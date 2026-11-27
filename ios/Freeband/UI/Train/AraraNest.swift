import SwiftUI

/// Firebase project for the shared nest (Firestore + anonymous sign-in, REST only, no SDK).
/// Both values come from the GoogleService-Info.plist of the "Aravia" Firebase project; an API
/// key of a Firebase web/iOS app is not a secret (access is decided by the Firestore rules).
enum FirebaseConfig {
    static let apiKey = "AIzaSyBs71tdEJHEIPAP16lIRabA0tt-RwzZnoM"
    static let projectID = "aravia-7b078"

    static var isConfigured: Bool { !apiKey.isEmpty && !projectID.isEmpty }

    /// Firebase's iOS keys are usually restricted to the app; REST calls name it in this header.
    static func sign(_ request: inout URLRequest) {
        request.setValue(Bundle.main.bundleIdentifier ?? "app.aravia.watch", forHTTPHeaderField: "X-Ios-Bundle-Identifier")
    }
}

/// The arara's name and the nest it may share with other people: everyone's points add up to
/// one arara. A nest is one Firestore document, nests/{code}, holding the name and each member's
/// name and points.
@MainActor
final class AraraNest: ObservableObject {
    static let shared = AraraNest()

    struct Member: Identifiable, Hashable {
        let id: String
        var name: String
        var xp: Int
    }

    enum NestError: LocalizedError {
        case notConfigured, notFound, server(String)
        var errorDescription: String? {
            switch self {
            case .notConfigured: "O ninho compartilhado ainda não foi ligado ao servidor do app."
            case .notFound: "Nenhum ninho com esse código. Confira as letras."
            case .server(let m): m
            }
        }
    }

    private enum Key {
        static let name = "aravia.araraName", code = "aravia.nestCode", me = "aravia.nestMyName"
        static let stage = "aravia.araraStage"
    }

    @Published private(set) var name: String
    @Published private(set) var code: String?
    @Published var myName: String { didSet { UserDefaults.standard.set(myName, forKey: Key.me) } }
    @Published private(set) var members: [Member] = []
    @Published private(set) var busy = false
    @Published var error: String?
    @Published private(set) var celebratedAt: Date?

    private var idToken: String?
    private var idExpiry = Date.distantPast
    private var uid: String? { Account.shared.uid }

    private init() {
        name = UserDefaults.standard.string(forKey: Key.name) ?? "Arara"
        code = UserDefaults.standard.string(forKey: Key.code)
        myName = UserDefaults.standard.string(forKey: Key.me) ?? Account.shared.username ?? "Eu"
    }

    /// This account's points across all its phones (iOS and Android), read from the account;
    /// nil until the first read.
    @Published private(set) var accountXP: Int?
    private var serverStage: Int?

    /// One phone's share of the points. Several phones may sync the same watch, so the arara takes
    /// the largest step and route counts among them and adds up workouts, which each phone logs
    /// on its own (docs: Aravia para Android, plano › Sincronização).
    struct Contribution: Equatable {
        var steps: Int
        var routes: Int
        var workouts: Int
    }

    static func xp(steps: Int, routes: Int, workouts: Int) -> Int { steps / 1000 + routes * 10 + workouts * 10 }

    /// A fixed id for this phone, so each phone writes only its own entry.
    private var deviceID: String {
        if let id = UserDefaults.standard.string(forKey: "aravia.deviceID") { return id }
        let id = UUID().uuidString.lowercased()
        UserDefaults.standard.set(id, forKey: "aravia.deviceID")
        return id
    }

    /// Sends this phone's numbers to `users/{uid}.arara.devices.{phone}`, reads every phone's back
    /// and works out the account's points, name and highest stage; then updates the nest.
    func syncAccount(_ mine: Contribution) async {
        guard FirebaseConfig.isConfigured, let uid = Account.shared.uid else { return }
        do {
            let entry: [String: Any] = ["mapValue": ["fields": [
                "steps": ["integerValue": String(mine.steps)],
                "routes": ["integerValue": String(mine.routes)],
                "workouts": ["integerValue": String(mine.workouts)],
                "platform": ["stringValue": "iOS"],
                "updatedAt": ["timestampValue": ISO8601DateFormatter().string(from: Date())],
            ]]]
            try await accountPatch(uid, fields: ["arara": ["mapValue": ["fields": [
                "devices": ["mapValue": ["fields": [deviceID: entry]]]]]]],
                mask: ["arara.devices.`\(deviceID)`"])
            if let doc = try await accountGet(uid) { readArara(doc, fallback: mine) }
            if inNest, let total = accountXP { await sync(myXP: total) }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func readArara(_ doc: [String: Any], fallback: Contribution) {
        let fields = doc["fields"] as? [String: Any] ?? [:]
        let arara = (fields["arara"] as? [String: Any])?["mapValue"] as? [String: Any]
        let araraFields = arara?["fields"] as? [String: Any] ?? [:]
        if let n = (araraFields["name"] as? [String: Any])?["stringValue"] as? String, !n.isEmpty, !inNest {
            name = n
            UserDefaults.standard.set(n, forKey: Key.name)
        }
        serverStage = ((araraFields["stage"] as? [String: Any])?["integerValue"] as? String).flatMap(Int.init)
        let devices = ((araraFields["devices"] as? [String: Any])?["mapValue"] as? [String: Any])?["fields"] as? [String: Any] ?? [:]
        var steps = fallback.steps, routes = fallback.routes, workouts = 0
        for (_, value) in devices {
            let f = ((value as? [String: Any])?["mapValue"] as? [String: Any])?["fields"] as? [String: Any] ?? [:]
            func int(_ k: String) -> Int { Int((f[k] as? [String: Any])?["integerValue"] as? String ?? "0") ?? 0 }
            steps = max(steps, int("steps"))
            routes = max(routes, int("routes"))
            workouts += int("workouts")
        }
        if devices.isEmpty { workouts = fallback.workouts }
        accountXP = Self.xp(steps: steps, routes: routes, workouts: workouts)
    }

    private func accountGet(_ uid: String) async throws -> [String: Any]? {
        var req = URLRequest(url: Account.docURL("users/\(uid)"))
        FirebaseConfig.sign(&req)
        req.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: req)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private func accountPatch(_ uid: String, fields: [String: Any], mask: [String]) async throws {
        let query = mask.map { URLQueryItem(name: "updateMask.fieldPaths", value: $0) }
        var req = URLRequest(url: Account.docURL("users/\(uid)", query: query))
        req.httpMethod = "PATCH"
        FirebaseConfig.sign(&req)
        req.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["fields": fields])
        let (_, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200 else { throw NestError.server("A conta não aceitou os dados da arara (\(status)).") }
    }

    var inNest: Bool { code != nil }

    /// Everyone's points in the nest, with this phone's taken fresh.
    func totalXP(mine: Int) -> Int {
        guard inNest else { return mine }
        return mine + members.filter { $0.id != uid }.reduce(0) { $0 + $1.xp }
    }

    /// Feathers fly when the arara reaches a stage it had not reached before.
    func noteStage(_ stage: AraraStage) {
        let local = UserDefaults.standard.object(forKey: Key.stage) as? Int
        let known = max(local ?? -1, serverStage ?? -1)
        if known >= 0, stage.rawValue > known { celebratedAt = Date() }
        if stage.rawValue > known {
            UserDefaults.standard.set(stage.rawValue, forKey: Key.stage)
            serverStage = stage.rawValue
            // The highest stage lives in the account, so the feathers fly once, on one phone.
            if let uid = Account.shared.uid {
                Task { try? await accountPatch(uid, fields: ["arara": ["mapValue": ["fields": [
                    "stage": ["integerValue": String(stage.rawValue)]]]]], mask: ["arara.stage"]) }
            }
        }
    }

    func rename(_ new: String) async {
        let clean = String(new.trimmingCharacters(in: .whitespacesAndNewlines).prefix(24))
        guard !clean.isEmpty else { return }
        name = clean
        UserDefaults.standard.set(clean, forKey: Key.name)
        if let uid = Account.shared.uid {
            try? await accountPatch(uid, fields: ["arara": ["mapValue": ["fields": ["name": ["stringValue": clean]]]]],
                                    mask: ["arara.name"])
        }
        guard let code else { return }
        await run { try await self.patch(code, fields: ["name": ["stringValue": clean]], mask: ["name"]) }
    }

    // MARK: nest

    func create(myXP: Int) async {
        await run {
            let new = Self.newCode()
            let me = try await self.signIn()
            let fields: [String: Any] = [
                "name": ["stringValue": self.name],
                "members": ["mapValue": ["fields": [me: self.memberValue(xp: myXP)]]],
            ]
            try await self.patch(new, fields: fields, mask: nil, mustExist: false)
            self.setCode(new)
            try await self.pull()
        }
    }

    func join(_ typed: String, myXP: Int) async {
        let wanted = typed.uppercased().filter { $0.isLetter || $0.isNumber }
        await run {
            let me = try await self.signIn()
            guard try await self.fetch(wanted) != nil else { throw NestError.notFound }
            try await self.patch(wanted, fields: ["members": ["mapValue": ["fields": [me: self.memberValue(xp: myXP)]]]],
                                 mask: ["members.`\(me)`"])
            self.setCode(wanted)
            try await self.pull()
        }
    }

    func leave() async {
        guard let code else { return }
        await run {
            let me = try await self.signIn()
            // Masking a field that the body leaves out deletes it.
            try await self.patch(code, fields: [:], mask: ["members.`\(me)`"])
        }
        setCode(nil)
        members = []
    }

    /// Sends this phone's points and reads everyone's.
    func sync(myXP: Int) async {
        guard let code, FirebaseConfig.isConfigured else { return }
        do {
            let me = try await signIn()
            try await patch(code, fields: ["members": ["mapValue": ["fields": [me: memberValue(xp: myXP)]]]],
                            mask: ["members.`\(me)`"])
            try await pull()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func handle(_ url: URL) {
        // aravia://ninho/CODE
        guard url.scheme == "aravia", url.host == "ninho", let c = url.pathComponents.last, c.count >= 4 else { return }
        pendingInvite = c.uppercased()
    }

    /// A code from an invitation link, waiting for the person to confirm joining.
    @Published var pendingInvite: String?

    private func setCode(_ c: String?) {
        code = c
        UserDefaults.standard.set(c, forKey: Key.code)
    }

    private func run(_ work: @escaping () async throws -> Void) async {
        guard FirebaseConfig.isConfigured else { error = NestError.notConfigured.localizedDescription; return }
        busy = true
        error = nil
        defer { busy = false }
        do { try await work() } catch { self.error = error.localizedDescription }
    }

    private func pull() async throws {
        guard let code, let doc = try await fetch(code) else { return }
        let fields = doc["fields"] as? [String: Any] ?? [:]
        if let n = (fields["name"] as? [String: Any])?["stringValue"] as? String {
            name = n
            UserDefaults.standard.set(n, forKey: Key.name)
        }
        let map = ((fields["members"] as? [String: Any])?["mapValue"] as? [String: Any])?["fields"] as? [String: Any] ?? [:]
        members = map.compactMap { id, value in
            guard let f = (value as? [String: Any])?["mapValue"] as? [String: Any],
                  let inner = f["fields"] as? [String: Any] else { return nil }
            let n = (inner["name"] as? [String: Any])?["stringValue"] as? String ?? "?"
            let xp = Int((inner["xp"] as? [String: Any])?["integerValue"] as? String ?? "0") ?? 0
            return Member(id: id, name: id == uid ? "\(n) (você)" : n, xp: xp)
        }
        .sorted { $0.xp > $1.xp }
    }

    private func memberValue(xp: Int) -> [String: Any] {
        ["mapValue": ["fields": [
            "name": ["stringValue": myName],
            "xp": ["integerValue": String(xp)],
            "updatedAt": ["timestampValue": ISO8601DateFormatter().string(from: Date())],
        ]]]
    }

    /// Six letters without look-alikes (no 0/O, 1/I/L).
    private static func newCode() -> String {
        let letters = Array("ABCDEFGHJKMNPQRSTUVWXYZ23456789")
        return String((0..<6).map { _ in letters.randomElement()! })
    }

    // MARK: Firestore REST

    private func docURL(_ code: String, query: [URLQueryItem] = []) -> URL {
        var c = URLComponents(string: "https://firestore.googleapis.com/v1/projects/\(FirebaseConfig.projectID)/databases/(default)/documents/nests/\(code)")!
        if !query.isEmpty { c.queryItems = query }
        return c.url!
    }

    private func fetch(_ code: String) async throws -> [String: Any]? {
        var req = URLRequest(url: docURL(code))
        FirebaseConfig.sign(&req)
        req.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 404 { return nil }
        guard status == 200 else { throw NestError.server("O servidor do ninho respondeu \(status).") }
        return try JSONSerialization.jsonObject(with: data) as? [String: Any]
    }

    private func patch(_ code: String, fields: [String: Any], mask: [String]?, mustExist: Bool? = true) async throws {
        var query = (mask ?? []).map { URLQueryItem(name: "updateMask.fieldPaths", value: $0) }
        if let mustExist { query.append(URLQueryItem(name: "currentDocument.exists", value: mustExist ? "true" : "false")) }
        var req = URLRequest(url: docURL(code, query: query))
        req.httpMethod = "PATCH"
        FirebaseConfig.sign(&req)
        req.setValue("Bearer \(try await token())", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["fields": fields])
        let (_, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        if status == 404 || (status == 400 && mustExist == true) { throw NestError.notFound }
        guard status == 200 else { throw NestError.server("O servidor do ninho respondeu \(status).") }
    }

    // MARK: the person's account

    /// The nest is reached with the person's Aravia account (e-mail confirmed).
    @discardableResult
    private func signIn() async throws -> String {
        _ = try await token()
        guard let uid else { throw NestError.server("Entre na sua conta do Aravia de novo.") }
        return uid
    }

    private func token() async throws -> String {
        try await Account.shared.token()
    }
}

/// Name, invitation and members of the nest.
struct NestSheet: View {
    let myXP: Int
    @Environment(\.dismiss) private var dismiss
    @StateObject private var nest = AraraNest.shared
    @State private var typedCode = ""
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(text: $newName) { Text(verbatim: "Nome da arara") }
                        .onSubmit { Task { await nest.rename(newName) } }
                    Button { Task { await nest.rename(newName) } } label: { Text(verbatim: "Salvar nome") }
                        .disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty || newName == nest.name)
                } header: { Text(verbatim: "Nome") } footer: {
                    Text(verbatim: nest.inNest ? "O nome muda para todos do ninho." : "Dê um nome para a sua arara.")
                }

                Section {
                    TextField(text: $nest.myName) { Text(verbatim: "Como você aparece no ninho") }
                } header: { Text(verbatim: "Seu nome no ninho") }

                if let code = nest.code {
                    Section {
                        HStack {
                            Text(verbatim: code).font(.system(size: 34, weight: .heavy, design: .monospaced)).kerning(4)
                            Spacer()
                            ShareLink(item: Self.invite(code: code, name: nest.name)) {
                                Label { Text(verbatim: "Convidar") } icon: { Image(systemName: "person.badge.plus") }
                            }
                            .buttonStyle(.borderedProminent)
                        }
                    } header: { Text(verbatim: "Código do ninho") } footer: {
                        Text(verbatim: "Quem entrar com esse código cuida da mesma arara: os pontos de todos se somam.")
                    }
                    Section {
                        ForEach(nest.members) { m in
                            HStack {
                                Image(systemName: "person.crop.circle.fill").foregroundStyle(Color.action)
                                Text(verbatim: m.name)
                                Spacer()
                                Text(verbatim: "\(m.xp) pts").monospacedDigit().foregroundStyle(Color.textSecondary)
                            }
                        }
                    } header: { Text(verbatim: "Quem cuida dela") }
                    Section {
                        Button(role: .destructive) { Task { await nest.leave() } } label: { Text(verbatim: "Sair do ninho") }
                    } footer: { Text(verbatim: "Ao sair, a arara volta a contar só os seus pontos.") }
                } else {
                    Section {
                        Button { Task { await nest.create(myXP: myXP) } } label: {
                            Label { Text(verbatim: "Criar ninho e convidar alguém") } icon: { Image(systemName: "person.2.fill") }
                        }
                    } footer: { Text(verbatim: "Você recebe um código para mandar. Os pontos de quem entrar somam com os seus.") }
                    Section {
                        TextField(text: $typedCode) { Text(verbatim: "Código de 6 letras") }
                            .textInputAutocapitalization(.characters)
                            .autocorrectionDisabled()
                        Button { Task { await nest.join(typedCode, myXP: myXP) } } label: { Text(verbatim: "Entrar no ninho") }
                            .disabled(typedCode.count < 6)
                    } header: { Text(verbatim: "Recebeu um convite?") }
                }

                if nest.busy { Section { HStack { ProgressView(); Text(verbatim: "Falando com o ninho…") } } }
                if let error = nest.error {
                    Section { Text(verbatim: error).foregroundStyle(Color.low) }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Color.canvas.ignoresSafeArea())
            .navigationTitle(Text(verbatim: nest.name))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button { dismiss() } label: { Text(verbatim: "OK") } }
            }
            .onAppear {
                newName = nest.name
                if let invite = nest.pendingInvite { typedCode = invite; nest.pendingInvite = nil }
            }
            .task { await nest.sync(myXP: myXP) }
        }
        .tint(Color.action)
    }

    static func invite(code: String, name: String) -> String {
        "Vem cuidar da arara \(name) comigo no Aravia! 🦜\n\nNo app: Treino › toque na arara › Recebeu um convite? e digite o código \(code)\n\nou abra: aravia://ninho/\(code)"
    }
}
