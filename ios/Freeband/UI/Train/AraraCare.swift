import Foundation

/// Guilt-free routine tracking for the Arara (docs/PRODUTO.md §3 Onda 1.3):
/// - Counts active days inside the current week (Mon–Sun) against a gentle weekly goal instead of
///   punishing missed individual days.
/// - Offers an explicit "Arara descansando" pause mode (recovery, illness/injury, travel) that
///   suspends goal pressure without ever reducing the Arara's stage or XP.
@MainActor
final class AraraCare: ObservableObject {
    static let shared = AraraCare()

    enum RestReason: String, CaseIterable, Identifiable {
        case recovery = "RECOVERY"
        case illness = "ILLNESS"
        case travel = "TRAVEL"

        var id: String { rawValue }

        var title: String {
            switch self {
            case .recovery: "Recuperação"
            case .illness: "Doença ou lesão"
            case .travel: "Viagem"
            }
        }

        var message: String {
            switch self {
            case .recovery: "Dia de recuperar as energias. A arara descansa no ninho com você."
            case .illness: "Cuide de você primeiro. A arara guarda seu progresso até você voltar."
            case .travel: "Aproveite a viagem. Quando voltar, a rotina retoma de onde parou."
            }
        }
    }

    private enum Key {
        static let resting = "aravia.araraResting"
        static let reason = "aravia.araraRestReason"
        static let since = "aravia.araraRestSince"
        static let goal = "aravia.araraWeeklyGoal"
    }

    @Published private(set) var resting: Bool
    @Published private(set) var reason: RestReason
    @Published private(set) var restSince: Date?
    @Published private(set) var weeklyGoal: Int

    private init() {
        let d = UserDefaults.standard
        resting = d.bool(forKey: Key.resting)
        reason = RestReason(rawValue: d.string(forKey: Key.reason) ?? "") ?? .recovery
        let ts = d.double(forKey: Key.since)
        restSince = ts > 0 ? Date(timeIntervalSince1970: ts) : nil
        let savedGoal = d.integer(forKey: Key.goal)
        weeklyGoal = (2 ... 6).contains(savedGoal) ? savedGoal : 3
    }

    func startRest(_ reason: RestReason) {
        let now = Date()
        let d = UserDefaults.standard
        d.set(true, forKey: Key.resting)
        d.set(reason.rawValue, forKey: Key.reason)
        d.set(now.timeIntervalSince1970, forKey: Key.since)
        resting = true
        self.reason = reason
        restSince = now
    }

    func endRest() {
        let d = UserDefaults.standard
        d.set(false, forKey: Key.resting)
        d.removeObject(forKey: Key.since)
        resting = false
        restSince = nil
    }

    func setWeeklyGoal(_ days: Int) {
        let clamped = min(max(days, 2), 6)
        UserDefaults.standard.set(clamped, forKey: Key.goal)
        weeklyGoal = clamped
    }

    /// Days active in the current Monday–Sunday week: ≥ 5,000 steps OR a logged workout OR a GPS route.
    func activeDaysThisWeek(
        history: HealthHistory?,
        sessions: [TrainingSession],
        routeDates: [Date],
        now: Date = Date()
    ) -> Int {
        var cal = Calendar(identifier: .iso8601)
        cal.timeZone = .current
        let todayStart = cal.startOfDay(for: now)
        let weekday = cal.component(.weekday, from: todayStart) // ISO: 2 = Monday ... 1 = Sunday
        let daysSinceMonday = (weekday + 5) % 7
        guard let monday = cal.date(byAdding: .day, value: -daysSinceMonday, to: todayStart) else { return 0 }

        var active = Set<Date>()
        if let history {
            for offset in 0 ... daysSinceMonday {
                if let d = cal.date(byAdding: .day, value: offset, to: monday) {
                    let dk = DayKey.current(d)
                    if (history.summary(dk)?.steps ?? 0) >= 5000 {
                        active.insert(d)
                    }
                }
            }
        }
        for s in sessions {
            let d = cal.startOfDay(for: s.start)
            if d >= monday && d <= todayStart { active.insert(d) }
        }
        for rDate in routeDates {
            let d = cal.startOfDay(for: rDate)
            if d >= monday && d <= todayStart { active.insert(d) }
        }
        return active.count
    }
}
