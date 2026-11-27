import AuthenticationServices
import CryptoKit
import SwiftUI

/// Google sign-in and upload for the Story videos, with no Google SDK: OAuth 2 for installed apps
/// with PKCE in ASWebAuthenticationSession, then the Drive v3 REST API. The only scope asked for
/// is drive.file, so the app sees nothing in the Drive but the files it made itself.
enum GoogleDriveConfig {
    /// iOS OAuth client ID from Google Cloud Console (APIs & Services › Credentials), bundle ID
    /// app.aravia.watch. It is not a secret: installed apps carry their client ID in the open.
    static let clientID = "872966351821-nlktcnphc3prilaikv9ic1qs62vmd0gr.apps.googleusercontent.com"

    static var isConfigured: Bool { clientID.hasSuffix(".apps.googleusercontent.com") }

    /// Google's redirect for iOS clients: the client ID reversed, used as a URL scheme.
    static var scheme: String {
        "com.googleusercontent.apps." + clientID.replacingOccurrences(of: ".apps.googleusercontent.com", with: "")
    }

    static var redirectURI: String { scheme + ":/oauth2redirect" }
}

@MainActor
final class GoogleDrive: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {
    static let shared = GoogleDrive()

    enum DriveError: LocalizedError {
        case notConfigured, cancelled, badResponse(String)
        var errorDescription: String? {
            switch self {
            case .notConfigured: "O ID do Google ainda não foi configurado no app."
            case .cancelled: "Login cancelado."
            case .badResponse(let m): m
            }
        }
    }

    private static let refreshAccount = "google-drive-refresh-token"
    private static let emailKey = "aravia.googleEmail"
    private static let folderName = "Aravia"

    @Published private(set) var email: String?
    @Published private(set) var busy = false
    var isConnected: Bool { email != nil && Vault.get(Self.refreshAccount) != nil }

    private var accessToken: String?
    private var accessExpiry = Date.distantPast
    private var session: ASWebAuthenticationSession?

    override private init() {
        super.init()
        if Vault.get(Self.refreshAccount) != nil { email = UserDefaults.standard.string(forKey: Self.emailKey) }
    }

    nonisolated func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        MainActor.assumeIsolated {
            UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
                .flatMap(\.windows).first { $0.isKeyWindow } ?? ASPresentationAnchor()
        }
    }

    // MARK: Sign-in

    func signIn() async throws {
        guard GoogleDriveConfig.isConfigured else { throw DriveError.notConfigured }
        busy = true
        defer { busy = false }

        let verifier = Self.randomURLSafe(48)
        let challenge = Self.base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
        let state = Self.randomURLSafe(16)
        var auth = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        auth.queryItems = [
            .init(name: "client_id", value: GoogleDriveConfig.clientID),
            .init(name: "redirect_uri", value: GoogleDriveConfig.redirectURI),
            .init(name: "response_type", value: "code"),
            .init(name: "scope", value: "openid email https://www.googleapis.com/auth/drive.file"),
            .init(name: "code_challenge", value: challenge),
            .init(name: "code_challenge_method", value: "S256"),
            .init(name: "state", value: state),
            .init(name: "prompt", value: "select_account consent"),
        ]

        let callback: URL = try await withCheckedThrowingContinuation { cont in
            let s = ASWebAuthenticationSession(url: auth.url!, callbackURLScheme: GoogleDriveConfig.scheme) { url, error in
                if let url { cont.resume(returning: url) } else {
                    let cancelled = (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin
                    cont.resume(throwing: cancelled ? DriveError.cancelled : (error ?? DriveError.cancelled))
                }
            }
            s.presentationContextProvider = self
            session = s
            s.start()
        }
        session = nil

        let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems ?? []
        guard items.first(where: { $0.name == "state" })?.value == state,
              let code = items.first(where: { $0.name == "code" })?.value else {
            throw DriveError.badResponse("O Google não devolveu a autorização.")
        }
        let tokens = try await tokenRequest([
            "client_id": GoogleDriveConfig.clientID,
            "code": code,
            "code_verifier": verifier,
            "redirect_uri": GoogleDriveConfig.redirectURI,
            "grant_type": "authorization_code",
        ])
        guard let refresh = tokens["refresh_token"] as? String else {
            throw DriveError.badResponse("O Google não devolveu um acesso permanente. Tente de novo.")
        }
        Vault.set(refresh, for: Self.refreshAccount)
        let mail = (tokens["id_token"] as? String).flatMap(Self.email(fromIDToken:)) ?? "Conta Google"
        UserDefaults.standard.set(mail, forKey: Self.emailKey)
        email = mail
    }

    func signOut() {
        if let token = Vault.get(Self.refreshAccount) {
            var req = URLRequest(url: URL(string: "https://oauth2.googleapis.com/revoke")!)
            req.httpMethod = "POST"
            req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            req.httpBody = Self.form(["token": token])
            Task { _ = try? await URLSession.shared.data(for: req) }
        }
        Vault.remove(Self.refreshAccount)
        UserDefaults.standard.removeObject(forKey: Self.emailKey)
        accessToken = nil
        email = nil
    }

    // MARK: Upload

    /// Uploads the file into the "Aravia" folder of the person's Drive and returns its link.
    func upload(_ file: URL, name: String, progress: @escaping @MainActor (Double) -> Void) async throws -> URL? {
        let token = try await validToken()
        let folder = try await folderID(token: token)

        var start = URLRequest(url: URL(string: "https://www.googleapis.com/upload/drive/v3/files?uploadType=resumable&fields=id,webViewLink")!)
        start.httpMethod = "POST"
        start.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        start.setValue("application/json; charset=UTF-8", forHTTPHeaderField: "Content-Type")
        start.setValue("video/mp4", forHTTPHeaderField: "X-Upload-Content-Type")
        start.httpBody = try JSONSerialization.data(withJSONObject: ["name": name, "parents": [folder], "mimeType": "video/mp4"])
        let (_, startResponse) = try await URLSession.shared.data(for: start)
        guard let http = startResponse as? HTTPURLResponse, http.statusCode == 200,
              let location = http.value(forHTTPHeaderField: "Location"), let sessionURL = URL(string: location) else {
            throw DriveError.badResponse("O Google Drive recusou o envio (\((startResponse as? HTTPURLResponse)?.statusCode ?? 0)).")
        }

        var put = URLRequest(url: sessionURL)
        put.httpMethod = "PUT"
        put.setValue("video/mp4", forHTTPHeaderField: "Content-Type")
        let watcher = UploadProgress(progress)
        let (data, response) = try await URLSession.shared.upload(for: put, fromFile: file, delegate: watcher)
        guard (response as? HTTPURLResponse).map({ (200...201).contains($0.statusCode) }) == true else {
            throw DriveError.badResponse("O envio para o Google Drive falhou (\((response as? HTTPURLResponse)?.statusCode ?? 0)).")
        }
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        return (json?["webViewLink"] as? String).flatMap(URL.init(string:))
    }

    /// The app's own "Aravia" folder; with drive.file the app only sees folders it created.
    private func folderID(token: String) async throws -> String {
        let q = "name = '\(Self.folderName)' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        var find = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
        find.queryItems = [.init(name: "q", value: q), .init(name: "fields", value: "files(id)"), .init(name: "spaces", value: "drive")]
        var req = URLRequest(url: find.url!)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, _) = try await URLSession.shared.data(for: req)
        if let files = (try JSONSerialization.jsonObject(with: data) as? [String: Any])?["files"] as? [[String: Any]],
           let id = files.first?["id"] as? String {
            return id
        }
        var create = URLRequest(url: URL(string: "https://www.googleapis.com/drive/v3/files?fields=id")!)
        create.httpMethod = "POST"
        create.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        create.setValue("application/json", forHTTPHeaderField: "Content-Type")
        create.httpBody = try JSONSerialization.data(withJSONObject: ["name": Self.folderName, "mimeType": "application/vnd.google-apps.folder"])
        let (made, _) = try await URLSession.shared.data(for: create)
        guard let id = (try JSONSerialization.jsonObject(with: made) as? [String: Any])?["id"] as? String else {
            throw DriveError.badResponse("Não deu para criar a pasta Aravia no Drive.")
        }
        return id
    }

    // MARK: Tokens

    private func validToken() async throws -> String {
        if let accessToken, accessExpiry > .now.addingTimeInterval(60) { return accessToken }
        guard let refresh = Vault.get(Self.refreshAccount) else {
            throw DriveError.badResponse("Conecte a conta Google de novo.")
        }
        let tokens: [String: Any]
        do {
            tokens = try await tokenRequest([
                "client_id": GoogleDriveConfig.clientID,
                "refresh_token": refresh,
                "grant_type": "refresh_token",
            ])
        } catch {
            signOut()
            throw DriveError.badResponse("O acesso ao Google Drive venceu. Conecte a conta de novo.")
        }
        guard let token = tokens["access_token"] as? String else { throw DriveError.badResponse("Sem acesso ao Google Drive.") }
        accessToken = token
        accessExpiry = .now.addingTimeInterval((tokens["expires_in"] as? Double) ?? 3000)
        return token
    }

    private func tokenRequest(_ fields: [String: String]) async throws -> [String: Any] {
        var req = URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!)
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        req.httpBody = Self.form(fields)
        let (data, response) = try await URLSession.shared.data(for: req)
        let json = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]
        guard (response as? HTTPURLResponse)?.statusCode == 200 else {
            throw DriveError.badResponse((json["error_description"] as? String) ?? "O Google recusou o login.")
        }
        return json
    }

    // MARK: Helpers

    private static func form(_ fields: [String: String]) -> Data {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return Data(fields.map { "\($0.key)=\($0.value.addingPercentEncoding(withAllowedCharacters: allowed) ?? "")" }
            .joined(separator: "&").utf8)
    }

    private static func randomURLSafe(_ bytes: Int) -> String {
        base64URL(Data((0..<bytes).map { _ in UInt8.random(in: 0...255) }))
    }

    private static func base64URL(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

    private static func email(fromIDToken token: String) -> String? {
        let parts = token.split(separator: ".")
        guard parts.count > 1 else { return nil }
        var b64 = parts[1].replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64 += "=" }
        guard let data = Data(base64Encoded: b64),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        return json["email"] as? String
    }
}

private final class UploadProgress: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    let report: @MainActor (Double) -> Void
    init(_ report: @escaping @MainActor (Double) -> Void) { self.report = report }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        guard totalBytesExpectedToSend > 0 else { return }
        let p = Double(totalBytesSent) / Double(totalBytesExpectedToSend)
        Task { @MainActor in report(p) }
    }
}

/// The Google Drive login screen.
struct GoogleDriveView: View {
    @StateObject private var drive = GoogleDrive.shared
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image(systemName: drive.isConnected ? "checkmark.icloud.fill" : "icloud.and.arrow.up")
                    .font(.system(size: 60, weight: .semibold))
                    .foregroundStyle(drive.isConnected ? Color.good : Color.action)
                    .padding(.top, 32)
                Text(verbatim: drive.isConnected ? "Google Drive conectado" : "Conecte o Google Drive")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Color.textPrimary)
                Text(verbatim: drive.isConnected
                     ? "Os vídeos de Story vão direto para a pasta Aravia do seu Drive. No celular fica só o tempo de gerar e compartilhar."
                     : "Faça login uma vez e os vídeos de Story vão direto para o seu Drive, sem ocupar o celular. O Aravia só enxerga os arquivos que ele mesmo criar.")
                    .font(.subheadline)
                    .foregroundStyle(Color.textSecondary)
                    .multilineTextAlignment(.center)

                if let email = drive.email, drive.isConnected {
                    Label { Text(verbatim: email) } icon: { Image(systemName: "person.crop.circle") }
                        .font(.headline)
                        .foregroundStyle(Color.textPrimary)
                        .padding(14)
                        .frame(maxWidth: .infinity)
                        .background(Color.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    Button(role: .destructive) { drive.signOut() } label: {
                        Text(verbatim: "Desconectar").frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                } else {
                    Button {
                        error = nil
                        Task {
                            do { try await drive.signIn() } catch GoogleDrive.DriveError.cancelled {
                            } catch { self.error = error.localizedDescription }
                        }
                    } label: {
                        HStack {
                            if drive.busy { ProgressView().tint(Color.onAction) }
                            Text(verbatim: "Entrar com o Google")
                        }
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 50)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.action)
                    .disabled(drive.busy)
                }
                if let error {
                    Text(verbatim: error)
                        .font(.footnote)
                        .foregroundStyle(Color.low)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.horizontal, 24)
        }
        .fbScreen()
        .navigationTitle(Text(verbatim: "Google Drive"))
        .navigationBarTitleDisplayMode(.inline)
    }
}
