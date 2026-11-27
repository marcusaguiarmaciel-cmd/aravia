import ActivityKit
import Foundation

/// The Live Activity of a workout in progress (lock screen and Dynamic Island). Shared by the app,
/// which starts and updates it, and the widget extension, which draws it.
struct WorkoutActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        /// Latest heart rate from the watch; nil until the first reading.
        var bpm: Int?
        /// 0 below zone 1, then zones 1 to 5 (by % of max heart rate).
        var zone: Int
        /// The second line: "1,24 km · 1.630 passos" or "Supino reto · série 3".
        var detail: String
        /// While resting between sets, when the rest ends.
        var restEndsAt: Date?
    }

    /// "Esteira", "Musculação" or "Treino".
    var title: String
    /// SF Symbol of the workout kind.
    var symbol: String
    var startedAt: Date
}
