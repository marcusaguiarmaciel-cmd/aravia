import Foundation
import GoogleMobileAds
import UIKit

/// 100% opt-in 24-hour Premium Pass unlocked by watching 2 Rewarded Ads (Google AdMob SDK v12).
/// - Zero in-app purchases (no StoreKit).
/// - Non-personalized ads (`npa = "1"`) by default; zero health data is ever sent to AdMob.
/// - Existing supporters (`Account.shared.hasEarlyAccess`) already have full Premium with zero ads.
@MainActor
final class RewardedPremium: NSObject, ObservableObject {
    static let shared = RewardedPremium()

    static let requiredAds = 2
    static let passDurationSeconds: TimeInterval = 24 * 60 * 60
    /// Official Google AdMob iOS Rewarded test ad unit ID (used when no production secret is configured).
    static let testRewardedUnitID = "ca-app-pub-3940256099942544/1712485313"

    private enum Key {
        static let adsWatched = "aravia.rewarded.adsWatched"
        static let cycleStartedAt = "aravia.rewarded.cycleStartedAt"
        static let passExpiresAt = "aravia.rewarded.passExpiresAt"
        static let passGrantedAt = "aravia.rewarded.passGrantedAt"
        static let passGrantedUptime = "aravia.rewarded.passGrantedUptime"
    }

    @Published private(set) var adsWatched: Int = 0
    @Published private(set) var passExpiresAt: TimeInterval = 0
    @Published private(set) var loadingAd = false
    @Published private(set) var showingAd = false
    @Published private(set) var statusMessage: String?
    @Published private(set) var error: String?

    private var sdkStarted = false
    private var preloadedAd: RewardedAd?
    private var activeAd: RewardedAd?
    private var rewardEarnedInCurrentAd = false

    static var rewardedUnitID: String {
        let raw = (Bundle.main.object(forInfoDictionaryKey: "AdMobRewardedUnitID") as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        if raw.isEmpty || raw.hasPrefix("$(") { return testRewardedUnitID }
        return raw
    }

    static var usingTestIDs: Bool {
        rewardedUnitID == testRewardedUnitID
    }

    private override init() {
        super.init()
        reloadState()
    }

    var isAdPassActive: Bool {
        passExpiresAt > Date().timeIntervalSince1970
    }

    /// True if user has Premium either via supporter account OR an active 24h ad-unlocked pass.
    var hasPremium: Bool {
        Account.shared.hasEarlyAccess || isAdPassActive
    }

    func remainingLabel(now: Date = Date()) -> String {
        let rem = max(0, passExpiresAt - now.timeIntervalSince1970)
        let mins = Int(((rem + 59) / 60).rounded(.down))
        if mins <= 0 { return "Expirado" }
        let h = mins / 60
        let m = mins % 60
        if h > 0 && m > 0 { return "\(h)h \(m)min restantes" }
        if h > 0 { return "\(h)h restantes" }
        return "\(m)min restantes"
    }

    func refreshExpiry() {
        reloadState()
    }

    private func reloadState() {
        let d = UserDefaults.standard
        let now = Date().timeIntervalSince1970
        let expires = d.double(forKey: Key.passExpiresAt)
        let grantedAt = d.double(forKey: Key.passGrantedAt)
        let grantedUptime = d.double(forKey: Key.passGrantedUptime)
        let currentUptime = ProcessInfo.processInfo.systemUptime

        // Basic monotonic clock check: if the wall clock was rolled backwards > 10 min or uptime exceeded 24h.
        let validExpires: TimeInterval
        if expires > now && grantedAt > 0 && now < grantedAt - 600 {
            validExpires = 0
        } else if expires > now && grantedUptime > 0 && currentUptime >= grantedUptime && (currentUptime - grantedUptime) > Self.passDurationSeconds {
            validExpires = 0
        } else if expires > now {
            validExpires = expires
        } else {
            validExpires = 0
        }

        var watched = min(max(d.integer(forKey: Key.adsWatched), 0), Self.requiredAds - 1)
        let cycleStarted = d.double(forKey: Key.cycleStartedAt)
        if watched > 0 && (cycleStarted <= 0 || now - cycleStarted > Self.passDurationSeconds) {
            watched = 0
            d.set(0, forKey: Key.adsWatched)
            d.removeObject(forKey: Key.cycleStartedAt)
        }

        adsWatched = watched
        passExpiresAt = validExpires
    }

    private func makeNonPersonalizedRequest() -> Request {
        let request = Request()
        let extras = Extras()
        extras.additionalParameters = ["npa": "1"]
        request.register(extras)
        return request
    }

    private func topViewController() -> UIViewController? {
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        let window = scenes.flatMap(\.windows).first(where: \.isKeyWindow) ?? scenes.flatMap(\.windows).first
        var vc = window?.rootViewController
        while let presented = vc?.presentedViewController {
            vc = presented
        }
        return vc
    }

    /// Loads and presents one opt-in Rewarded Ad; credits progress only when AdMob confirms completion.
    func watchNextAd() {
        reloadState()
        guard !loadingAd, !showingAd else { return }
        guard let rootVC = topViewController() else {
            error = "Não foi possível abrir a tela de anúncio agora."
            return
        }
        loadingAd = true
        error = nil
        statusMessage = nil

        if let cached = preloadedAd {
            preloadedAd = nil
            present(cached, from: rootVC)
            return
        }

        Task {
            if !sdkStarted {
                await MobileAds.shared.start()
                sdkStarted = true
            }
            do {
                let ad = try await RewardedAd.load(with: Self.rewardedUnitID, request: makeNonPersonalizedRequest())
                guard let vc = topViewController() else {
                    preloadedAd = ad
                    loadingAd = false
                    return
                }
                present(ad, from: vc)
            } catch {
                loadingAd = false
                self.error = "Nenhum anúncio disponível agora. Confira a internet e tente de novo em instantes."
            }
        }
    }

    private func present(_ ad: RewardedAd, from rootVC: UIViewController) {
        loadingAd = false
        showingAd = true
        error = nil
        rewardEarnedInCurrentAd = false
        activeAd = ad
        ad.fullScreenContentDelegate = self
        ad.present(from: rootVC) { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                self.rewardEarnedInCurrentAd = true
                self.creditOneAd()
            }
        }
    }

    private func creditOneAd() {
        let now = Date().timeIntervalSince1970
        let d = UserDefaults.standard
        let nextCount = min(adsWatched + 1, Self.requiredAds)
        if nextCount >= Self.requiredAds {
            let expires = now + Self.passDurationSeconds
            d.set(0, forKey: Key.adsWatched)
            d.removeObject(forKey: Key.cycleStartedAt)
            d.set(expires, forKey: Key.passExpiresAt)
            d.set(now, forKey: Key.passGrantedAt)
            d.set(ProcessInfo.processInfo.systemUptime, forKey: Key.passGrantedUptime)
            adsWatched = 0
            passExpiresAt = expires
            statusMessage = "Premium liberado por 24 horas!"
            error = nil
        } else {
            d.set(nextCount, forKey: Key.adsWatched)
            d.set(now, forKey: Key.cycleStartedAt)
            adsWatched = nextCount
            statusMessage = "\(nextCount) de \(Self.requiredAds) anúncios assistidos — falta só mais 1 para liberar 24h de Premium!"
            error = nil
        }
    }

    private func preloadQuietly() {
        guard preloadedAd == nil, sdkStarted else { return }
        Task {
            if let ad = try? await RewardedAd.load(with: Self.rewardedUnitID, request: makeNonPersonalizedRequest()) {
                self.preloadedAd = ad
            }
        }
    }
    private func restoreWindowLayout() {
        FBWindowLayout.restoreIfNeeded()
        DispatchQueue.main.async {
            FBWindowLayout.restoreIfNeeded()
        }
    }
}

extension RewardedPremium: FullScreenContentDelegate {
    nonisolated func adDidDismissFullScreenContent(_ ad: FullScreenPresentingAd) {
        Task { @MainActor in
            self.activeAd = nil
            self.showingAd = false
            self.restoreWindowLayout()
            if !self.rewardEarnedInCurrentAd {
                self.statusMessage = "Assista o anúncio até o final para contar no seu passe de 24h."
            } else if self.adsWatched == 1 && !self.isAdPassActive {
                self.preloadQuietly()
            }
        }
    }

    nonisolated func ad(_ ad: FullScreenPresentingAd, didFailToPresentFullScreenContentWithError error: Error) {
        Task { @MainActor in
            self.activeAd = nil
            self.showingAd = false
            self.restoreWindowLayout()
            self.error = "Não deu para exibir o anúncio agora. Tente novamente."
        }
    }
}
