import ActivityKit
import Combine
import SwiftUI

/// Runs the workout in progress: keeps the watch's live heart rate on for the whole session,
/// records it, counts steps, times the rest between sets and keeps the Live Activity (lock screen
/// and Dynamic Island) up to date.
@MainActor
final class WorkoutController: ObservableObject {
    static let shared = WorkoutController()

    @Published private(set) var session: TrainingSession?
    @Published private(set) var bpm: Int?
    @Published private(set) var restEndsAt: Date?
    @Published var restSeconds: Int = UserDefaults.standard.object(forKey: "aravia.restSeconds") as? Int ?? 90 {
        didSet { UserDefaults.standard.set(restSeconds, forKey: "aravia.restSeconds") }
    }

    private var model: AppModel?
    private var watcher: AnyCancellable?
    private var stepsAtStart: UInt64?
    private var lastBeat = Date.distantPast
    private var lastActivityUpdate = Date.distantPast
    private var activity: Activity<WorkoutActivityAttributes>?
    private var restTask: Task<Void, Never>?

    var isRunning: Bool { session != nil }

    func start(_ kind: TrainingSession.Kind, band: Band?, model: AppModel) {
        guard session == nil else { return }
        self.model = model
        session = TrainingSession(kind: kind, start: Date())
        bpm = nil
        stepsAtStart = nil
        restEndsAt = nil
        if let band, !band.isHealthKit { model.startWorkoutLive(band) }
        watcher = model.link.$stats
            .compactMap { $0 }
            .receive(on: RunLoop.main)
            .sink { [weak self] stats in self?.received(stats) }
        startActivity()
    }

    /// Feeds live heart rate from any standard Bluetooth SIG (`0x180D`) strap or broadcast watch.
    func receivedExternalBpm(_ hr: Int) {
        guard var s = session, (30 ... 220).contains(hr) else { return }
        bpm = hr
        if Date().timeIntervalSince(lastBeat) >= 5 {
            s.heart.append(.init(t: Date(), bpm: hr))
            lastBeat = Date()
            session = s
        }
        updateActivity()
    }

    private func received(_ stats: MiAuth.LiveStats) {
        guard var s = session else { return }
        let first = stepsAtStart ?? stats.steps
        stepsAtStart = first
        s.steps = Int(stats.steps - min(stats.steps, first))
        let hr = Int(stats.heartRate)
        if hr > 30 {
            bpm = hr
            if Date().timeIntervalSince(lastBeat) >= 5 {
                s.heart.append(.init(t: Date(), bpm: hr))
                lastBeat = Date()
            }
        }
        session = s
        updateActivity()
    }

    // MARK: gym

    func addEntry(_ exercise: String) {
        guard var s = session else { return }
        s.entries.append(.init(exercise: exercise))
        session = s
        updateActivity(force: true)
    }

    func removeEntry(_ id: UUID) {
        guard var s = session else { return }
        s.entries.removeAll { $0.id == id }
        session = s
    }

    func logSet(entry id: UUID, reps: Int, kg: Double) {
        guard var s = session, let i = s.entries.firstIndex(where: { $0.id == id }) else { return }
        s.entries[i].sets.append(.init(reps: reps, kg: kg, doneAt: Date()))
        session = s
        startRest()
    }

    func removeSet(entry id: UUID, set setID: UUID) {
        guard var s = session, let i = s.entries.firstIndex(where: { $0.id == id }) else { return }
        s.entries[i].sets.removeAll { $0.id == setID }
        session = s
    }

    func startRest() {
        restTask?.cancel()
        let ends = Date().addingTimeInterval(TimeInterval(restSeconds))
        restEndsAt = ends
        updateActivity(force: true)
        restTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(Double(self?.restSeconds ?? 90)))
            guard !Task.isCancelled, let self else { return }
            self.restEndsAt = nil
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            self.updateActivity(force: true)
        }
    }

    func skipRest() {
        restTask?.cancel()
        restEndsAt = nil
        updateActivity(force: true)
    }

    func setTypedDistance(_ meters: Double?) {
        session?.typedDistanceMeters = meters
    }

    // MARK: end

    func finish() {
        guard var s = session else { return }
        s.end = Date()
        // A workout started by mistake and ended at once is not kept.
        if s.duration >= 60 || !s.heart.isEmpty || !s.entries.isEmpty {
            TrainingStore.shared.add(s)
            if let context = model?.context { Task { await Strava.shared.sendNew(context: context) } }
        }
        close()
    }

    func discard() { close() }

    private func close() {
        restTask?.cancel()
        watcher = nil
        if model?.link.liveWindow != nil {
            model?.link.liveWindow = nil
            model?.stop()
        }
        session = nil
        bpm = nil
        restEndsAt = nil
        endActivity()
    }

    // MARK: heart zones

    /// 0 below 50% of max heart rate, then zones 1 to 5 in steps of 10%.
    static func zone(_ bpm: Int?) -> Int {
        guard let bpm else { return 0 }
        let pct = Double(bpm) / Profile.load().maxHR
        return min(5, max(0, Int((pct - 0.4) * 10)))
    }

    static let zoneNames = ["Repouso", "Muito leve", "Leve", "Moderado", "Forte", "Máximo"]

    // MARK: Live Activity

    private var detail: String {
        guard let s = session else { return "" }
        switch s.kind {
        case .gym:
            guard let entry = s.entries.last else { return "Escolha o primeiro exercício" }
            return "\(entry.exercise) · série \(entry.sets.count + 1)"
        case .treadmill, .other:
            let km = String(format: "%.2f km", s.distanceMeters(heightCm: Profile.load().heightCm) / 1000)
                .replacingOccurrences(of: ".", with: ",")
            return "\(km) · \(s.steps.formatted(.number.locale(Locale(identifier: "pt_BR")))) passos"
        }
    }

    private var state: WorkoutActivityAttributes.ContentState {
        .init(bpm: bpm, zone: Self.zone(bpm), detail: detail, restEndsAt: restEndsAt)
    }

    private func startActivity() {
        guard let s = session, ActivityAuthorizationInfo().areActivitiesEnabled else { return }
        let attributes = WorkoutActivityAttributes(title: s.kind.name, symbol: s.kind.symbol, startedAt: s.start)
        activity = try? Activity.request(attributes: attributes, content: .init(state: state, staleDate: nil))
    }

    /// At most every 5 s for heart rate; sets and rest go out at once.
    private func updateActivity(force: Bool = false) {
        guard let activity, force || Date().timeIntervalSince(lastActivityUpdate) >= 5 else { return }
        lastActivityUpdate = Date()
        let content = ActivityContent(state: state, staleDate: nil)
        Task { await activity.update(content) }
    }

    private func endActivity() {
        guard let activity else { return }
        self.activity = nil
        Task { await activity.end(nil, dismissalPolicy: .immediate) }
    }
}
