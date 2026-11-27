import AuthenticationServices
import Foundation
import SwiftData
import UIKit

/// Strava app credentials, put into Info.plist at build time from the repository's secrets
/// (STRAVA_CLIENT_ID, STRAVA_CLIENT_SECRET; see tools/build-ipa.sh). An app with no server of its
/// own has to carry its client secret: it can be read from the app, which is why the repository
/// and its builds stay private. Empty in a build without the secrets, and Strava is then hidden.
enum StravaConfig {
    static var clientID: String { value("StravaClientID") }
    static var clientSecret: String { value("StravaClientSecret") }
    static var isConfigured: Bool { !clientID.isEmpty && !clientSecret.isEmpty }

    /// The app's own URL scheme. The Strava app's "Authorization Callback Domain" is localhost.
    static let scheme = "aravia"
    static let redirectURI = "aravia://localhost/strava"

    private static func value(_ key: String) -> String {
        let v = (Bundle.main.object(forInfoDictionaryKey: key) as? String ?? "").trimmingCharacters(in: .whitespaces)
        return v.hasPrefix("$(") ? "" : v
    }
}

/// Strava sign-in and upload, with no SDK: OAuth 2 in ASWebAuthenticationSession, then the
/// Strava v3 REST API. A workout with a GPS track goes as a GPX upload (with its map); one
/// without goes as a manual activity with its time, distance and heart rate in the description.
@MainActor
final class Strava: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = Strava()

    enum StravaError: LocalizedError {
        case notConfigured, cancelled, noWriteScope, badResponse(String)
        var errorDescription: String? {
            switch self {
            case .notConfigured: "Este build não tem as chaves do Strava."
            case .cancelled: "Login cancelado."
            case .noWriteScope: "Deixe marcada a permissão de enviar atividades e tente de novo."
            case .badResponse(let m): m
            }
        }
    }

    /// One workout sent, so it is never sent twice.
    struct Sent: Codable, Equatable {
        let start: Date
        let activityID: Int
    }

    private static let refreshAccount = "strava-refresh-token"
    private static let athleteKey = "aravia.stravaAthlete"
    private static let connectedAtKey = "aravia.stravaConnectedAt"
    private static let sentKey = "aravia.stravaSent"
    static let autoKey = "aravia.stravaAuto"

    @Published private(set) var athlete: String?
    @Published private(set) var busy = false
    @Published private(set) var status: String?
    @Published private(set) var sent: [Sent] = []

    var isConnected: Bool { athlete != nil && Vault.get(Self.refreshAccount) != nil }
    var connectedAt: Date { UserDefaults.standard.object(forKey: Self.connectedAtKey) as? Date ?? .distantFuture }
    var sendsAutomatically: Bool { UserDefaults.standard.object(forKey: Self.autoKey) as? Bool ?? true }

    private var accessToken: String?
    private var accessExpiry = Date.distantPast
    private var session: ASWebAuthenticationSession?

    override private init() {
        super.init()
        if Vault.get(Self.refreshAccount) != nil { athlete = UserDefaults.standard.string(forKey: Self.athleteKey) }
        if let data = UserDefaults.standard.data(forKey: Self.sentKey),
           let list = try? JSONDecoder().decode([Sent].self, from: data) { sent = list }
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows).first { $0.isKeyWindow } ?? ASPresentationAnchor()
        }
    }

    // MARK: Sign-in

    func signIn() async throws {
        guard StravaConfig.isConfigured else { throw StravaError.notConfigured }
        busy = true
        defer { busy = false }

        let state = UUID().uuidString
        var auth = URLComponents(string: "https://www.strava.com/oauth/mobile/authorize")!
        auth.queryItems = [
            .init(name: "client_id", value: StravaConfig.clientID),
            .init(name: "redirect_uri", value: StravaConfig.redirectURI),
            .init(name: "response_type", value: "code"),
            .init(name: "approval_prompt", value: "auto"),
            .init(name: "scope", value: "read,activity:write"),
            .init(name: "state", value: state),
        ]

        let callback: URL = try await withCheckedThrowingContinuation { cont in
            let s = ASWebAuthenticationSession(url: auth.url!, callbackURLScheme: StravaConfig.scheme) { url, error in
                if let url { cont.resume(returning: url) } else {
                    let cancelled = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
                    cont.resume(throwing: cancelled ? StravaError.cancelled : (error ?? StravaError.cancelled))
                }
            }
            s.presentationContextProvider = self
            session = s
            s.start()
        }
        session = nil

        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func item(_ name: String) -> String? { items.first { $0.name == name }?.value }
        if item("error") == "access_denied" { throw StravaError.cancelled }
        guard item("state") == state, let code = item("code") else {
            throw StravaError.badResponse("O Strava não devolveu a autorização.")
        }
        guard (item("scope") ?? "").split(separator: ",").contains("activity:write") else { throw StravaError.noWriteScope }

        let tokens = try await tokenRequest([
            "client_id": StravaConfig.clientID,
            "client_secret": StravaConfig.clientSecret,
            "code": code,
            "grant_type": "authorization_code",
        ])
        let person = tokens["athlete"] as? [String: Any]
        let name = [person?["firstname"] as? String, person?["lastname"] as? String]
            .compactMap { $0?.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }.joined(separator: " ")
        UserDefaults.standard.set(name.isEmpty ? "Conta Strava" : name, forKey: Self.athleteKey)
        // Only workouts from now on go by themselves; older ones can be sent one by one.
        if UserDefaults.standard.object(forKey: Self.connectedAtKey) == nil {
            UserDefaults.standard.set(Date(), forKey: Self.connectedAtKey)
        }
        athlete = name.isEmpty ? "Conta Strava" : name
        status = nil
    }

    func signOut() {
        if let token = accessToken {
            var req = URLRequest(url: URL(string: "https://www.strava.com/oauth/deauthorize")!)
            req.httpMethod = "POST"
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            Task { _ = try? await URLSession.shared.data(for: req) }
        }
        Vault.remove(Self.refreshAccount)
        UserDefaults.standard.removeObject(forKey: Self.athleteKey)
        UserDefaults.standard.removeObject(forKey: Self.connectedAtKey)
        accessToken = nil
        athlete = nil
        status = nil
    }

    func setAutomatic(_ on: Bool) {
        UserDefaults.standard.set(on, forKey: Self.autoKey)
        objectWillChange.send()
    }

    // MARK: What to send

    /// A workout as Strava sees it: a GPS track, a summary from the band, or both.
    struct Item {
        let start: Date
        let end: Date
        let sport: String
        let name: String
        let workout: Workout?
        let track: GpsTrack?

        var isOutdoor: Bool { StravaSport.isOutdoor(sport) }
    }

    /// The activity a workout went to, if it was sent.
    func activityID(near start: Date) -> Int? {
        sent.first { abs($0.start.timeIntervalSince(start)) < 120 }?.activityID
    }

    /// Workouts and GPS tracks that started at or after `since`, a track and the summary of the
    /// same workout joined into one item.
    static func items(since: Date, context: ModelContext) -> [Item] {
        let workouts = (try? context.fetch(FetchDescriptor<Workout>(predicate: #Predicate { $0.start >= since }))) ?? []
        let blobs = (try? context.fetch(FetchDescriptor<ActivityBlob>(
            predicate: #Predicate { $0.type == 1 && $0.detailType == 2 && $0.ts >= since }))) ?? []
        var used = Set<PersistentIdentifier>()
        var out: [Item] = []
        for blob in blobs {
            guard let track = GpsTrack.parse(blob.data, version: blob.version), track.points.count > 1 else { continue }
            let start = track.start ?? blob.ts
            let w = workouts.first { $0.bandID == blob.bandID && abs($0.start.timeIntervalSince(start)) < 300 }
            if let w { used.insert(w.persistentModelID) }
            let sport = w?.sport ?? ActivityFileID.Sport(rawValue: UInt8(clamping: blob.subtype))?.name ?? "workout"
            out.append(Item(start: start, end: max(track.end ?? start, w?.end ?? start), sport: sport,
                            name: w?.sportName ?? RouteNames.sport(blob.subtype), workout: w, track: track))
        }
        for w in workouts where !used.contains(w.persistentModelID) {
            out.append(Item(start: w.start, end: w.end, sport: w.sport, name: w.sportName, workout: w, track: nil))
        }
        return out.sorted { $0.start < $1.start }
    }

    // MARK: Sending

    /// After a sync: sends the workouts done since Strava was connected that are not there yet.
    /// An outdoor workout without its GPS track waits a day for the track, which can come in a
    /// later sync.
    func sendNew(context: ModelContext) async {
        guard isConnected, sendsAutomatically, !busy else { return }
        let waitUntil = Date().addingTimeInterval(-24 * 3600)
        let due = Self.items(since: connectedAt, context: context).filter { item in
            activityID(near: item.start) == nil && !(item.track == nil && item.isOutdoor && item.end > waitUntil)
        }
        let logged = TrainingStore.shared.sessions.filter {
            $0.end != nil && $0.start >= connectedAt && activityID(near: $0.start) == nil
        }
        guard !due.isEmpty || !logged.isEmpty else { return }
        busy = true
        defer { busy = false }
        var done = 0
        do {
            for item in due {
                _ = try await send(item)
                done += 1
            }
            for session in logged {
                _ = try await send(session)
                done += 1
            }
        } catch {
            status = "Não deu para enviar: \(error.localizedDescription)"
            return
        }
        status = done == 1 ? "1 treino enviado ao Strava." : "\(done) treinos enviados ao Strava."
    }

    /// Sends one workout now, from its screen.
    func sendNow(_ item: Item) async throws -> Int {
        busy = true
        defer { busy = false }
        return try await send(item)
    }

    private func send(_ item: Item) async throws -> Int {
        if let id = activityID(near: item.start) { return id }
        let token = try await validToken()
        let sport = StravaSport.of(item.sport)
        let description = Self.description(item)
        let id: Int
        if let track = item.track {
            id = try await upload(track, item: item, description: description, token: token)
            try? await update(id, sport: sport, token: token)
        } else {
            id = try await create(item, sport: sport, description: description, token: token)
        }
        remember(Sent(start: item.start, activityID: id))
        return id
    }

    /// A workout logged in Aravia, now, from its screen.
    func sendNow(_ session: TrainingSession) async throws -> Int {
        busy = true
        defer { busy = false }
        return try await send(session)
    }

    /// A workout logged in Aravia (treadmill, gym, other) goes as a manual activity, with each
    /// exercise's sets and the heart rate in the description, as on Android.
    private func send(_ s: TrainingSession) async throws -> Int {
        if let id = activityID(near: s.start) { return id }
        let token = try await validToken()
        let type: String = switch s.kind {
        case .treadmill: "Run"
        case .gym: "WeightTraining"
        case .other: "Workout"
        }
        var fields = [
            "name": s.kind.name,
            "sport_type": type,
            "start_date_local": Self.localTime(s.start, offsetMinutes: nil),
            "elapsed_time": String(max(1, Int(s.duration))),
            "description": Self.description(s),
            "trainer": "1",
        ]
        if s.kind == .treadmill {
            let d = s.distanceMeters(heightCm: Profile.load().heightCm)
            if d > 0 { fields["distance"] = String(Int(d.rounded())) }
        }
        var req = URLRequest(url: URL(string: "https://www.strava.com/api/v3/activities")!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = Self.form(fields)
        let json = try await call(req, ok: 201)
        guard let id = (json["id"] as? NSNumber)?.intValue else { throw StravaError.badResponse("O Strava não criou a atividade.") }
        remember(Sent(start: s.start, activityID: id))
        return id
    }

    static func description(_ s: TrainingSession) -> String {
        func kg(_ v: Double) -> String { v.rounded() == v ? String(Int(v)) : String(format: "%.1f", v).replacingOccurrences(of: ".", with: ",") }
        var lines: [String] = []
        if let avg = s.avgBPM { lines.append("FC média \(avg) bpm" + (s.maxBPM.map { " (máx \($0))" } ?? "")) }
        for e in s.entries where !e.sets.isEmpty {
            lines.append("\(e.exercise): " + e.sets.map { "\($0.reps)×\(kg($0.kg)) kg" }.joined(separator: ", "))
        }
        if s.volumeKg > 0 { lines.append("\(s.setCount) séries · \(kg(s.volumeKg)) kg no total") }
        if s.steps > 0 { lines.append("\(s.steps) passos") }
        lines.append("Enviado pelo Aravia")
        return lines.joined(separator: "\n")
    }

    private func remember(_ s: Sent) {
        sent.append(s)
        if sent.count > 2000 { sent.removeFirst(sent.count - 2000) }
        if let data = try? JSONEncoder().encode(sent) { UserDefaults.standard.set(data, forKey: Self.sentKey) }
    }

    static func description(_ item: Item) -> String {
        var parts: [String] = []
        if let w = item.workout {
            if let hr = w.hrAvg { parts.append("FC média \(hr) bpm" + (w.hrMax.map { " (máx \($0))" } ?? "")) }
            if let kcal = w.calories { parts.append("\(kcal) kcal") }
            if let steps = w.steps, steps > 0 { parts.append("\(steps) passos") }
        }
        parts.append("Enviado pelo Aravia")
        return parts.joined(separator: " · ")
    }

    /// GPX upload; Strava makes the activity in the background, so the upload is asked about
    /// until it has one. A file Strava already has counts as sent.
    private func upload(_ track: GpsTrack, item: Item, description: String, token: String) async throws -> Int {
        let boundary = "aravia-\(UUID().uuidString)"
        var body = Data()
        func field(_ name: String, _ value: String) {
            body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".utf8))
        }
        let key = "aravia-\(Int(item.start.timeIntervalSince1970))"
        field("data_type", "gpx")
        field("name", item.name)
        field("description", description)
        field("external_id", key)
        body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"file\"; filename=\"\(key).gpx\"\r\nContent-Type: application/gpx+xml\r\n\r\n".utf8))
        let safeTrack = RoutePrivacy.shared.safeTrack(for: track)
        body.append(Data(safeTrack.gpx(name: item.name).utf8))
        body.append(Data("\r\n--\(boundary)--\r\n".utf8))

        var req = URLRequest(url: URL(string: "https://www.strava.com/api/v3/uploads")!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        req.httpBody = body
        var json = try await call(req, ok: 201)

        for _ in 0..<20 {
            if let id = (json["activity_id"] as? NSNumber)?.intValue { return id }
            if let error = json["error"] as? String, !error.isEmpty {
                if let id = Self.duplicateID(error) { return id }
                throw StravaError.badResponse("O Strava recusou o arquivo: \(error)")
            }
            guard let upload = (json["id"] as? NSNumber)?.intValue else { break }
            try await Task.sleep(for: .seconds(2))
            var check = URLRequest(url: URL(string: "https://www.strava.com/api/v3/uploads/\(upload)")!)
            check.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
            json = try await call(check, ok: 200)
        }
        throw StravaError.badResponse("O Strava ainda está processando. Tente de novo daqui a pouco.")
    }

    /// "… duplicate of <a href='/activities/123'>…" → 123.
    static func duplicateID(_ error: String) -> Int? {
        guard error.contains("duplicate"), let r = error.range(of: "activities/") else { return nil }
        return Int(error[r.upperBound...].prefix { $0.isNumber })
    }

    /// A GPX carries no sport, so it is set after the upload.
    private func update(_ id: Int, sport: StravaSport, token: String) async throws {
        var req = URLRequest(url: URL(string: "https://www.strava.com/api/v3/activities/\(id)")!)
        req.httpMethod = "PUT"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try JSONSerialization.data(withJSONObject: ["sport_type": sport.type, "trainer": sport.trainer])
        _ = try await call(req, ok: 200)
    }

    private func create(_ item: Item, sport: StravaSport, description: String, token: String) async throws -> Int {
        let w = item.workout
        var fields = [
            "name": item.name,
            "sport_type": sport.type,
            "start_date_local": Self.localTime(item.start, offsetMinutes: w?.tzOffsetMinutes),
            "elapsed_time": String(max(1, Int(item.end.timeIntervalSince(item.start)))),
            "description": description,
            "trainer": sport.trainer ? "1" : "0",
        ]
        if let d = w?.distanceMeters, d > 0 { fields["distance"] = String(d) }
        var req = URLRequest(url: URL(string: "https://www.strava.com/api/v3/activities")!)
        req.httpMethod = "POST"
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = Self.form(fields)
        let json = try await call(req, ok: 201)
        guard let id = (json["id"] as? NSNumber)?.intValue else { throw StravaError.badResponse("O Strava não criou a atividade.") }
        return id
    }

    /// Wall-clock time where the workout was done, as Strava's start_date_local wants it.
    static func localTime(_ date: Date, offsetMinutes: Int?) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd'T'HH:mm:ss"
        f.timeZone = offsetMinutes.flatMap { TimeZone(secondsFromGMT: $0 * 60) } ?? .current
        return f.string(from: date) + "Z"
    }

    private func call(_ req: URLRequest, ok: Int) async throws -> [String: Any] {
        let (data, response) = try await URLSession.shared.data(for: req)
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        if code == 401 { accessToken = nil }
        guard code == ok || (200...201).contains(code) else {
            if code == 429 { throw StravaError.badResponse("Muitos envios seguidos. O Strava libera de novo em 15 minutos.") }
            let message = (json["message"] as? String) ?? "erro \(code)"
            throw StravaError.badResponse("O Strava recusou (\(message)).")
        }
        return json
    }

    // MARK: Tokens

    private func validToken() async throws -> String {
        if let accessToken, accessExpiry > .now.addingTimeInterval(60) { return accessToken }
        guard StravaConfig.isConfigured, let refresh = Vault.get(Self.refreshAccount) else {
            throw StravaError.badResponse("Conecte o Strava de novo.")
        }
        do {
            _ = try await tokenRequest([
                "client_id": StravaConfig.clientID,
                "client_secret": StravaConfig.clientSecret,
                "refresh_token": refresh,
                "grant_type": "refresh_token",
            ])
        } catch let error as URLError {
            throw error  // offline: keep the connection and try again later
        } catch {
            signOut()
            throw StravaError.badResponse("O acesso ao Strava venceu. Conecte de novo.")
        }
        guard let token = accessToken else { throw StravaError.badResponse("Sem acesso ao Strava.") }
        return token
    }

    /// Strava hands a new refresh token with every access token; the newest is kept.
    private func tokenRequest(_ fields: [String: String]) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: "https://www.strava.com/oauth/token")!)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = Self.form(fields)
        let (data, response) = try await URLSession.shared.data(for: req)
        let json = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        guard (response as? HTTPURLResponse)?.statusCode == 200,
              let access = json["access_token"] as? String, let refresh = json["refresh_token"] as? String else {
            throw StravaError.badResponse((json["message"] as? String) ?? "O Strava recusou o login.")
        }
        Vault.set(refresh, for: Self.refreshAccount)
        accessToken = access
        let expires = (json["expires_at"] as? NSNumber)?.doubleValue
        accessExpiry = expires.map { Date(timeIntervalSince1970: $0) } ?? .now.addingTimeInterval(3600)
        return json
    }

    private static func form(_ fields: [String: String]) -> Data {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return Data(fields.map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")" }
            .joined(separator: "&").utf8)
    }
}

/// The band's sport names (Workout.sport and ActivityFileID.Sport.name) as Strava sport types.
struct StravaSport: Equatable {
    let type: String
    let trainer: Bool

    static func of(_ sport: String) -> StravaSport {
        switch sport {
        case "outdoor_running": StravaSport(type: "Run", trainer: false)
        case "trail_running": StravaSport(type: "TrailRun", trainer: false)
        case "treadmill": StravaSport(type: "Run", trainer: true)
        case "walking", "outdoor_walking", "outdoor_walking_v1", "outdoor_walking_v2": StravaSport(type: "Walk", trainer: false)
        case "indoor_walking": StravaSport(type: "Walk", trainer: true)
        case "hiking", "trekking": StravaSport(type: "Hike", trainer: false)
        case "outdoor_cycling", "outdoor_cycling_v2": StravaSport(type: "Ride", trainer: false)
        case "indoor_cycling": StravaSport(type: "Ride", trainer: true)
        case "pool_swimming": StravaSport(type: "Swim", trainer: false)
        case "elliptical": StravaSport(type: "Elliptical", trainer: true)
        case "rowing": StravaSport(type: "Rowing", trainer: true)
        case "hiit": StravaSport(type: "HighIntensityIntervalTraining", trainer: true)
        default: StravaSport(type: "Workout", trainer: true)
        }
    }

    /// Sports the band records with GPS.
    static func isOutdoor(_ sport: String) -> Bool {
        ["outdoor_running", "trail_running", "outdoor_walking", "outdoor_walking_v1", "outdoor_walking_v2",
         "hiking", "trekking", "outdoor_cycling", "outdoor_cycling_v2"].contains(sport)
    }
}
