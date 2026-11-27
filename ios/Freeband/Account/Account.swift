import Foundation

/// The person's Aravia account: Firebase Authentication with e-mail and password, reached over
/// REST (no SDK), plus a profile document with the username and the record of what they
/// accepted. Health data never goes to the account: it stays on the phone.
@MainActor
final class Account: ObservableObject {
    static let shared = Account()

    enum AccountError: LocalizedError {
        case message(String)
        var errorDescription: String? {
            switch self { case .message(let m): m }
        }
    }

    private enum Key {
        static let uid = "aravia.account.uid", email = "aravia.account.email", username = "aravia.account.username"
        static let verified = "aravia.account.verified", accepted = "aravia.account.termsVersion"
        static let refresh = "aravia-account-refresh"
    }

    @Published private(set) var uid: String?
    @Published private(set) var email: String?
    @Published private(set) var username: String?
    @Published private(set) var emailVerified = false
    /// The version of the terms this account accepted; behind `Legal.version` means accept again.
    @Published private(set) var acceptedVersion: Int?
    /// Active supporter Early Access entitlement synced with Firestore & aravia.app.
    @Published private(set) var supporter: SupporterAccess.Entitlement?
    /// True while a sign-in reads the account's state from the server: the screens wait instead
    /// of flashing the e-mail or terms steps the account has already done.
    @Published private(set) var checking = false

    private var idToken: String?
    private var idExpiry = Date.distantPast

    var isSignedIn: Bool { uid != nil && Vault.get(Key.refresh) != nil }
    var needsTerms: Bool { (acceptedVersion ?? 0) < Legal.version }
    var hasEarlyAccess: Bool { supporter?.earlyAccess == true }
    /// Signed in, e-mail confirmed and the current terms accepted: the app opens.
    var isReady: Bool { isSignedIn && emailVerified && !needsTerms }

    private init() {
        let d = UserDefaults.standard
        if Vault.get(Key.refresh) != nil {
            uid = d.string(forKey: Key.uid)
            email = d.string(forKey: Key.email)
            username = d.string(forKey: Key.username)
            emailVerified = d.bool(forKey: Key.verified)
            acceptedVersion = d.object(forKey: Key.accepted) as? Int
            if let uid {
                supporter = SupporterAccess.shared.loadCached(uid: uid)
            }
        }
    }

    // MARK: sign up, in, out

    /// Creates the account, claims the username and sends the confirmation e-mail.
    func signUp(username raw: String, email rawEmail: String, password: String, terms: Consent) async throws {
        let name = Self.normalize(raw)
        guard Self.isValidUsername(name) else {
            throw AccountError.message("O nome de usuário precisa ter de 3 a 20 caracteres: letras minúsculas, números, ponto ou _.")
        }
        let mail = rawEmail.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if try await usernameTaken(name) { throw AccountError.message("Esse nome de usuário já está em uso. Escolha outro.") }

        let json = try await auth("accounts:signUp", ["email": mail, "password": password, "returnSecureToken": true])
        try store(json)
        do {
            try await firestorePatch("usernames/\(name)", fields: ["uid": ["stringValue": uid ?? ""]], mustExist: false)
        } catch {
            // Someone took the name a moment ago: undo the new account so the e-mail is free again.
            _ = try? await auth("accounts:delete", ["idToken": try await token()])
            forget()
            throw AccountError.message("Esse nome de usuário acabou de ser usado por outra pessoa. Escolha outro.")
        }
        _ = try await auth("accounts:update", ["idToken": try await token(), "displayName": name, "returnSecureToken": false])
        set(username: name)
        try await saveProfile(consent: terms)
        try await sendVerification()
    }

    func signIn(email rawEmail: String, password: String) async throws {
        let mail = rawEmail.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let json = try await auth("accounts:signInWithPassword", ["email": mail, "password": password, "returnSecureToken": true])
        checking = true
        defer { checking = false }
        try store(json)
        // Read what this account already did (confirmed e-mail, accepted terms, supporter access)
        // before any screen decides what to ask.
        if let accepted = try? await readAccepted() { setAccepted(accepted) }
        try await refreshStatus()
    }

    private func readAccepted() async throws -> Int? {
        guard let uid, let profile = try await firestoreGet("users/\(uid)") else { return nil }
        let fields = profile["fields"] as? [String: Any] ?? [:]
        return ((fields["termsVersion"] as? [String: Any])?["integerValue"] as? String).flatMap(Int.init)
    }

    func signOut() {
        forget()
    }

    func sendVerification() async throws {
        _ = try await auth("accounts:sendOobCode", ["requestType": "VERIFY_EMAIL", "idToken": try await token()])
    }

    func sendPasswordReset(email rawEmail: String) async throws {
        let mail = rawEmail.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        _ = try await auth("accounts:sendOobCode", ["requestType": "PASSWORD_RESET", "email": mail])
    }

    /// Reads whether the e-mail was confirmed (a new token carries it to the database rules too),
    /// which terms were accepted, and any Early Access supporter entitlement.
    func refreshStatus() async throws {
        idExpiry = .distantPast
        let json = try await auth("accounts:lookup", ["idToken": try await token()])
        let user = (json["users"] as? [[String: Any]])?.first
        emailVerified = user?["emailVerified"] as? Bool ?? false
        UserDefaults.standard.set(emailVerified, forKey: Key.verified)
        if let name = user?["displayName"] as? String, !name.isEmpty { set(username: name) }
        if let uid, let profile = try await firestoreGet("users/\(uid)") {
            let fields = profile["fields"] as? [String: Any] ?? [:]
            if let v = (fields["termsVersion"] as? [String: Any])?["integerValue"] as? String { setAccepted(Int(v)) }
            if let fsEnt = SupporterAccess.parseFromFirestore(fields: fields) {
                applySupporter(fsEnt, uid: uid)
            }
        }
        // Best-effort silent sync with aravia.app so supporters who paid with their @usuario unlock automatically.
        if emailVerified {
            _ = try? await syncSupporterNow()
        }
    }

    /// Redeems a supporter code (`ARV1`/`ARV3`/`ARV12`/`ARVV-...`) on aravia.app and saves to Firestore.
    @discardableResult
    func redeemSupporterCode(_ code: String) async throws -> SupporterAccess.Entitlement {
        guard let uid else { throw AccountError.message("Entre na sua conta primeiro.") }
        let idTok = try await token()
        guard let ent = try await SupporterAccess.fetchFromServer(idToken: idTok, code: code), ent.earlyAccess else {
            throw AccountError.message("Esse código não libera Acesso Antecipado.")
        }
        try? await saveSupporterToFirestore(uid: uid, ent: ent)
        applySupporter(ent, uid: uid)
        return ent
    }

    /// Checks aravia.app for an approved support linked to this account's UID or `@usuario`.
    @discardableResult
    func syncSupporterNow() async throws -> SupporterAccess.Entitlement? {
        guard let uid else { return nil }
        let idTok = try await token()
        guard let serverEnt = try await SupporterAccess.fetchFromServer(idToken: idTok, code: nil), serverEnt.earlyAccess else {
            return supporter
        }
        let currentRank = SupporterAccess.tierRank(supporter?.tier)
        let nextRank = SupporterAccess.tierRank(serverEnt.tier)
        let chosen = (supporter != nil && currentRank > nextRank) ? supporter! : serverEnt
        if chosen != supporter {
            try? await saveSupporterToFirestore(uid: uid, ent: chosen)
            applySupporter(chosen, uid: uid)
        }
        return chosen
    }

    private func applySupporter(_ ent: SupporterAccess.Entitlement, uid: String) {
        supporter = ent
        SupporterAccess.shared.cache(uid: uid, entitlement: ent)
    }

    private func saveSupporterToFirestore(uid: String, ent: SupporterAccess.Entitlement) async throws {
        let query = [URLQueryItem(name: "updateMask.fieldPaths", value: "supporter")]
        var req = URLRequest(url: Self.docURL("users/\(uid)", query: query))
        req.httpMethod = "PATCH"
        FirebaseConfig.sign(&req)
        req.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["fields": ["supporter": SupporterAccess.firestoreMapField(ent)]])
        _ = try await URLSession.shared.data(for: req)
    }

    /// Records a new acceptance of the terms (when they change after sign-up).
    func accept(_ consent: Consent) async throws {
        try await saveProfile(consent: consent)
    }

    /// Deletes the account for good: the username, the profile, the person's place in a shared
    /// nest and the login itself. The password is asked again because Firebase only deletes
    /// after a recent sign-in.
    func deleteAccount(password: String) async throws {
        guard let email else { return }
        let json = try await auth("accounts:signInWithPassword", ["email": email, "password": password, "returnSecureToken": true])
        try store(json)
        await AraraNest.shared.leave()
        if let username { try? await firestoreDelete("usernames/\(username)") }
        if let uid { try? await firestoreDelete("users/\(uid)") }
        _ = try await auth("accounts:delete", ["idToken": try await token()])
        forget()
    }

    // MARK: profile

    struct Consent {
        var termsAccepted: Bool
        var healthConsent: Bool
        var adult: Bool
    }

    private func saveProfile(consent: Consent) async throws {
        guard let uid, consent.termsAccepted, consent.healthConsent, consent.adult else {
            throw AccountError.message("Para usar o Aravia é preciso aceitar os termos, autorizar o uso dos dados de saúde e ter 18 anos ou mais.")
        }
        let now = ISO8601DateFormatter().string(from: Date())
        try await firestorePatch("users/\(uid)", fields: [
            "username": ["stringValue": username ?? ""],
            "email": ["stringValue": email ?? ""],
            "termsVersion": ["integerValue": String(Legal.version)],
            "termsAcceptedAt": ["timestampValue": now],
            "healthConsentAt": ["timestampValue": now],
            "adultDeclaredAt": ["timestampValue": now],
            "platform": ["stringValue": "iOS"],
        ], mustExist: nil)
        setAccepted(Legal.version)
    }

    // MARK: username

    static func normalize(_ s: String) -> String {
        s.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    static func isValidUsername(_ s: String) -> Bool {
        (3...20).contains(s.count) && s.allSatisfy { ($0.isLetter && $0.isASCII) || $0.isNumber || $0 == "_" || $0 == "." }
    }

    func usernameTaken(_ name: String) async throws -> Bool {
        var req = URLRequest(url: Self.docURL("usernames/\(name)"))
        FirebaseConfig.sign(&req)
        let (_, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 404 { return false }
        if status == 200 { return true }
        throw AccountError.message("Não deu para conferir o nome de usuário (\(status)). Confira a internet.")
    }

    // MARK: tokens

    func token() async throws -> String {
        if let idToken, idExpiry > Date().addingTimeInterval(60) { return idToken }
        guard let refresh = Vault.get(Key.refresh) else { throw AccountError.message("Entre na sua conta de novo.") }
        var req = URLRequest(url: URL(string: "https://securetoken.googleapis.com/v1/token?key=\(FirebaseConfig.apiKey)")!)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = Data("grant_type=refresh_token&refresh_token=\(refresh)".utf8)
        let json = try await send(req)
        guard let t = json["id_token"] as? String else { throw AccountError.message("Entre na sua conta de novo.") }
        idToken = t
        idExpiry = Date().addingTimeInterval(Double(json["expires_in"] as? String ?? "3000") ?? 3000)
        if let r = json["refresh_token"] as? String { Vault.set(r, for: Key.refresh) }
        return t
    }

    private func store(_ json: [String: Any]) throws {
        guard let t = json["idToken"] as? String, let r = json["refreshToken"] as? String, let id = json["localId"] as? String else {
            throw AccountError.message("O servidor não confirmou a entrada. Tente de novo.")
        }
        idToken = t
        idExpiry = Date().addingTimeInterval(Double(json["expiresIn"] as? String ?? "3000") ?? 3000)
        Vault.set(r, for: Key.refresh)
        uid = id
        UserDefaults.standard.set(id, forKey: Key.uid)
        if let mail = json["email"] as? String {
            email = mail
            UserDefaults.standard.set(mail, forKey: Key.email)
        }
    }

    private func set(username name: String) {
        username = name
        UserDefaults.standard.set(name, forKey: Key.username)
    }

    private func setAccepted(_ v: Int?) {
        acceptedVersion = v
        UserDefaults.standard.set(v, forKey: Key.accepted)
    }

    private func forget() {
        Vault.remove(Key.refresh)
        for k in [Key.uid, Key.email, Key.username, Key.verified, Key.accepted] { UserDefaults.standard.removeObject(forKey: k) }
        SupporterAccess.shared.clearCache()
        idToken = nil
        uid = nil
        email = nil
        username = nil
        emailVerified = false
        acceptedVersion = nil
        supporter = nil
    }

    // MARK: REST

    private func auth(_ method: String, _ body: [String: Any]) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: "https://identitytoolkit.googleapis.com/v1/\(method)?key=\(FirebaseConfig.apiKey)")!)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        // Firebase writes its e-mails (confirmation, new password) in this language.
        req.setValue("pt-BR", forHTTPHeaderField: "X-Firebase-Locale")
        req.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(req)
    }

    private func send(_ request: URLRequest) async throws -> [String: Any] {
        var req = request
        FirebaseConfig.sign(&req)
        let (data, response) = try await URLSession.shared.data(for: req)
        let json = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            let code = ((json["error"] as? [String: Any])?["message"] as? String) ?? ""
            throw AccountError.message(Self.explain(code))
        }
        return json
    }

    /// Firebase's error codes, in words.
    static func explain(_ code: String) -> String {
        let key = code.split(separator: " ").first.map(String.init) ?? code
        switch key {
        case "EMAIL_EXISTS": return "Já existe uma conta com esse e-mail. Entre com ele ou recupere a senha."
        case "INVALID_EMAIL": return "Esse e-mail não parece válido."
        case "WEAK_PASSWORD": return "Senha fraca: use pelo menos 8 caracteres, com letras e números."
        case "INVALID_LOGIN_CREDENTIALS", "INVALID_PASSWORD", "EMAIL_NOT_FOUND": return "E-mail ou senha incorretos."
        case "USER_DISABLED": return "Essa conta foi desativada."
        case "TOO_MANY_ATTEMPTS_TRY_LATER": return "Muitas tentativas. Espere alguns minutos e tente de novo."
        case "OPERATION_NOT_ALLOWED", "ADMIN_ONLY_OPERATION": return "O cadastro por e-mail ainda não foi ligado no servidor do Aravia."
        case "CREDENTIAL_TOO_OLD_LOGIN_AGAIN": return "Por segurança, digite a senha de novo."
        case "": return "Não deu para falar com o servidor. Confira a internet."
        default: return "O servidor respondeu: \(code)"
        }
    }

    static func docURL(_ path: String, query: [URLQueryItem] = []) -> URL {
        var c = URLComponents(string: "https://firestore.googleapis.com/v1/projects/\(FirebaseConfig.projectID)/databases/(default)/documents/\(path)")!
        if !query.isEmpty { c.queryItems = query }
        return c.url!
    }

    private func firestoreGet(_ path: String) async throws -> [String: Any]? {
        var req = URLRequest(url: Self.docURL(path))
        FirebaseConfig.sign(&req)
        req.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: req)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private func firestorePatch(_ path: String, fields: [String: Any], mustExist: Bool?) async throws {
        var query: [URLQueryItem] = []
        if let mustExist { query.append(URLQueryItem(name: "currentDocument.exists", value: mustExist ? "true" : "false")) }
        var req = URLRequest(url: Self.docURL(path, query: query))
        req.httpMethod = "PATCH"
        FirebaseConfig.sign(&req)
        req.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["fields": fields])
        let (_, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw AccountError.message("O servidor recusou a gravação (\(status)).") }
    }

    private func firestoreDelete(_ path: String) async throws {
        var req = URLRequest(url: Self.docURL(path))
        req.httpMethod = "DELETE"
        FirebaseConfig.sign(&req)
        req.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
        _ = try await URLSession.shared.data(for: req)
    }
}
