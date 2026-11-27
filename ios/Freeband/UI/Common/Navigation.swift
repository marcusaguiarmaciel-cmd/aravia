import SwiftData
import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

enum AppTab: String, Hashable, CaseIterable {
    case today, train, progress, watches
}

enum ProgressSection: String, CaseIterable, Identifiable {
    case trends, sleep

    var id: String { rawValue }

    var title: String {
        switch self {
        case .trends: "Tendências"
        case .sleep: "Sono"
        }
    }
}

enum TrainSection: String, CaseIterable, Identifiable {
    case now, routes, history

    var id: String { rawValue }

    var title: String {
        switch self {
        case .now: "Agora"
        case .routes: "Rotas"
        case .history: "Histórico"
        }
    }
}

/// Every pushed screen. Days travel as `DayKey.index` so routes stay plain values.
enum Route: Hashable {
    case score(ScoreKind, day: Int)
    case scoreMethod(ScoreKind, day: Int)
    case trend(TrendMetric, day: Int)
    case night(day: Int)
    /// A workout of the selected band, by its summary file's id.
    case workout(fileID: String)
    case aboutBand(UUID)
    case monitoring(UUID)
    case bandOptions(UUID)
    case settings
    case advancedSettings
    case bands
    case profile
    case webhooks
    case shortcuts
    case googleDrive
    case strava
    case account
    case diagnostics
    case about
}

/// Tab selection, each tab's path and the app-wide sheets.
@MainActor
final class AppRouter: ObservableObject {
    @Published var tab: AppTab = .today
    @Published var progressSection: ProgressSection = .trends
    @Published var trainSection: TrainSection = .now
    @Published var todayPath: [Route] = []
    @Published var trainPath: [Route] = []
    @Published var progressPath: [Route] = []
    @Published var watchesPath: [Route] = []
    @Published var showsProfile = false
    @Published var showsLive = false
    @Published var addingBand = false
    /// When the running live session ends (BandLink stops live after `liveLimit`).
    @Published var liveEndsAt: Date?
    /// The band was asked to vibrate; cleared by Stop or after a minute.
    @Published var findingSince: Date?

    var isFinding: Bool { findingSince.map { Date().timeIntervalSince($0) < 60 } ?? false }

    func path(_ tab: AppTab) -> Binding<[Route]> {
        switch tab {
        case .today: Binding(get: { self.todayPath }, set: { self.todayPath = $0 })
        case .train: Binding(get: { self.trainPath }, set: { self.trainPath = $0 })
        case .progress: Binding(get: { self.progressPath }, set: { self.progressPath = $0 })
        case .watches: Binding(get: { self.watchesPath }, set: { self.watchesPath = $0 })
        }
    }

    // MARK: band actions shared by Today, Watches and the Live sheet

    func startLive(_ band: Band, model: AppModel) {
        if !(model.link.phase == .live && model.link.bandID == band.id) {
            guard !model.link.phase.isBusy else { showsLive = true; return }
            model.start(band, .live)
        }
        showsLive = true
    }

    func find(_ band: Band, model: AppModel) {
        guard !model.link.phase.isBusy else { return }
        model.findBand(band, start: true)
        findingSince = Date()
    }

    func stopFinding(_ band: Band, model: AppModel) {
        findingSince = nil
        guard !model.link.phase.isBusy else { return }
        model.findBand(band, start: false)
    }
}

/// Compact profile/account button placed in top bars across the four main tabs.
/// Opens the unified Profile & Settings hub (Account, Premium 24h, Beta-Tester, Integrations).
struct ProfileAvatarButton: View {
    @EnvironmentObject private var router: AppRouter
    @StateObject private var account = Account.shared
    @StateObject private var rewarded = RewardedPremium.shared

    private var initial: String {
        if let first = account.username?.first { return String(first).uppercased() }
        if let first = account.email?.first { return String(first).uppercased() }
        return ""
    }

    var body: some View {
        Button {
            router.showsProfile = true
        } label: {
            ZStack(alignment: .topTrailing) {
                Circle()
                    .fill(Color.surface)
                    .frame(width: 34, height: 34)
                    .overlay(Circle().stroke(Color.hairlineStrong, lineWidth: 1))
                    .overlay {
                        if initial.isEmpty {
                            Image(systemName: "person.fill")
                                .font(.system(size: 14, weight: .semibold))
                                .foregroundStyle(Color.textPrimary)
                        } else {
                            Text(verbatim: initial)
                                .font(.system(size: 14, weight: .bold, design: .rounded))
                                .foregroundStyle(Color.textPrimary)
                        }
                    }

                if account.hasEarlyAccess || rewarded.isAdPassActive {
                    Circle()
                        .fill(account.hasEarlyAccess ? Color.action : Color.good)
                        .frame(width: 10, height: 10)
                        .overlay(Circle().stroke(Color.canvas, lineWidth: 1.5))
                        .offset(x: 2, y: -1)
                }
            }
            .frame(minWidth: FBSize.minTarget, minHeight: FBSize.minTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(verbatim: "Perfil, Premium e ajustes"))
    }
}

/// The band shown everywhere (shared selection), or nil before one is added.
struct SelectedBand<Content: View>: View {
    @Query(sort: \Band.addedAt) private var bands: [Band]
    @AppStorage("selectedBand") private var selectedBand = ""
    @ViewBuilder let content: (Band?) -> Content

    var body: some View {
        content(bands.first { $0.id.uuidString == selectedBand } ?? bands.first)
    }
}

extension View {
    /// The destinations every tab's stack can reach.
    func routeDestinations() -> some View {
        navigationDestination(for: Route.self) { route in
            RouteView(route: route)
        }
    }

    /// Canvas behind a scrolling screen.
    func fbScreen() -> some View {
        background(.canvas)
            .scrollContentBackground(.hidden)
    }

    /// Locks a vertical ScrollView's content to the exact viewport width so dynamic children
    /// (Canvas, Charts, ViewThatFits, sheets, ads) can never expand the page wider than the
    /// screen and leave it looking stuck "zoomed in" on iPhone.
    func fbScrollContent() -> some View {
        frame(maxWidth: .infinity, alignment: .leading)
            .containerRelativeFrame(.horizontal, alignment: .topLeading)
            .clipped()
    }
}

/// Defensively resets any stuck root view transform or mismatched window bounds after
/// full-screen ads, interrupted sheet dismissals, or app foregrounding.
enum FBWindowLayout {
    @MainActor
    static func restoreIfNeeded() {
        #if canImport(UIKit)
        let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        for window in scenes.flatMap(\.windows) {
            guard let rootVC = window.rootViewController,
                  rootVC.presentedViewController == nil,
                  let rootView = rootVC.view else { continue }
            var changed = false
            if rootView.transform != .identity {
                rootView.transform = .identity
                changed = true
            }
            if rootView.frame != window.bounds {
                rootView.frame = window.bounds
                changed = true
            }
            if changed {
                rootView.setNeedsLayout()
                rootView.layoutIfNeeded()
            }
        }
        #endif
    }
}

private struct RouteView: View {
    let route: Route

    var body: some View {
        switch route {
        case .settings: SettingsView()
        case .advancedSettings: AdvancedSettingsView()
        case .bands: BandsView()
        case .profile: ProfileView()
        case .webhooks: WebhooksView()
        case .shortcuts: ShortcutsView()
        case .googleDrive: GoogleDriveView()
        case .strava: StravaView()
        case .account: AccountView()
        case .diagnostics: DiagnosticsView()
        case .about: AboutView()
        case .aboutBand(let id): AboutBandView(bandID: id)
        case .monitoring(let id): MonitoringView(bandID: id)
        case .bandOptions(let id): BandOptionsView(bandID: id)
        default:
            SelectedBand { band in
                if let band {
                    BandHealthReader(band: band) { history in
                        switch route {
                        case .score(let kind, let day): ScoreDetailView(kind: kind, day: DayKey(index: day), band: band, history: history)
                        case .scoreMethod(let kind, let day): ScoreMethodView(kind: kind, day: DayKey(index: day), history: history)
                        case .trend(let metric, let day): TrendDetailView(metric: metric, day: DayKey(index: day), band: band, history: history)
                        case .night(let day): SleepNightScreen(band: band, history: history, initialDay: DayKey(index: day))
                        case .workout(let fileID): WorkoutDetailView(bandID: band.id, fileID: fileID)
                        default: EmptyView()
                        }
                    }
                }
            }
        }
    }
}

extension View {
    /// Inside a ScrollViewReader: jumps to the section named by DEBUG `-freeband-scroll`.
    func debugScroll(_ proxy: ScrollViewProxy) -> some View {
        onAppear {
            guard let target = DebugScreen.scrollTarget else { return }
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { proxy.scrollTo(target, anchor: .top) }
        }
    }
}
