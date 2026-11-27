import Foundation

/// Sleep Regularity Index (Phillips et al., Scientific Reports 2017):
/// percentage probability (0–100) that a person is in the same sleep/wake state at any two time
/// points 24 hours apart, computed over 5-minute epochs using noon-to-noon sleep-day windows
/// (`-12h .. +12h` around wake-day local midnight) so today's score never depends on tomorrow's sleep.
/// Device-agnostic: works identically on 2-stage and 4-stage wearables.
enum SleepRegularity {
    static let epochMinutes = 5
    static let epochsPerDay = 1440 / epochMinutes // 288
    static let minDays = 4

    /// Returns SRI (0...100) ending on `endingDay` across up to `windowDays` consecutive days,
    /// or `nil` when fewer than `minDays` days in the window have recorded sleep.
    static func computeSri(
        sleepsByDay: [Int: [SleepInput]],
        endingDay: DayKey,
        windowDays: Int = 7
    ) -> Double? {
        let startIdx = endingDay.index - (windowDays - 1)
        let days = Array(startIdx ... endingDay.index)
        let recorded = days.filter { !(sleepsByDay[$0] ?? []).isEmpty }
        guard recorded.count >= minDays else { return nil }

        var grid: [Int: [Bool]] = [:]
        for d in days {
            let sleeps = sleepsByDay[d] ?? []
            let tz = sleeps.first?.tzOffsetMinutes ?? 0
            // Noon-to-noon window (-12h .. +12h around wake-day local midnight).
            let windowStart = Double(d) * 86_400.0 - 12.0 * 3600.0 - Double(tz) * 60.0
            var epochs = [Bool](repeating: false, count: epochsPerDay)
            for i in 0 ..< epochsPerDay {
                let mid = Date(timeIntervalSince1970: windowStart + Double(i * epochMinutes * 60 + epochMinutes * 30))
                epochs[i] = sleeps.contains { $0.contains(mid) }
            }
            grid[d] = epochs
        }

        var matches = 0
        var total = 0
        for idx in 0 ..< (days.count - 1) {
            let d1 = days[idx]
            let d2 = days[idx + 1]
            guard !(sleepsByDay[d1] ?? []).isEmpty, !(sleepsByDay[d2] ?? []).isEmpty,
                  let g1 = grid[d1], let g2 = grid[d2] else { continue }
            for i in 0 ..< epochsPerDay {
                if g1[i] == g2[i] { matches += 1 }
                total += 1
            }
        }
        guard total > 0 else { return nil }
        let p = Double(matches) / Double(total)
        // Phillips et al. 2017: SRI = -100 + 200 * P(same state 24h apart), clamped 0...100 for UI.
        return Stats.clamp(-100.0 + 200.0 * p, 0.0, 100.0)
    }
}
