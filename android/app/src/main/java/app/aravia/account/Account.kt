package app.aravia.account

import app.aravia.legal.LegalText
import com.google.firebase.Timestamp
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await

/**
 * The person's Aravia account, the same one as on iOS: Firebase e-mail and password, the
 * username in `usernames/{name}` and the record of what was accepted in `users/{uid}`, with the
 * same fields the iOS app writes. Health data never goes here.
 */
object Account {
    data class State(
        val uid: String? = null,
        val email: String? = null,
        val username: String? = null,
        val emailVerified: Boolean = false,
        val acceptedVersion: Int? = null,
        /** Reading the account's state from the server after a sign-in. */
        val checking: Boolean = false,
        /** Verified Early Access / supporter entitlement saved on `users/{uid}.supporter`. */
        val supporter: SupporterAccess.Entitlement? = null,
    ) {
        val signedIn get() = uid != null
        val needsTerms get() = (acceptedVersion ?: 0) < LegalText.VERSION
        val ready get() = signedIn && emailVerified && !needsTerms
        val hasEarlyAccess get() = supporter?.earlyAccess == true
    }

    data class Consent(val terms: Boolean, val health: Boolean, val adult: Boolean)

    class Problem(message: String) : Exception(message)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val auth get() = FirebaseAuth.getInstance()
    private val db get() = FirebaseFirestore.getInstance()

    /** At launch: a session kept by Firebase opens straight in, then its state is refreshed. */
    suspend fun restore() {
        if (!FirebaseSetup.isConfigured) return
        val user = auth.currentUser ?: return
        val cachedSup = SupporterAccess.loadCached(user.uid)
        _state.value = State(uid = user.uid, email = user.email, username = user.displayName,
            emailVerified = user.isEmailVerified, checking = true, supporter = cachedSup)
        runCatching { refresh() }
        _state.value = _state.value.copy(checking = false)
    }

    fun normalize(s: String) = s.trim().lowercase()

    fun isValidUsername(s: String) =
        s.length in 3..20 && s.all { (it in 'a'..'z') || it.isDigit() || it == '_' || it == '.' }

    suspend fun signUp(rawUsername: String, rawEmail: String, password: String, consent: Consent) {
        requireConfigured()
        val name = normalize(rawUsername)
        if (!isValidUsername(name)) throw Problem("O nome de usuário precisa ter de 3 a 20 caracteres: letras minúsculas, números, ponto ou _.")
        if (!(consent.terms && consent.health && consent.adult)) throw Problem(CONSENT_NEEDED)
        if (usernameTaken(name)) throw Problem("Esse nome de usuário já está em uso. Escolha outro.")
        auth.setLanguageCode("pt-BR")
        val user = try {
            auth.createUserWithEmailAndPassword(rawEmail.trim().lowercase(), password).await().user!!
        } catch (e: Exception) { throw Problem(explain(e)) }
        try {
            db.collection("usernames").document(name).set(mapOf("uid" to user.uid)).await()
        } catch (e: Exception) {
            // Someone took the name a moment ago: undo the new account so the e-mail is free again.
            runCatching { user.delete().await() }
            throw Problem("Esse nome de usuário acabou de ser usado por outra pessoa. Escolha outro.")
        }
        user.updateProfile(com.google.firebase.auth.UserProfileChangeRequest.Builder().setDisplayName(name).build()).await()
        // Save consent and send the verification e-mail BEFORE publishing signedIn state:
        // updating _state earlier swaps SignUp out of composition and cancels its rememberCoroutineScope.
        saveConsent(consent, uid = user.uid, username = name, email = user.email ?: rawEmail.trim().lowercase())
        runCatching { user.sendEmailVerification().await() }
        _state.value = State(uid = user.uid, email = user.email, username = name, acceptedVersion = LegalText.VERSION)
    }

    suspend fun signIn(rawEmail: String, password: String) {
        requireConfigured()
        // Do NOT flip state.checking = true here: AccountFlow swaps SignIn out of composition
        // when state.checking is true, which cancels SignIn's rememberCoroutineScope mid-flight.
        // SignIn shows its own busy spinner until auth + refresh finish atomically.
        try {
            val user = auth.signInWithEmailAndPassword(rawEmail.trim().lowercase(), password).await().user
                ?: throw Problem("Não foi possível concluir o login agora. Tente de novo.")
            refresh(initialUser = user)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Problem) {
            throw e
        } catch (e: Exception) {
            throw Problem(explain(e))
        }
    }

    /** Whether the e-mail was confirmed (a fresh token carries it to the database rules too),
     *  which terms were accepted, and the account's Early Access supporter entitlement. */
    suspend fun refresh(initialUser: com.google.firebase.auth.FirebaseUser? = null) {
        val user = initialUser ?: auth.currentUser ?: return
        runCatching { user.reload().await() }
        val fresh = auth.currentUser ?: user
        val idToken = if (fresh.isEmailVerified) runCatching { fresh.getIdToken(true).await().token }.getOrNull() else null
        val profile = runCatching { db.collection("users").document(fresh.uid).get().await() }.getOrNull()
        val accepted = profile?.getLong("termsVersion")?.toInt()
        var supporter = SupporterAccess.parseFromFirestore(profile) ?: SupporterAccess.loadCached(fresh.uid)

        // Silently sync from aravia.app/api/resgatar so payments made with @usuario unlock
        // automatically on the user's account and pick up any tier upgrades.
        if (!idToken.isNullOrBlank()) {
            val serverEnt = runCatching { SupporterAccess.fetchFromServer(idToken) }.getOrNull()
            if (serverEnt != null && serverEnt.earlyAccess) {
                val shouldUpdate = supporter == null ||
                    SupporterAccess.tierRank(serverEnt.tier) >= SupporterAccess.tierRank(supporter.tier)
                if (shouldUpdate) {
                    supporter = serverEnt
                    runCatching { SupporterAccess.saveToFirestore(db, fresh.uid, serverEnt) }
                }
            }
        }
        SupporterAccess.cache(fresh.uid, supporter)
        _state.value = State(
            uid = fresh.uid,
            email = fresh.email,
            username = fresh.displayName ?: profile?.getString("username"),
            emailVerified = fresh.isEmailVerified,
            acceptedVersion = accepted,
            checking = false,
            supporter = supporter,
        )
    }

    /** Redeems a supporter code (ARV1/ARV3/ARV12/ARVV) against aravia.app and saves it to the user's Firestore account. */
    suspend fun redeemSupporterCode(code: String): SupporterAccess.Entitlement {
        requireConfigured()
        val user = auth.currentUser ?: throw Problem("Faça login na sua conta do Aravia primeiro.")
        val idToken = user.getIdToken(true).await().token
            ?: throw Problem("Não foi possível confirmar sua sessão agora. Tente de novo.")
        val ent = SupporterAccess.fetchFromServer(idToken, code)
            ?: throw Problem("Esse código não libera acesso antecipado no app.")
        runCatching { SupporterAccess.saveToFirestore(db, user.uid, ent) }
        SupporterAccess.cache(user.uid, ent)
        _state.value = _state.value.copy(supporter = ent)
        return ent
    }

    /** Re-checks aravia.app for an approved Early Access support linked to this account or @usuario. */
    suspend fun syncSupporterNow(): SupporterAccess.Entitlement? {
        requireConfigured()
        val user = auth.currentUser ?: throw Problem("Faça login na sua conta do Aravia primeiro.")
        val idToken = user.getIdToken(true).await().token
            ?: throw Problem("Não foi possível confirmar sua sessão agora.")
        val ent = SupporterAccess.fetchFromServer(idToken)
        if (ent != null && ent.earlyAccess) {
            runCatching { SupporterAccess.saveToFirestore(db, user.uid, ent) }
            SupporterAccess.cache(user.uid, ent)
            _state.value = _state.value.copy(supporter = ent)
        }
        return ent
    }

    suspend fun sendVerification() {
        auth.setLanguageCode("pt-BR")
        auth.currentUser?.sendEmailVerification()?.await()
    }

    suspend fun sendPasswordReset(rawEmail: String) {
        requireConfigured()
        auth.setLanguageCode("pt-BR")
        try { auth.sendPasswordResetEmail(rawEmail.trim().lowercase()).await() } catch (e: Exception) { throw Problem(explain(e)) }
    }

    suspend fun accept(consent: Consent) = saveConsent(consent)

    fun signOut() {
        auth.signOut()
        SupporterAccess.clearCache()
        _state.value = State()
    }

    /** Deletes the account for good: username, profile, the place in a shared nest and the login.
     *  The password is asked again because Firebase only deletes after a recent sign-in. */
    suspend fun deleteAccount(password: String) {
        val user = auth.currentUser ?: return
        val email = user.email ?: return
        try {
            user.reauthenticate(EmailAuthProvider.getCredential(email, password)).await()
        } catch (e: Exception) { throw Problem(explain(e)) }
        app.aravia.arara.AraraNest.leave()
        _state.value.username?.let { runCatching { db.collection("usernames").document(it).delete().await() } }
        runCatching { db.collection("users").document(user.uid).delete().await() }
        user.delete().await()
        _state.value = State()
    }

    private suspend fun saveConsent(
        consent: Consent,
        uid: String? = _state.value.uid,
        username: String = _state.value.username ?: "",
        email: String = _state.value.email ?: "",
    ) {
        val targetUid = uid ?: return
        if (!(consent.terms && consent.health && consent.adult)) throw Problem(CONSENT_NEEDED)
        val now = Timestamp.now()
        db.collection("users").document(targetUid).set(mapOf(
            "username" to username,
            "email" to email,
            "termsVersion" to LegalText.VERSION.toLong(),
            "termsAcceptedAt" to now,
            "healthConsentAt" to now,
            "adultDeclaredAt" to now,
            "platform" to "Android",
        ), com.google.firebase.firestore.SetOptions.merge()).await()
        if (_state.value.uid == targetUid) _state.value = _state.value.copy(acceptedVersion = LegalText.VERSION)
    }

    private suspend fun usernameTaken(name: String): Boolean = try {
        db.collection("usernames").document(name).get().await().exists()
    } catch (e: FirebaseFirestoreException) {
        throw Problem("Não deu para conferir o nome de usuário. Confira a internet.")
    }

    private fun requireConfigured() {
        if (!FirebaseSetup.isConfigured) throw Problem("O servidor de contas do Aravia ainda não foi ligado neste app.")
    }

    private const val CONSENT_NEEDED =
        "Para usar o Aravia é preciso aceitar os termos, autorizar o uso dos dados de saúde e ter 18 anos ou mais."

    /** Firebase's errors in words, as on iOS. */
    fun explain(e: Exception): String {
        val code = (e as? FirebaseAuthException)?.errorCode ?: ""
        return when (code) {
            "ERROR_EMAIL_ALREADY_IN_USE" -> "Já existe uma conta com esse e-mail. Entre com ele ou recupere a senha."
            "ERROR_INVALID_EMAIL" -> "Esse e-mail não parece válido."
            "ERROR_WEAK_PASSWORD" -> "Senha fraca: use pelo menos 8 caracteres, com letras e números."
            "ERROR_INVALID_CREDENTIAL", "ERROR_WRONG_PASSWORD", "ERROR_USER_NOT_FOUND" -> "E-mail ou senha incorretos."
            "ERROR_USER_DISABLED" -> "Essa conta foi desativada."
            "ERROR_TOO_MANY_REQUESTS" -> "Muitas tentativas. Espere alguns minutos e tente de novo."
            "ERROR_OPERATION_NOT_ALLOWED" -> "O cadastro por e-mail ainda não foi ligado no servidor do Aravia."
            "ERROR_REQUIRES_RECENT_LOGIN" -> "Por segurança, digite a senha de novo."
            else -> if (e is com.google.firebase.FirebaseNetworkException) "Não deu para falar com o servidor. Confira a internet."
            else e.localizedMessage ?: "Algo deu errado. Tente de novo."
        }
    }
}
