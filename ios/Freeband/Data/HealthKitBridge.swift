import CoreLocation
import Foundation
import HealthKit
import SwiftData

/// Universal read-only Apple HealthKit bridge for iOS: reads steps, heart rate, resting HR,
/// HRV (SDNN), sleep stages, SpO₂, active calories, workouts and GPS workout routes from
/// Apple Watch, Garmin, Polar, Suunto, Amazfit/Zepp, Xiaomi/Mi Fitness, Oura, Whoop, etc.
///
/// 100% on-device; gracefully handles devices or free-Apple-ID sideloaded builds where the
/// HealthKit entitlement is not available without ever crashing.
@MainActor
final class HealthKitBridge: ObservableObject {
    static let shared = HealthKitBridge()

    enum Availability: Equatable {
        case available
        case unavailable
        case missingEntitlement
    }

    @Published private(set) var availability: Availability = HKHealthStore.isHealthDataAvailable() ? .available : .unavailable
    @Published private(set) var connected = UserDefaults.standard.bool(forKey: "aravia.healthKitConnected")
    @Published private(set) var syncing = false
    @Published private(set) var lastMessage: String?
    @Published private(set) var error: String?

    private let store = HKHealthStore()

    private var readTypes: Set<HKObjectType> {
        var set = Set<HKObjectType>()
        let quantities: [HKQuantityTypeIdentifier] = [
            .stepCount,
            .heartRate,
            .restingHeartRate,
            .heartRateVariabilitySDNN,
            .oxygenSaturation,
            .activeEnergyBurned,
            .distanceWalkingRunning,
        ]
        for id in quantities {
            if let q = HKQuantityType.quantityType(forIdentifier: id) { set.insert(q) }
        }
        if let sleep = HKCategoryType.categoryType(forIdentifier: .sleepAnalysis) { set.insert(sleep) }
        set.insert(HKObjectType.workoutType())
        set.insert(HKSeriesType.workoutRoute())
        return set
    }

    private init() {}

    func requestAndSync(context: ModelContext, model: AppModel) async {
        guard HKHealthStore.isHealthDataAvailable() else {
            availability = .unavailable
            error = "O Apple Saúde não está disponível neste dispositivo."
            return
        }
        error = nil
        do {
            try await store.requestAuthorization(toShare: [], read: readTypes)
            connected = true
            availability = .available
            UserDefaults.standard.set(true, forKey: "aravia.healthKitConnected")
            await sync(context: context, model: model)
        } catch {
            let msg = error.localizedDescription
            if msg.localizedCaseInsensitiveContains("entitlement") || msg.localizedCaseInsensitiveContains("com.apple.developer.healthkit") {
                availability = .missingEntitlement
                self.error = "O Apple Saúde exige assinatura com conta Apple Developer (TestFlight/App Store). Em instalações Sideloadly gratuitas, use sensores Bluetooth padrão (0x180D), conexão direta Xiaomi/Redmi ou importe .gpx."
            } else {
                self.error = "Não foi possível abrir as permissões do Apple Saúde: \(msg)"
            }
        }
    }

    func sync(context: ModelContext, model: AppModel, daysBack: Int = 30) async {
        guard HKHealthStore.isHealthDataAvailable() else {
            availability = .unavailable
            return
        }
        guard !syncing else { return }
        syncing = true
        error = nil
        defer { syncing = false }

        let now = Date()
        let cal = Calendar.current
        let todayStart = cal.startOfDay(for: now)
        guard let rangeStart = cal.date(byAdding: .day, value: -(daysBack - 1), to: todayStart) else { return }

        do {
            let band = ensureHealthKitBand(context: context)
            let tzMinutes = TimeZone.current.secondsFromGMT(for: now) / 60

            async let stepsTask = queryDailySums(.stepCount, unit: .count(), from: rangeStart, to: now)
            async let kcalTask = queryDailySums(.activeEnergyBurned, unit: .kilocalorie(), from: rangeStart, to: now)
            async let hrTask = queryQuantitySamples(.heartRate, unit: HKUnit.count().unitDivided(by: .minute()), from: rangeStart, to: now)
            async let rhrTask = queryQuantitySamples(.restingHeartRate, unit: HKUnit.count().unitDivided(by: .minute()), from: rangeStart, to: now)
            async let hrvTask = queryQuantitySamples(.heartRateVariabilitySDNN, unit: .secondUnit(with: .milli), from: rangeStart, to: now)
            async let spo2Task = queryQuantitySamples(.oxygenSaturation, unit: .percent(), from: rangeStart, to: now)
            async let sleepTask = querySleepSamples(from: rangeStart.addingTimeInterval(-12 * 3600), to: now)
            async let workoutsTask = queryWorkouts(from: rangeStart, to: now)

            let dailySteps = try await stepsTask
            let dailyKcal = try await kcalTask
            let hrSamples = try await hrTask
            let rhrSamples = try await rhrTask
            let hrvSamples = try await hrvTask
            let spo2Samples = try await spo2Task
            let sleepSamples = try await sleepTask
            let workouts = try await workoutsTask

            var detectedSources = Set<String>()
            var daySources: [Int: String] = [:]

            func noteSource(_ sample: HKSample, dayIdx: Int) {
                let name = sample.sourceRevision.source.name
                let bundle = sample.sourceRevision.source.bundleIdentifier
                if !name.isEmpty { detectedSources.insert(name) }
                if daySources[dayIdx] == nil, !bundle.isEmpty {
                    daySources[dayIdx] = "hk:\(bundle)"
                }
            }

            // Group HR samples into MinuteSeries per day.
            var minuteByDay: [Int: MinuteSeries] = [:]
            var hrMinByDay: [Int: Int] = [:]
            var hrMaxByDay: [Int: Int] = [:]
            var hrSumByDay: [Int: (sum: Int, count: Int)] = [:]

            for s in hrSamples {
                let bpm = Int(s.quantity.doubleValue(for: HKUnit.count().unitDivided(by: .minute())).rounded())
                guard (30 ... 220).contains(bpm) else { continue }
                let dk = DayKey(date: s.startDate, offsetMinutes: tzMinutes)
                noteSource(s, dayIdx: dk.index)
                let startOfDay = dk.start(offsetMinutes: tzMinutes)
                let minute = Int(s.startDate.timeIntervalSince(startOfDay) / 60)
                guard (0 ..< MinuteSeries.slots).contains(minute) else { continue }
                var series = minuteByDay[dk.index] ?? MinuteSeries(day: dk, tzOffsetMinutes: tzMinutes)
                series.heartRate[minute] = bpm
                minuteByDay[dk.index] = series
                hrMinByDay[dk.index] = min(hrMinByDay[dk.index] ?? bpm, bpm)
                hrMaxByDay[dk.index] = max(hrMaxByDay[dk.index] ?? bpm, bpm)
                let prev = hrSumByDay[dk.index] ?? (0, 0)
                hrSumByDay[dk.index] = (prev.sum + bpm, prev.count + 1)
            }

            // Resting HR per day (latest reading for that day).
            var rhrByDay: [Int: Int] = [:]
            for s in rhrSamples {
                let bpm = Int(s.quantity.doubleValue(for: HKUnit.count().unitDivided(by: .minute())).rounded())
                guard (30 ... 140).contains(bpm) else { continue }
                let dk = DayKey(date: s.startDate, offsetMinutes: tzMinutes)
                noteSource(s, dayIdx: dk.index)
                rhrByDay[dk.index] = bpm
            }

            // HRV (SDNN) per day: median of that day's readings (Apple Watch reports SDNN in ms).
            var hrvValuesByDay: [Int: [Double]] = [:]
            var hrvSourceByDay: [Int: String] = [:]
            for s in hrvSamples {
                let ms = s.quantity.doubleValue(for: .secondUnit(with: .milli))
                guard ms >= 5, ms <= 250 else { continue }
                let dk = DayKey(date: s.startDate, offsetMinutes: tzMinutes)
                noteSource(s, dayIdx: dk.index)
                hrvValuesByDay[dk.index, default: []].append(ms)
                hrvSourceByDay[dk.index] = "hk:\(s.sourceRevision.source.bundleIdentifier)"
            }
            var hrvEntries: [Int: ExternalHealthStore.HrvEntry] = [:]
            for (dayIdx, vals) in hrvValuesByDay {
                if let med = Stats.median(vals) {
                    hrvEntries[dayIdx] = ExternalHealthStore.HrvEntry(
                        dayIndex: dayIdx,
                        valueMs: med,
                        kind: .sdnn,
                        sourceId: hrvSourceByDay[dayIdx] ?? "healthkit",
                        recordedAt: now
                    )
                }
            }

            // SpO2 per day (%).
            var spo2ByDay: [Int: [Int]] = [:]
            for s in spo2Samples {
                let pct = Int((s.quantity.doubleValue(for: .percent()) * 100.0).rounded())
                guard (70 ... 100).contains(pct) else { continue }
                let dk = DayKey(date: s.startDate, offsetMinutes: tzMinutes)
                noteSource(s, dayIdx: dk.index)
                spo2ByDay[dk.index, default: []].append(pct)
            }

            // Collect all days touched by steps, kcal, HR, RHR, SpO2 or HRV.
            var allDays = Set<Int>()
            allDays.formUnion(dailySteps.keys)
            allDays.formUnion(dailyKcal.keys)
            allDays.formUnion(minuteByDay.keys)
            allDays.formUnion(rhrByDay.keys)
            allDays.formUnion(spo2ByDay.keys)

            // Persist MinuteDay & DaySummary rows for the HealthKit band.
            let bandID = band.id
            let sourceTag = "healthkit"

            for dayIdx in allDays {
                let dk = DayKey(index: dayIdx)
                if let series = minuteByDay[dayIdx] {
                    let existing = (try? context.fetch(FetchDescriptor<MinuteDay>(
                        predicate: #Predicate { $0.bandID == bandID && $0.dayIndex == dayIdx }))).flatMap(\.first)
                    let draft = MinuteDayDraft(series: series, sourceFileIDs: [sourceTag])
                    if let existing {
                        existing.update(from: draft)
                    } else {
                        context.insert(MinuteDay(bandID: bandID, draft: draft))
                    }
                }

                let spo2Vals = spo2ByDay[dayIdx] ?? []
                let spo2Avg = spo2Vals.isEmpty ? nil : spo2Vals.reduce(0, +) / spo2Vals.count
                let hrAvg = hrSumByDay[dayIdx].map { $0.sum / max($0.count, 1) }
                let sumDraft = DaySummaryDraft(
                    day: dk,
                    tzOffsetMinutes: tzMinutes,
                    sourceFileID: sourceTag,
                    fileVersion: 1,
                    steps: dailySteps[dayIdx],
                    activeCalories: dailyKcal[dayIdx],
                    calories: dailyKcal[dayIdx],
                    hrResting: rhrByDay[dayIdx],
                    hrMin: hrMinByDay[dayIdx],
                    hrMax: hrMaxByDay[dayIdx],
                    hrAvg: hrAvg,
                    stressAvg: nil,
                    stressMin: nil,
                    stressMax: nil,
                    standingMask: nil,
                    spo2Avg: spo2Avg,
                    spo2Min: spo2Vals.min(),
                    spo2Max: spo2Vals.max(),
                    trainingLoadDay: nil,
                    trainingLoadWeek: nil,
                    trainingLoadLevel: nil,
                    vitalityCurrent: nil,
                    vitalityLight: nil,
                    vitalityModerate: nil,
                    vitalityHigh: nil,
                    recoveryHours: nil
                )
                try? context.delete(model: DaySummary.self, where: #Predicate {
                    $0.bandID == bandID && $0.dayIndex == dayIdx && $0.sourceFileID == sourceTag
                })
                context.insert(DaySummary(bandID: bandID, draft: sumDraft))
            }

            // Persist SleepRecords grouped into sessions (gaps > 2 h start a new session).
            let groupedSleeps = buildSleepDrafts(from: sleepSamples, tzMinutes: tzMinutes, noteSource: noteSource)
            var sleepCount = 0
            for draft in groupedSleeps {
                let dayIdx = draft.day.index
                try? context.delete(model: SleepRecord.self, where: #Predicate {
                    $0.bandID == bandID && $0.dayIndex == dayIdx && $0.sourceFileID == sourceTag
                })
            }
            for draft in groupedSleeps {
                context.insert(SleepRecord(bandID: bandID, draft: draft))
                sleepCount += 1
            }

            // Import workouts & HKWorkoutRoute GPS tracks.
            var routeCount = 0
            for w in workouts {
                let dk = DayKey(date: w.startDate, offsetMinutes: tzMinutes)
                noteSource(w, dayIdx: dk.index)
                let srcName = w.sourceRevision.source.name.isEmpty ? "Apple Saúde" : w.sourceRevision.source.name
                let title = Self.workoutTitle(w.workoutActivityType)
                let points = (try? await queryWorkoutRoutePoints(for: w)) ?? []
                if points.count >= 2 {
                    ExternalHealthStore.shared.importHealthKitRoute(
                        id: w.uuid.uuidString,
                        title: title,
                        date: w.startDate,
                        sourceName: srcName,
                        points: points
                    )
                    routeCount += 1
                }
            }

            band.lastSyncAt = now
            try? context.save()

            ExternalHealthStore.shared.updateHealthKitMeta(
                hrvByDay: hrvEntries,
                daySources: daySources,
                sources: Array(detectedSources),
                syncedAt: now
            )

            // Recompute ScoreEngine v4 for the HealthKit band.
            let earliest = allDays.min().map(DayKey.init(index:)) ?? DayKey.current(now).adding(days: -(daysBack - 1))
            DailyRollup.recompute(context: context, bandID: bandID, from: earliest, now: now, profile: .load())
            // If user has no selected band yet, select Apple Saúde automatically.
            if (UserDefaults.standard.string(forKey: "selectedBand") ?? "").isEmpty {
                UserDefaults.standard.set(bandID.uuidString, forKey: "selectedBand")
            }

            var summaryParts: [String] = []
            if !allDays.isEmpty { summaryParts.append("\(allDays.count) dias") }
            if sleepCount > 0 { summaryParts.append("\(sleepCount) noites") }
            if !hrvEntries.isEmpty { summaryParts.append("\(hrvEntries.count) HRV") }
            if routeCount > 0 { summaryParts.append("\(routeCount) rotas GPS") }
            lastMessage = summaryParts.isEmpty ? "Sem registros recentes no Apple Saúde" : summaryParts.joined(separator: " · ")
            connected = true
            UserDefaults.standard.set(true, forKey: "aravia.healthKitConnected")
        } catch {
            let msg = error.localizedDescription
            if msg.localizedCaseInsensitiveContains("entitlement") || msg.localizedCaseInsensitiveContains("com.apple.developer.healthkit") {
                availability = .missingEntitlement
                self.error = "O Apple Saúde exige assinatura com conta Apple Developer (TestFlight/App Store)."
            } else {
                self.error = "Erro ao ler Apple Saúde: \(msg)"
            }
        }
    }

    private func ensureHealthKitBand(context: ModelContext) -> Band {
        let marker: String? = Band.healthKitMarker
        if let existing = (try? context.fetch(FetchDescriptor<Band>(predicate: #Predicate { $0.mac == marker })))?.first {
            return existing
        }
        let b = Band(name: "Apple Saúde", mac: Band.healthKitMarker)
        context.insert(b)
        try? context.save()
        return b
    }

    // MARK: HealthKit queries

    private func queryDailySums(_ id: HKQuantityTypeIdentifier, unit: HKUnit, from start: Date, to end: Date) async throws -> [Int: Int] {
        guard let qType = HKQuantityType.quantityType(forIdentifier: id) else { return [:] }
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: .strictStartDate)
        let anchor = Calendar.current.startOfDay(for: start)
        let interval = DateComponents(day: 1)
        let tzMinutes = TimeZone.current.secondsFromGMT(for: end) / 60

        return try await withCheckedThrowingContinuation { cont in
            let q = HKStatisticsCollectionQuery(
                quantityType: qType,
                quantitySamplePredicate: predicate,
                options: .cumulativeSum,
                anchorDate: anchor,
                intervalComponents: interval
            )
            q.initialResultsHandler = { _, collection, err in
                if let err { cont.resume(throwing: err); return }
                var out: [Int: Int] = [:]
                collection?.enumerateStatistics(from: start, to: end) { stats, _ in
                    if let sum = stats.sumQuantity() {
                        let val = Int(sum.doubleValue(for: unit).rounded())
                        if val > 0 {
                            let dk = DayKey(date: stats.startDate, offsetMinutes: tzMinutes)
                            out[dk.index] = val
                        }
                    }
                }
                cont.resume(returning: out)
            }
            store.execute(q)
        }
    }

    private func queryQuantitySamples(_ id: HKQuantityTypeIdentifier, unit: HKUnit, from start: Date, to end: Date) async throws -> [HKQuantitySample] {
        guard let qType = HKQuantityType.quantityType(forIdentifier: id) else { return [] }
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: .strictStartDate)
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)
        return try await withCheckedThrowingContinuation { cont in
            let q = HKSampleQuery(sampleType: qType, predicate: predicate, limit: HKObjectQueryNoLimit, sortDescriptors: [sort]) { _, samples, err in
                if let err { cont.resume(throwing: err); return }
                cont.resume(returning: (samples as? [HKQuantitySample]) ?? [])
            }
            store.execute(q)
        }
    }

    private func querySleepSamples(from start: Date, to end: Date) async throws -> [HKCategorySample] {
        guard let cType = HKCategoryType.categoryType(forIdentifier: .sleepAnalysis) else { return [] }
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: .strictStartDate)
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: true)
        return try await withCheckedThrowingContinuation { cont in
            let q = HKSampleQuery(sampleType: cType, predicate: predicate, limit: HKObjectQueryNoLimit, sortDescriptors: [sort]) { _, samples, err in
                if let err { cont.resume(throwing: err); return }
                cont.resume(returning: (samples as? [HKCategorySample]) ?? [])
            }
            store.execute(q)
        }
    }

    private func queryWorkouts(from start: Date, to end: Date) async throws -> [HKWorkout] {
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end, options: .strictStartDate)
        let sort = NSSortDescriptor(key: HKSampleSortIdentifierStartDate, ascending: false)
        return try await withCheckedThrowingContinuation { cont in
            let q = HKSampleQuery(sampleType: .workoutType(), predicate: predicate, limit: 60, sortDescriptors: [sort]) { _, samples, err in
                if let err { cont.resume(throwing: err); return }
                cont.resume(returning: (samples as? [HKWorkout]) ?? [])
            }
            store.execute(q)
        }
    }

    private func queryWorkoutRoutePoints(for workout: HKWorkout) async throws -> [GpsPoint] {
        let predicate = HKQuery.predicateForObjects(from: workout)
        let routes: [HKWorkoutRoute] = try await withCheckedThrowingContinuation { cont in
            let q = HKSampleQuery(sampleType: HKSeriesType.workoutRoute(), predicate: predicate, limit: 4, sortDescriptors: nil) { _, samples, err in
                if let err { cont.resume(throwing: err); return }
                cont.resume(returning: (samples as? [HKWorkoutRoute]) ?? [])
            }
            store.execute(q)
        }
        guard let route = routes.first else { return [] }
        return try await withCheckedThrowingContinuation { cont in
            var points: [GpsPoint] = []
            let q = HKWorkoutRouteQuery(route: route) { _, locations, done, err in
                if let err {
                    cont.resume(throwing: err)
                    return
                }
                if let locations {
                    for loc in locations {
                        let kmh = loc.speed >= 0 ? loc.speed * 3.6 : nil
                        points.append(GpsPoint(
                            time: loc.timestamp,
                            latitude: loc.coordinate.latitude,
                            longitude: loc.coordinate.longitude,
                            speedKmh: kmh
                        ))
                    }
                }
                if done {
                    cont.resume(returning: points)
                }
            }
            store.execute(q)
        }
    }

    // MARK: Sleep stage mapping

    private func buildSleepDrafts(
        from samples: [HKCategorySample],
        tzMinutes: Int,
        noteSource: (HKSample, Int) -> Void
    ) -> [SleepDraft] {
        // Filter out pure `.inBed` when stage samples exist; keep asleep/awake intervals.
        let stageSamples = samples.filter { $0.value != HKCategoryValueSleepAnalysis.inBed.rawValue }
        let usable = stageSamples.isEmpty ? samples : stageSamples
        guard !usable.isEmpty else { return [] }

        // Cluster consecutive samples with gap <= 2 hours into one sleep session.
        var clusters: [[HKCategorySample]] = []
        for s in usable {
            if var last = clusters.last, let prevEnd = last.last?.endDate, s.startDate.timeIntervalSince(prevEnd) <= 2 * 3600 {
                last.append(s)
                clusters[clusters.count - 1] = last
            } else {
                clusters.append([s])
            }
        }

        var drafts: [SleepDraft] = []
        for cluster in clusters {
            guard let bed = cluster.map(\.startDate).min(),
                  let wake = cluster.map(\.endDate).max(),
                  wake.timeIntervalSince(bed) >= 15 * 60 else { continue }
            let dk = DayKey(date: wake, offsetMinutes: tzMinutes)
            if let first = cluster.first { noteSource(first, dk.index) }

            var segments: [StageSegment] = []
            var deep = 0, light = 0, rem = 0, awake = 0
            var hasExplicitStages = false

            for s in cluster {
                let mins = max(1, Int((s.endDate.timeIntervalSince(s.startDate) / 60).rounded()))
                let stage: StageKind
                switch s.value {
                case HKCategoryValueSleepAnalysis.awake.rawValue:
                    stage = .awake; awake += mins
                case HKCategoryValueSleepAnalysis.asleepDeep.rawValue:
                    stage = .deep; deep += mins; hasExplicitStages = true
                case HKCategoryValueSleepAnalysis.asleepREM.rawValue:
                    stage = .rem; rem += mins; hasExplicitStages = true
                case HKCategoryValueSleepAnalysis.asleepCore.rawValue:
                    stage = .light; light += mins; hasExplicitStages = true
                default:
                    stage = .light; light += mins
                }
                segments.append(StageSegment(start: s.startDate, minutes: mins, stage: stage))
            }

            let totalSleep = deep + light + rem
            guard totalSleep >= 15 else { continue }
            let isNap = totalSleep < 180
            drafts.append(SleepDraft(
                sourceFileID: "healthkit",
                kind: .stages,
                fileVersion: 1,
                day: dk,
                tzOffsetMinutes: tzMinutes,
                bedTime: bed,
                wakeTime: wake,
                deepMinutes: hasExplicitStages ? deep : nil,
                lightMinutes: hasExplicitStages ? light : totalSleep,
                remMinutes: hasExplicitStages ? rem : nil,
                awakeMinutes: awake,
                segments: segments,
                hrStart: nil,
                hrIntervalSeconds: 60,
                hrValues: [],
                spo2Start: nil,
                spo2IntervalSeconds: 60,
                spo2Values: [],
                wakeCount: segments.filter { $0.stage == .awake }.count,
                sleepQuality: nil,
                isNap: isNap,
                rr: [],
                isPrimary: true
            ))
        }
        return drafts
    }

    private static func workoutTitle(_ type: HKWorkoutActivityType) -> String {
        switch type {
        case .running: "Corrida"
        case .walking: "Caminhada"
        case .cycling: "Pedalada"
        case .hiking: "Trilha"
        case .swimming: "Natação"
        case .traditionalStrengthTraining, .functionalStrengthTraining: "Musculação"
        default: "Treino ao ar livre"
        }
    }
}
