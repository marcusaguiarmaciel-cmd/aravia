package app.aravia.arara

import android.content.Context
import app.aravia.account.Account
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * The arara in the person's account and the nest it may share, the same documents the iOS app
 * uses: `users/{uid}.arara` (name, highest stage, points per phone) and `nests/{code}` (name,
 * members with their points). The arara takes the largest steps and routes among the person's
 * phones and adds up workouts, so a watch synced to two phones is not counted twice.
 */
object AraraNest {
    data class Member(val id: String, val name: String, val xp: Int)

    data class State(
        val name: String = "Arara",
        val code: String? = null,
        val members: List<Member> = emptyList(),
        val accountXp: Int? = null,
        val highestStage: Int? = null,
        val celebratedAt: Long? = null,
        val busy: Boolean = false,
        val error: String? = null,
        val pendingInvite: String? = null,
    ) {
        val inNest get() = code != null
    }

    data class Contribution(val steps: Int, val routes: Int, val workouts: Int)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private lateinit var prefs: android.content.SharedPreferences
    private val db get() = FirebaseFirestore.getInstance()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("aravia", Context.MODE_PRIVATE)
        _state.value = _state.value.copy(
            name = prefs.getString("araraName", "Arara") ?: "Arara",
            code = prefs.getString("nestCode", null),
            highestStage = if (prefs.contains("araraStage")) prefs.getInt("araraStage", 0) else null,
        )
    }

    private val deviceId: String
        get() = prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().lowercase().also {
            prefs.edit().putString("deviceId", it).apply()
        }

    /** Everyone's points in the nest, with this account's taken fresh. */
    fun totalXp(mine: Int): Int {
        val s = _state.value
        if (!s.inNest) return mine
        val uid = Account.state.value.uid
        return mine + s.members.filter { it.id != uid }.sumOf { it.xp }
    }

    fun myName(): String = prefs.getString("nestMyName", null) ?: Account.state.value.username ?: "Eu"
    fun setMyName(n: String) = prefs.edit().putString("nestMyName", n).apply()

    /** Sends this phone's numbers, reads every phone's back and works out the account's points,
     *  name and highest stage; then updates the nest. */
    suspend fun syncAccount(mine: Contribution) {
        val uid = Account.state.value.uid ?: return
        try {
            val doc = db.collection("users").document(uid)
            doc.update(FieldPath.of("arara", "devices", deviceId), mapOf(
                "steps" to mine.steps.toLong(), "routes" to mine.routes.toLong(), "workouts" to mine.workouts.toLong(),
                "platform" to "Android", "updatedAt" to Timestamp.now(),
            )).await()
            val snap = doc.get().await()
            @Suppress("UNCHECKED_CAST")
            val arara = snap.get("arara") as? Map<String, Any?> ?: emptyMap()
            @Suppress("UNCHECKED_CAST")
            val devices = arara["devices"] as? Map<String, Map<String, Any?>> ?: emptyMap()
            var steps = mine.steps; var routes = mine.routes; var workouts = 0
            for (d in devices.values) {
                steps = maxOf(steps, (d["steps"] as? Number)?.toInt() ?: 0)
                routes = maxOf(routes, (d["routes"] as? Number)?.toInt() ?: 0)
                workouts += (d["workouts"] as? Number)?.toInt() ?: 0
            }
            if (devices.isEmpty()) workouts = mine.workouts
            val name = (arara["name"] as? String)?.takeIf { it.isNotBlank() }
            if (name != null && !_state.value.inNest) {
                prefs.edit().putString("araraName", name).apply()
                _state.value = _state.value.copy(name = name)
            }
            val serverStage = (arara["stage"] as? Number)?.toInt()
            val total = AraraProgress.xp(steps, routes, workouts)
            _state.value = _state.value.copy(accountXp = total,
                highestStage = maxOf(_state.value.highestStage ?: -1, serverStage ?: -1).takeIf { it >= 0 })
            if (_state.value.inNest) syncNest(total)
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.localizedMessage)
        }
    }

    /** Feathers fly when the arara reaches a stage no phone of this account had reached. */
    suspend fun noteStage(stage: AraraStage) {
        val known = _state.value.highestStage ?: -1
        if (stage.ordinal <= known) return
        _state.value = _state.value.copy(highestStage = stage.ordinal,
            celebratedAt = if (known >= 0) System.nanoTime() else null)
        prefs.edit().putInt("araraStage", stage.ordinal).apply()
        val uid = Account.state.value.uid ?: return
        runCatching { db.collection("users").document(uid).update(FieldPath.of("arara", "stage"), stage.ordinal.toLong()).await() }
    }

    suspend fun rename(raw: String) {
        val clean = raw.trim().take(24)
        if (clean.isEmpty()) return
        prefs.edit().putString("araraName", clean).apply()
        _state.value = _state.value.copy(name = clean)
        Account.state.value.uid?.let { uid ->
            runCatching { db.collection("users").document(uid).update(FieldPath.of("arara", "name"), clean).await() }
        }
        _state.value.code?.let { code -> runCatching { db.collection("nests").document(code).update("name", clean).await() } }
    }

    // nest

    suspend fun create(myXp: Int) = act {
        val uid = Account.state.value.uid ?: throw Account.Problem("Entre na sua conta de novo.")
        val code = newCode()
        val ref = db.collection("nests").document(code)
        db.runTransaction { tx ->
            if (tx.get(ref).exists()) throw Account.Problem("Tente de novo.")
            tx.set(ref, mapOf("name" to _state.value.name, "members" to mapOf(uid to member(myXp))))
        }.await()
        setCode(code)
        pull()
    }

    suspend fun join(typed: String, myXp: Int) = act {
        val uid = Account.state.value.uid ?: throw Account.Problem("Entre na sua conta de novo.")
        val code = typed.uppercase().filter { it.isLetterOrDigit() }
        val ref = db.collection("nests").document(code)
        if (!ref.get().await().exists()) throw Account.Problem("Nenhum ninho com esse código. Confira as letras.")
        ref.update(FieldPath.of("members", uid), member(myXp)).await()
        setCode(code)
        pull()
    }

    suspend fun leave() {
        val code = _state.value.code ?: return
        val uid = Account.state.value.uid
        if (uid != null) runCatching { db.collection("nests").document(code).update(FieldPath.of("members", uid), FieldValue.delete()).await() }
        setCode(null)
        _state.value = _state.value.copy(members = emptyList())
    }

    suspend fun syncNest(myXp: Int) {
        val code = _state.value.code ?: return
        val uid = Account.state.value.uid ?: return
        runCatching {
            db.collection("nests").document(code).update(FieldPath.of("members", uid), member(myXp)).await()
            pull()
        }.onFailure { _state.value = _state.value.copy(error = it.localizedMessage) }
    }

    fun handleInvite(uri: android.net.Uri?) {
        // aravia://ninho/CODE
        if (uri?.scheme != "aravia" || uri.host != "ninho") return
        val code = uri.lastPathSegment?.uppercase() ?: return
        if (code.length >= 4) _state.value = _state.value.copy(pendingInvite = code)
    }

    fun clearInvite() { _state.value = _state.value.copy(pendingInvite = null) }

    private suspend fun act(work: suspend () -> Unit) {
        _state.value = _state.value.copy(busy = true, error = null)
        try { work() } catch (e: Exception) {
            _state.value = _state.value.copy(error = e.localizedMessage ?: "Algo deu errado.")
        } finally { _state.value = _state.value.copy(busy = false) }
    }

    private suspend fun pull() {
        val code = _state.value.code ?: return
        val snap = db.collection("nests").document(code).get().await()
        val uid = Account.state.value.uid
        @Suppress("UNCHECKED_CAST")
        val members = (snap.get("members") as? Map<String, Map<String, Any?>> ?: emptyMap()).map { (id, m) ->
            val n = m["name"] as? String ?: "?"
            Member(id, if (id == uid) "$n (você)" else n, (m["xp"] as? Number)?.toInt() ?: 0)
        }.sortedByDescending { it.xp }
        val name = snap.getString("name")
        if (name != null) prefs.edit().putString("araraName", name).apply()
        _state.value = _state.value.copy(members = members, name = name ?: _state.value.name)
    }

    private fun member(xp: Int) = mapOf("name" to myName(), "xp" to xp.toLong(), "updatedAt" to Timestamp.now())

    private fun setCode(c: String?) {
        prefs.edit().apply { if (c == null) remove("nestCode") else putString("nestCode", c) }.apply()
        _state.value = _state.value.copy(code = c)
    }

    /** Six letters without look-alikes (no 0/O, 1/I/L), as on iOS. */
    private fun newCode(): String {
        val letters = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        return (1..6).map { letters.random() }.joinToString("")
    }

    fun invite(code: String, name: String) =
        "Vem cuidar da arara $name comigo no Aravia! 🦜\n\nNo app: Treino › toque na arara › Recebeu um convite? e digite o código $code\n\nou abra: aravia://ninho/$code"
}
