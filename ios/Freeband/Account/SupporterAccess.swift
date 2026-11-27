import Foundation

/// Supporter Early Access entitlements & experimental Lab preferences.
///
/// Verified on the server (`POST https://aravia.app/api/resgatar`) using the user's real Firebase
/// Auth ID token, then persisted into `users/{uid}.supporter` in Firestore so the unlocked Early
/// Access section follows the person's account across iPhones, Androids, and reinstalls.
@MainActor
final class SupporterAccess: ObservableObject {
    static let shared = SupporterAccess()
    static let endpoint = URL(string: "https://aravia.app/api/resgatar")!

    struct Entitlement: Codable, Equatable, Sendable {
        var tier: String
        var tierName: String
        var earlyAccess: Bool
        var founderBadge: Bool
        var lifetime: Bool
        var premiumMonths: Int
        var validUntil: String?
        var codeMasked: String?
        var earlyUrl: String?
    }

    struct LabPrefs: Codable, Equatable, Sendable {
        var earlyChannel: Bool = true
        var hideStoryWatermark: Bool = true
        var experimentalMetrics: Bool = true
    }

    private enum Key {
        static let lab = "aravia.supporter.lab"
        static let cachedUID = "aravia.supporter.cachedUID"
        static let cachedEnt = "aravia.supporter.cachedEnt"
    }

    @Published var lab: LabPrefs {
        didSet {
            if let data = try? JSONEncoder().encode(lab) {
                UserDefaults.standard.set(data, forKey: Key.lab)
            }
        }
    }

    private init() {
        if let data = UserDefaults.standard.data(forKey: Key.lab),
           let decoded = try? JSONDecoder().decode(LabPrefs.self, from: data) {
            lab = decoded
        } else {
            lab = LabPrefs()
        }
    }

    func loadCached(uid: String) -> Entitlement? {
        guard UserDefaults.standard.string(forKey: Key.cachedUID) == uid,
              let data = UserDefaults.standard.data(forKey: Key.cachedEnt),
              let ent = try? JSONDecoder().decode(Entitlement.self, from: data),
              ent.earlyAccess else { return nil }
        return ent
    }

    func cache(uid: String, entitlement: Entitlement?) {
        let d = UserDefaults.standard
        guard let entitlement else {
            d.removeObject(forKey: Key.cachedUID)
            d.removeObject(forKey: Key.cachedEnt)
            return
        }
        d.set(uid, forKey: Key.cachedUID)
        if let data = try? JSONEncoder().encode(entitlement) {
            d.set(data, forKey: Key.cachedEnt)
        }
    }

    func clearCache() {
        cache(uid: "", entitlement: nil)
    }

    // MARK: Firestore REST mapping

    static func parseFromFirestore(fields: [String: Any]) -> Entitlement? {
        guard let mapVal = fields["supporter"] as? [String: Any],
              let map = (mapVal["mapValue"] as? [String: Any])?["fields"] as? [String: Any] else { return nil }
        func str(_ k: String) -> String? {
            let v = (map[k] as? [String: Any])?["stringValue"] as? String
            return (v?.isEmpty == false) ? v : nil
        }
        func bool(_ k: String) -> Bool {
            ((map[k] as? [String: Any])?["booleanValue"] as? Bool) ?? false
        }
        func int(_ k: String) -> Int {
            ((map[k] as? [String: Any])?["integerValue"] as? String).flatMap(Int.init) ?? 0
        }
        guard let tier = str("tier"), bool("earlyAccess") else { return nil }
        return Entitlement(
            tier: tier,
            tierName: str("tierName") ?? tier,
            earlyAccess: true,
            founderBadge: bool("founderBadge"),
            lifetime: bool("lifetime"),
            premiumMonths: int("premiumMonths"),
            validUntil: str("validUntil"),
            codeMasked: str("codeMasked"),
            earlyUrl: str("earlyUrl")
        )
    }

    static func firestoreMapField(_ ent: Entitlement) -> [String: Any] {
        var f: [String: Any] = [
            "tier": ["stringValue": ent.tier],
            "tierName": ["stringValue": ent.tierName],
            "earlyAccess": ["booleanValue": ent.earlyAccess],
            "founderBadge": ["booleanValue": ent.founderBadge],
            "lifetime": ["booleanValue": ent.lifetime],
            "premiumMonths": ["integerValue": String(ent.premiumMonths)],
            "updatedAt": ["timestampValue": ISO8601DateFormatter().string(from: Date())],
        ]
        if let v = ent.validUntil, !v.isEmpty { f["validUntil"] = ["stringValue": v] }
        if let v = ent.codeMasked, !v.isEmpty { f["codeMasked"] = ["stringValue": v] }
        if let v = ent.earlyUrl, !v.isEmpty { f["earlyUrl"] = ["stringValue": v] }
        return ["mapValue": ["fields": f]]
    }

    // MARK: Server verification (aravia.app/api/resgatar)

    static func fetchFromServer(idToken: String, code: String? = nil) async throws -> Entitlement? {
        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.timeoutInterval = 12
        req.setValue("Bearer \(idToken)", forHTTPHeaderField: "Authorization")
        req.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.setValue("application/json", forHTTPHeaderField: "Accept")
        var body: [String: Any] = [:]
        if let code {
            let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
            if !trimmed.isEmpty { body["codigo"] = trimmed }
        }
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: req)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        let json = (try? JSONSerialization.jsonObject(with: data) as? [String: Any]) ?? [:]

        guard (200 ... 299).contains(status) else {
            let msg = (json["error"] as? String).flatMap { $0.isEmpty ? nil : $0 }
                ?? "Não foi possível validar o código agora (HTTP \(status))."
            throw Account.AccountError.message(msg)
        }
        guard (json["ativo"] as? Bool) == true else { return nil }
        func optStr(_ k: String) -> String? {
            guard let v = json[k] as? String, !v.isEmpty, v != "null" else { return nil }
            return v
        }
        return Entitlement(
            tier: optStr("faixa") ?? "filhote",
            tierName: optStr("faixaNome") ?? "Apoiador",
            earlyAccess: (json["acessoAntecipado"] as? Bool) ?? false,
            founderBadge: (json["seloFundador"] as? Bool) ?? false,
            lifetime: (json["vitalicio"] as? Bool) ?? false,
            premiumMonths: (json["premiumMeses"] as? Int) ?? 0,
            validUntil: optStr("validoAte"),
            codeMasked: optStr("codigoMascarado"),
            earlyUrl: optStr("earlyUrl")
        )
    }

    static func tierRank(_ tier: String?) -> Int {
        switch tier {
        case "arara_real": 5
        case "adulta": 4
        case "jovem": 3
        case "filhote": 2
        case "ovo": 1
        default: 0
        }
    }
}
