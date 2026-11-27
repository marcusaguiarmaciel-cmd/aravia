import Foundation

/// On-device JSON store for multi-watch data that does not come from Xiaomi's binary files:
/// - External HRV readings (Apple HealthKit SDNN & 60 s BLE chest-strap RMSSD check-ins)
/// - Imported GPS routes (from Apple HealthKit `HKWorkoutRoute` or `.gpx` files)
/// - Detected Apple HealthKit watch/app sources and last sync time
/// Everything stays 100% offline in Application Support; nothing leaves the phone.
@MainActor
final class ExternalHealthStore: ObservableObject {
    static let shared = ExternalHealthStore()

    struct HrvEntry: Codable, Equatable, Sendable {
        var dayIndex: Int
        var valueMs: Double
        var kind: HrvMetricKind
        var sourceId: String
        var recordedAt: Date
    }

    struct StoredPoint: Codable, Equatable, Sendable {
        var ts: TimeInterval
        var lat: Double
        var lon: Double
        var speedKmh: Double?
    }

    struct ExternalRoute: Codable, Identifiable, Equatable, Sendable {
        var id: String
        var title: String
        var sport: String
        var date: Date
        var sourceName: String
        var points: [StoredPoint]

        var track: GpsTrack {
            GpsTrack(points: points.map {
                GpsPoint(time: Date(timeIntervalSince1970: $0.ts), latitude: $0.lat, longitude: $0.lon, speedKmh: $0.speedKmh)
            })
        }
    }

    private struct Snapshot: Codable {
        var spotHrv: [Int: HrvEntry] = [:]
        var healthKitHrv: [Int: HrvEntry] = [:]
        var daySources: [Int: String] = [:]
        var routes: [ExternalRoute] = []
        var detectedSources: [String] = []
        var lastHealthKitSync: Date?
    }

    @Published private(set) var routes: [ExternalRoute] = []
    @Published private(set) var detectedSources: [String] = []
    @Published private(set) var lastHealthKitSync: Date?
    @Published private(set) var latestSpotHrv: HrvEntry?

    private var snapshot = Snapshot()
    private let fileURL: URL

    private init() {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.temporaryDirectory
        let dir = base.appendingPathComponent("AraviaExternal", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("external_health.json")
        load()
    }

    private func load() {
        guard let data = try? Data(contentsOf: fileURL),
              let decoded = try? JSONDecoder().decode(Snapshot.self, from: data) else { return }
        snapshot = decoded
        publish()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(snapshot) {
            try? data.write(to: fileURL, options: [.atomic])
        }
        publish()
    }

    private func publish() {
        routes = snapshot.routes.sorted { $0.date > $1.date }
        detectedSources = snapshot.detectedSources
        lastHealthKitSync = snapshot.lastHealthKitSync
        let todayIdx = DayKey.current().index
        latestSpotHrv = snapshot.spotHrv[todayIdx]
    }

    // MARK: HRV & sources

    func saveSpotHrv(rmssdMs: Double, sourceName: String, day: DayKey = DayKey.current()) {
        snapshot.spotHrv[day.index] = HrvEntry(
            dayIndex: day.index,
            valueMs: rmssdMs,
            kind: .rmssd,
            sourceId: "ble:\(sourceName)",
            recordedAt: Date()
        )
        save()
    }

    func updateHealthKitMeta(hrvByDay: [Int: HrvEntry], daySources: [Int: String], sources: [String], syncedAt: Date = Date()) {
        for (day, entry) in hrvByDay {
            snapshot.healthKitHrv[day] = entry
        }
        for (day, src) in daySources {
            snapshot.daySources[day] = src
        }
        snapshot.detectedSources = Array(Set(sources)).sorted()
        snapshot.lastHealthKitSync = syncedAt
        save()
    }

    /// Returns external HRV for a day:
    /// - For a HealthKit band: prefers HealthKit SDNN (keeping Apple Watch SDNN baseline pure), falling back to BLE Spot RMSSD.
    /// - For a Xiaomi/direct band: uses BLE Spot RMSSD when the band had no raw sleep RR intervals.
    func externalHrv(for dayIndex: Int, isHealthKitBand: Bool) -> HrvEntry? {
        if isHealthKitBand {
            return snapshot.healthKitHrv[dayIndex] ?? snapshot.spotHrv[dayIndex]
        }
        return snapshot.spotHrv[dayIndex]
    }

    func dataSourceId(for dayIndex: Int, isHealthKitBand: Bool) -> String? {
        if isHealthKitBand {
            return snapshot.daySources[dayIndex] ?? "healthkit"
        }
        return "xiaomi_ble"
    }

    // MARK: Routes (GPX + HealthKit HKWorkoutRoute)

    @discardableResult
    func importGpx(xml: String, fallbackName: String = "Rota GPX") -> ExternalRoute? {
        guard let track = GpsTrack.parseGpx(xml), track.points.count >= 2 else { return nil }
        let title = GpsTrack.parseGpxName(xml) ?? fallbackName
        let startDate = track.start ?? Date()
        let id = "gpx-\(Int(startDate.timeIntervalSince1970))-\(track.points.count)"
        let route = ExternalRoute(
            id: id,
            title: title,
            sport: title,
            date: startDate,
            sourceName: "Arquivo GPX",
            points: track.points.map {
                StoredPoint(ts: $0.time.timeIntervalSince1970, lat: $0.latitude, lon: $0.longitude, speedKmh: $0.speedKmh)
            }
        )
        upsertRoute(route)
        return route
    }

    func importHealthKitRoute(id: String, title: String, date: Date, sourceName: String, points: [GpsPoint]) {
        guard points.count >= 2 else { return }
        let route = ExternalRoute(
            id: "hk-\(id)",
            title: title,
            sport: title,
            date: date,
            sourceName: sourceName,
            points: points.map {
                StoredPoint(ts: $0.time.timeIntervalSince1970, lat: $0.latitude, lon: $0.longitude, speedKmh: $0.speedKmh)
            }
        )
        upsertRoute(route)
    }

    func deleteRoute(id: String) {
        snapshot.routes.removeAll { $0.id == id }
        save()
    }

    func clearAll() {
        snapshot = Snapshot()
        try? FileManager.default.removeItem(at: fileURL)
        publish()
    }

    private func upsertRoute(_ route: ExternalRoute) {
        if let idx = snapshot.routes.firstIndex(where: { $0.id == route.id }) {
            snapshot.routes[idx] = route
        } else {
            snapshot.routes.append(route)
        }
        save()
    }
}
