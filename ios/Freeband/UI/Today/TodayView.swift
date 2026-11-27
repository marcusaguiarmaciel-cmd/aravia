import SwiftData
import SwiftUI

/// Home: the day's verdict, the band in one line, the Recovery card with Sleep and Strain, the
/// day's trace, what stands out, the day's workouts and its numbers as tiles.
struct TodayView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var router: AppRouter

    var body: some View {
        NavigationStack(path: $router.todayPath) {
            SelectedBand { band in
                if let band {
                    BandHealthReader(band: band) { history in
                        TodayContent(band: band, history: history)
                    }
                    // A new day (midnight, clock change) rebuilds the day's queries.
                    .id("\(band.id.uuidString)-\(model.today.timeIntervalSince1970)")
                } else {
                    NoBandView()
                }
            }
            .routeDestinations()
        }
    }
}

/// Before any band is added: welcoming Home with the Arara, quick workout start, and 1 clean card to connect any watch.
private struct NoBandView: View {
    @EnvironmentObject private var router: AppRouter
    @StateObject private var nest = AraraNest.shared
    @StateObject private var care = AraraCare.shared
    @StateObject private var store = TrainingStore.shared
    @StateObject private var ext = ExternalHealthStore.shared
    @StateObject private var hr = StandardHrLink.shared

    var body: some View {
        let own = AraraProgress(totalSteps: 0, workouts: store.sessions.count, routes: ext.routes.count)
        let progress = AraraProgress(xp: nest.totalXP(mine: nest.accountXP ?? own.xp))
        let activeDays = care.activeDaysThisWeek(history: nil, sessions: store.sessions, routeDates: ext.routes.map(\.date))

        ScrollView {
            VStack(alignment: .leading, spacing: FBSpace.l) {
                HStack(alignment: .center, spacing: FBSpace.xs) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(verbatim: "Hoje")
                            .textStyle(.title)
                            .foregroundStyle(.textPrimary)
                            .accessibilityAddTraits(.isHeader)
                        Text(FBFormat.dayTitle(Date()))
                            .textStyle(.subhead)
                            .foregroundStyle(.textSecondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    ProfileAvatarButton()
                }
                .padding(.top, FBSpace.s)

                // Arara + quick workout entry so the app feels alive even without a watch
                Card(padding: FBSpace.m) {
                    VStack(alignment: .leading, spacing: FBSpace.s) {
                        HStack(spacing: 14) {
                            AraraView(stage: progress.stage, celebratedAt: nest.celebratedAt, interactive: false, resting: care.resting)
                                .frame(width: 74, height: 74)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(verbatim: nest.name)
                                    .textStyle(.headline)
                                    .foregroundStyle(.textPrimary)
                                Text(verbatim: care.resting
                                     ? "\(progress.stage.name) · em descanso"
                                     : "\(progress.stage.name) · \(activeDays) de \(care.weeklyGoal) dias na semana")
                                    .textStyle(.subhead)
                                    .foregroundStyle(care.resting ? Color.textSecondary : Color.action)
                                Text(verbatim: store.sessions.isEmpty
                                     ? "Comece um treino pelo celular ou conecte seu relógio para ver Recuperação, Sono e Esforço."
                                     : "\(store.sessions.count) treino(s) registrados no Aravia.")
                                    .textStyle(.caption)
                                    .foregroundStyle(.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        HStack(spacing: FBSpace.xs) {
                            Button {
                                router.tab = .train
                                router.trainSection = .now
                            } label: {
                                Label { Text(verbatim: "Começar treino") } icon: { Image(systemName: "figure.run") }
                                    .font(.subheadline.weight(.semibold))
                                    .frame(maxWidth: .infinity, minHeight: 42)
                            }
                            .buttonStyle(.borderedProminent)
                            .tint(Color.action)

                            Button {
                                router.tab = .train
                                router.trainSection = .routes
                            } label: {
                                Label { Text(verbatim: "Rotas") } icon: { Image(systemName: "map") }
                                    .font(.subheadline.weight(.medium))
                                    .frame(minHeight: 42)
                            }
                            .buttonStyle(.bordered)
                            .tint(Color.action)
                        }
                    }
                }

                // Single clean watch connection CTA — full connection hub lives in the Relógios tab
                Card(padding: FBSpace.m) {
                    VStack(alignment: .leading, spacing: FBSpace.s) {
                        HStack(spacing: 10) {
                            Image(systemName: "applewatch.radiowaves.left.and.right")
                                .font(.title2)
                                .foregroundStyle(Color.action)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(verbatim: "Conecte seu relógio ou cinta")
                                    .textStyle(.headline)
                                    .foregroundStyle(.textPrimary)
                                Text(verbatim: hr.status == .connected
                                     ? "Cinta conectada (\(hr.bpm.map { "\($0) bpm" } ?? "ao vivo")). Conecte também um relógio para sono e recuperação."
                                     : "Funciona com Apple Watch, Garmin, Polar, Amazfit, Xiaomi/Redmi e qualquer cinta cardíaca Bluetooth.")
                                    .textStyle(.subhead)
                                    .foregroundStyle(.textSecondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        }
                        Button {
                            router.tab = .watches
                        } label: {
                            Text(verbatim: "Conectar relógio")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity, minHeight: 42)
                        }
                        .buttonStyle(.bordered)
                        .tint(Color.action)
                    }
                }
            }
            .padding(.horizontal, FBSpace.gutter)
            .padding(.bottom, FBSpace.xl)
            .fbScrollContent()
        }
        .fbScreen()
        .toolbar(.hidden, for: .navigationBar)
    }
}

private struct TodayContent: View {
    let band: Band
    let history: HealthHistory

    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var link: BandLink
    @EnvironmentObject private var router: AppRouter
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.modelContext) private var context
    @ScaledMetric(relativeTo: .body) private var chevronSize: CGFloat = 17
    @Query private var liveSamples: [Sample]
    @StateObject private var nest = AraraNest.shared
    @StateObject private var care = AraraCare.shared
    @StateObject private var store = TrainingStore.shared
    @StateObject private var ext = ExternalHealthStore.shared
    @State private var dayOffset = DebugScreen.dayOffset

    init(band: Band, history: HealthHistory) {
        self.band = band
        self.history = history
        let id = band.id
        let start = Calendar.current.startOfDay(for: Date())
        _liveSamples = Query(filter: #Predicate<Sample> { $0.bandID == id && $0.kind == "live" && $0.ts >= start },
                             sort: \Sample.ts)
    }

    private var today: DayKey { DayKey.current(model.today) }
    private var day: DayKey { today.adding(days: dayOffset) }
    private var isToday: Bool { dayOffset == 0 }
    private var syncing: Bool { BandPresentation.isSyncing(link, band) || model.ingestBusy }

    /// Today's steps include the latest live reading (AppModel.daySteps).
    private var todaySteps: Double? {
        let live = liveSamples.last?.steps
        return [model.daySteps(bandID: band.id), live].compactMap { $0 }.max().map(Double.init)
    }

    private var overrides: [DayKey: Double] { todaySteps.map { [today: $0] } ?? [:] }

    /// Early in the day there is nothing fair to compare with yet.
    private func hidesTodayDelta(_ metric: TodayMetric, _ averages: [TodayMetric: Double]) -> Bool {
        switch metric {
        case .activeMinutes: true
        case .steps: (averages[.steps] ?? 0) < 500
        default: false
        }
    }

    /// Today is not over: steps compare with the last 7 days' steps by this time of day, from
    /// their minute files. (Active minutes have no per-minute history, so today shows no delta.)
    private var todayAverages: [TodayMetric: Double] {
        let now = Date()
        let minute = Int(now.timeIntervalSince(Calendar.current.startOfDay(for: now)) / 60)
        let id = band.id
        var totals: [Double] = []
        for back in 1...7 {
            let index = today.adding(days: -back).index
            guard let row = try? context.fetch(FetchDescriptor<MinuteDay>(
                predicate: #Predicate { $0.bandID == id && $0.dayIndex == index })).first else { continue }
            let steps = row.series.steps.prefix(min(max(minute, 0), MinuteSeries.slots))
            totals.append(Double(steps.reduce(0) { $0 + ($1 ?? 0) }))
        }
        guard !totals.isEmpty else { return [:] }
        return [.steps: totals.reduce(0, +) / Double(totals.count)]
    }

    /// The shown day's minute file, for the day trace.
    private var dayMinutes: MinuteSeries? {
        let id = band.id
        let index = day.index
        return (try? context.fetch(FetchDescriptor<MinuteDay>(predicate: #Predicate { $0.bandID == id && $0.dayIndex == index })))?.first?.series
    }

    /// The newest heart rate of the shown day, wherever it came from: a measurement started on
    /// the band, the band's own minutes, or (today) a live reading.
    private func latestHeartRate(_ minutes: MinuteSeries?) -> (value: Int, at: Date)? {
        var best: (value: Int, at: Date)?
        func offer(_ value: Int, _ at: Date) {
            if ValidRange.hr(value) != nil, best.map({ at > $0.at }) ?? true { best = (value, at) }
        }
        let id = band.id
        let index = day.index
        let manual = (try? context.fetch(FetchDescriptor<ManualReading>(
            predicate: #Predicate { $0.bandID == id && $0.dayIndex == index && $0.kind == "heart_rate" }))) ?? []
        for reading in manual { offer(reading.value, reading.ts) }
        if let minutes, let last = (0 ..< MinuteSeries.slots).last(where: { ValidRange.hr(minutes.heartRate[$0]) != nil }) {
            offer(minutes.heartRate[last] ?? 0, minutes.time(ofMinute: last))
        }
        if isToday, let live = liveSamples.last(where: { $0.heartRate > 10 }) { offer(live.heartRate, live.ts) }
        return best
    }

    /// Heart rate the band recorded in the sleep files that touch the shown day.
    private var dayNightHeart: [(at: Date, bpm: Int)] {
        let next = day.adding(days: 1)
        let records = [history.mainSleep(day), history.mainSleep(next)].compactMap { $0 } + history.naps(day) + history.naps(next)
        return records.flatMap { $0.heartRatePoints.map { (at: $0.date, bpm: Int($0.value)) } }
    }

    /// When the shown day's workouts ran, for the shading on the day trace.
    private var dayWorkouts: [ClosedRange<Date>] {
        let id = band.id
        let index = day.index
        let rows = (try? context.fetch(FetchDescriptor<Workout>(predicate: #Predicate { $0.bandID == id && $0.dayIndex == index }))) ?? []
        return rows.filter { $0.end > $0.start }.map { $0.start ... $0.end }
    }

    /// Every sleep that touches the shown day: the night that ended on it, its naps, and the
    /// night that began on it. A session without stages is drawn as one stretch of light sleep.
    private var daySleep: [StageSegment] {
        let next = day.adding(days: 1)
        let records = [history.mainSleep(day), history.mainSleep(next)].compactMap { $0 } + history.naps(day) + history.naps(next)
        return records.flatMap { record -> [StageSegment] in
            let stages = StageSegment.unpack(record.stageTimeline)
            guard stages.isEmpty else { return stages }
            return [StageSegment(start: record.bedTime, minutes: Int(record.wakeTime.timeIntervalSince(record.bedTime) / 60), stage: .light)]
        }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: FBSpace.l) {
                VStack(alignment: .leading, spacing: FBSpace.xs) {
                    header
                    Button { router.tab = .watches } label: {
                        StatusLine(name: band.shortName, battery: BandPresentation.battery(link, band),
                                   state: BandPresentation.statusState(link, band), lastSync: band.lastSyncAt,
                                   showsChevron: true)
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint(Text(verbatim: "Abre a aba Relógios"))
                    progressLine
                }

                emptyState

                if !isFirstSync {
                    VStack(spacing: FBSpace.xs) {
                        hero
                        pillars
                        if let night = history.mainSleep(day) {
                            nightQuickRow(night)
                        }
                        Card {
                            DayTrace(minutes: dayMinutes, sleep: daySleep, dayStart: day.date, now: isToday ? Date() : nil,
                                     nightHeart: dayNightHeart, workouts: dayWorkouts)
                        }
                    }
                    .opacity(syncing ? 0.5 : 1)
                    .fbStateAnimation(syncing)
                    araraStrip
                    WorkoutsSection(bandID: band.id, day: day)
                    InsightList(insights: history.insights(day, sleepNeedMinutes: Profile.load().sleepNeed), day: day)
                }
                if isToday, !Profile.load().maxHRIsKnown {
                    NavigationLink(value: Route.profile) {
                        Card {
                            HStack(alignment: .firstTextBaseline, spacing: FBSpace.s) {
                                Image(systemName: "person.crop.circle")
                                    .foregroundStyle(.textSecondary)
                                    .accessibilityHidden(true)
                                VStack(alignment: .leading, spacing: FBSpace.xxs) {
                                    Text(verbatim: "Adicione seu ano de nascimento")
                                        .textStyle(.headline)
                                        .foregroundStyle(.textPrimary)
                                    Text(verbatim: "O Esforço usa sua frequência cardíaca máxima, que depende da idade. Enquanto não preencher, o Aravia usa uma estimativa.")
                                        .textStyle(.subhead)
                                        .foregroundStyle(.textSecondary)
                                        .fixedSize(horizontal: false, vertical: true)
                                }
                            }
                            .accessibilityElement(children: .combine)
                        }
                    }
                    .buttonStyle(.plain)
                }
                if isToday {
                    BandContextCard(band: band)
                }
                if !isFirstSync {
                    metrics
                        .opacity(syncing ? 0.5 : 1)
                        .fbStateAnimation(syncing)
                }
            }
            .padding(.horizontal, FBSpace.gutter)
            .padding(.top, FBSpace.xs)
            .padding(.bottom, FBSpace.xl)
            .fbScrollContent()
            .animation(FBMotion.standard, value: router.isFinding)
        }
        .fbScreen()
        .refreshable { await model.syncAndWait(band) }
        .toolbar(.hidden, for: .navigationBar)
    }

    // MARK: quick cards (sleep night & arara rhythm)

    private func nightQuickRow(_ night: SleepRecord) -> some View {
        let slept = history.sleepMinutes(day) ?? Double(night.totalSleepMinutes ?? 0)
        return NavigationLink(value: Route.night(day: day.index)) {
            ListCard {
                ListRow(
                    title: "Detalhes da noite",
                    subtitle: FBFormat.span(night.bedTime, night.wakeTime),
                    value: slept > 0 ? FBFormat.duration(minutes: slept) : nil,
                    systemImage: "moon.stars",
                    showsDivider: false
                )
            }
        }
        .buttonStyle(.fbRow)
    }

    private var araraStrip: some View {
        let activeDays = care.activeDaysThisWeek(history: history, sessions: store.sessions, routeDates: ext.routes.map(\.date))
        let progress = AraraProgress(xp: nest.totalXP(mine: nest.accountXP ?? (store.sessions.count * 10 + ext.routes.count * 10)))
        return Button {
            router.tab = .train
            router.trainSection = .now
        } label: {
            Card(padding: FBSpace.s) {
                HStack(spacing: 12) {
                    AraraView(stage: progress.stage, celebratedAt: nest.celebratedAt, interactive: false, resting: care.resting)
                        .frame(width: 48, height: 48)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(verbatim: "\(nest.name) · \(progress.stage.name)")
                            .textStyle(.headline)
                            .foregroundStyle(.textPrimary)
                        Text(verbatim: care.resting
                             ? "Modo descanso ativo · sem perder progresso"
                             : "Semana: \(activeDays) de \(care.weeklyGoal) dias ativos · toque para treinar")
                            .textStyle(.caption)
                            .foregroundStyle(care.resting ? Color.textSecondary : Color.action)
                    }
                    Spacer()
                    Image(systemName: "chevron.right")
                        .font(.system(size: 13, weight: .medium))
                        .foregroundStyle(.textTertiary)
                }
            }
        }
        .buttonStyle(.plain)
    }

    // MARK: header

    private var verdict: String {
        if let recovery = history.score(.recovery, day) { return DayVerdict(recovery: recovery).word }
        if let sleep = history.score(.sleep, day) { return ScoreBand.sleep(sleep).word }
        return isToday ? "Hoje" : day.noon.formatted(.dateTime.weekday(.wide))
    }

    private var header: some View {
        HStack(alignment: .center, spacing: 2) {
            VStack(alignment: .leading, spacing: 0) {
                Text(verdict)
                    .textStyle(.title)
                    .foregroundStyle(.textPrimary)
                    .accessibilityAddTraits(.isHeader)
                Text(FBFormat.dayTitle(day.noon))
                    .textStyle(.subhead)
                    .foregroundStyle(.textSecondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            pagerButton("chevron.left", label: String(localized: "Previous day"),
                        enabled: history.firstDay.map { day > $0 } ?? false) { dayOffset -= 1 }
            pagerButton("chevron.right", label: String(localized: "Next day"), enabled: dayOffset < 0) { dayOffset += 1 }
            ProfileAvatarButton()
        }
        .padding(.top, FBSpace.s)
    }

    private func pagerButton(_ symbol: String, label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: min(chevronSize, 28), weight: .regular))
                .foregroundStyle(enabled ? Color.textPrimary : Color.textTertiary)
                .frame(width: 36, height: FBSize.minTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

    @ViewBuilder private var progressLine: some View {
        if BandPresentation.isSyncing(link, band), let progress = link.fileProgress, progress.total > 0 {
            ProgressView(value: Double(progress.done), total: Double(progress.total))
                .progressViewStyle(.linear)
                .tint(.textPrimary)
                .accessibilityLabel(Text("Sync progress"))
        }
    }

    // MARK: empty states

    private var isFirstSync: Bool { history.isEmpty && liveSamples.isEmpty }

    @ViewBuilder private var emptyState: some View {
        if isFirstSync {
            EmptyState(systemImage: "arrow.triangle.2.circlepath",
                       title: String(localized: "First sync"),
                       message: String(localized: "Close Mi Fitness, keep the band close and sync. The first sync reads the records the band keeps, up to a week."),
                       actionTitle: String(localized: "Sync")) { model.start(band, .sync) }
                .disabled(link.phase.isBusy)
        } else if isToday, history.score(.recovery, day) == nil, calibratingNights < 3 {
            EmptyState(systemImage: "hourglass",
                       title: String(localized: "Calibrating · \(calibratingNights) of 3 nights"),
                       message: String(localized: "Recovery compares each morning with your normal. Wear the band tonight; it needs three nights to learn it."))
        }
    }

    /// Nights with a resting heart rate so far, the recovery baseline's minimum is three.
    private var calibratingNights: Int {
        min(3, history.baselineDays(.restingHeartRate, before: day.adding(days: 1)))
    }

    // MARK: scores

    /// Recovery leads the day: its ring, and beside it what it was made of.
    private var hero: some View {
        NavigationLink(value: Route.score(.recovery, day: day.index)) {
            Card(padding: FBSpace.m) {
                HStack(alignment: .center, spacing: FBSpace.m) {
                    ScoreArc(kind: .recovery, value: history.score(.recovery, day), style: .hero)
                    VStack(alignment: .leading, spacing: FBSpace.xs) {
                        VStack(alignment: .leading, spacing: 0) {
                            Text(ScoreKind.recovery.name)
                                .textStyle(.headline)
                                .foregroundStyle(.textPrimary)
                            Text(history.score(.recovery, day).map { ScoreKind.recovery.band(for: $0).word } ?? String(localized: "No data"))
                                .textStyle(.subhead)
                                .foregroundStyle(.textSecondary)
                        }
                        VStack(spacing: FBSpace.xxs) {
                            fact(TodayMetric.restingHeartRate.title, history.settledRestingHR(day).map(FBFormat.bpm))
                            fact(String(localized: "HRV"), history.score(day)?.hrv.map { String(localized: "\(Int($0.rounded())) ms") })
                            fact(TodayMetric.sleep.title, history.sleepMinutes(day).map { FBFormat.duration(minutes: $0) })
                        }
                        confidenceMarker(.recovery)
                    }
                }
            }
        }
        .buttonStyle(.plain)
    }

    private func fact(_ label: String, _ value: String?) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .textStyle(.subhead)
                .foregroundStyle(.textSecondary)
            Spacer(minLength: FBSpace.xs)
            Text(value ?? "–")
                .textStyle(.figureS)
                .foregroundStyle(value == nil ? Color.textTertiary : Color.textPrimary)
        }
        .lineLimit(1)
        .accessibilityElement(children: .combine)
    }

    /// Sleep and Strain, side by side; one under the other at large text sizes.
    private var pillars: some View {
        let layout = dynamicTypeSize >= .xxLarge ? AnyLayout(VStackLayout(spacing: FBSpace.xs)) : AnyLayout(HStackLayout(spacing: FBSpace.xs))
        return layout {
            ForEach([ScoreKind.sleep, .strain]) { kind in
                NavigationLink(value: Route.score(kind, day: day.index)) {
                    Card {
                        VStack(alignment: .leading, spacing: FBSpace.xxs) {
                            ScoreTile(kind: kind, value: history.score(kind, day), layout: .row)
                            confidenceMarker(kind)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
        }
    }

    @ViewBuilder private func confidenceMarker(_ kind: ScoreKind) -> some View {
        if history.score(kind, day) != nil, let confidence = history.confidence(kind, day), confidence.level.isLow {
            HStack(spacing: FBSpace.xxs) {
                Image(systemName: "circle.lefthalf.filled")
                    .font(.system(size: 10))
                    .accessibilityHidden(true)
                Text("Low confidence")
            }
            .textStyle(.caption)
            .foregroundStyle(.textSecondary)
        }
    }

    // MARK: metrics

    private var metrics: some View {
        let averages = isToday ? todayAverages : [:]
        func tile(_ metric: TodayMetric, wide: Bool = false) -> some View {
            let reading = metric.reading(history: history, day: day, overrides: metric == .steps ? overrides : [:],
                                         average: averages[metric], hidesDelta: isToday && hidesTodayDelta(metric, averages))
            return NavigationLink(value: Route.trend(metric.trend, day: day.index)) {
                StatTile(title: metric.title, value: reading.value, unit: reading.unit, trend: reading.trend, delta: reading.delta,
                         color: metric.color, wide: wide)
            }
            .buttonStyle(.plain)
        }
        return VStack(alignment: .leading, spacing: FBSpace.xs) {
            SectionHeader(title: isToday ? String(localized: "Today") : FBFormat.shortDay(day.noon))
            let minutes = dayMinutes
            let heart = latestHeartRate(minutes)
            StatTile(title: String(localized: "Heart rate"), value: heart.map { FBFormat.number($0.value) }, unit: String(localized: "bpm"),
                     trend: minutes.map { $0.heartRate.compactMap { ValidRange.hr($0).map(Double.init) } } ?? [],
                     color: .heart, wide: true, note: heart.map { FBFormat.time($0.at) })
            tile(.steps, wide: true)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: FBSpace.xs), GridItem(.flexible())], spacing: FBSpace.xs) {
                // From the band's own daily summary: what it counts as burned by moving, and stress.
                let summary = history.summary(day)
                StatTile(title: String(localized: "Active calories"), value: summary?.activeCalories.map { FBFormat.number($0) },
                         unit: String(localized: "kcal"),
                         trend: (0..<7).reversed().map { history.summary(day.adding(days: -$0))?.activeCalories.map(Double.init) },
                         color: .activity)
                // The band's Moving minutes when the day's minute files carry the level; else our own count.
                if let moving = minutes?.movingMinutes {
                    StatTile(title: String(localized: "Moving"), value: FBFormat.number(moving), unit: String(localized: "min"), color: .activity)
                } else {
                    tile(.activeMinutes)
                }
                tile(.restingHeartRate)
                NavigationLink(value: Route.trend(.stress, day: day.index)) {
                    StatTile(title: String(localized: "Stress"), value: summary?.stressAvg.map { FBFormat.number($0) },
                             trend: (0..<7).reversed().map { history.value(.stress, on: day.adding(days: -$0)) },
                             color: .textSecondary,
                             note: summary.flatMap { s in s.stressMin.flatMap { low in s.stressMax.map { "\(low)–\($0)" } } })
                }
                .buttonStyle(.plain)
                tile(.sleep)
                tile(.bloodOxygen)
            }
            Footnote(String(localized: "Compared with your 7-day average."))
        }
    }
}

/// The five metrics on Home, each with its value, a 7-day shape and a neutral delta.
enum TodayMetric: String, CaseIterable, Identifiable {
    case steps, restingHeartRate, sleep, activeMinutes, bloodOxygen

    var id: String { rawValue }

    var trend: TrendMetric {
        switch self {
        case .steps: .steps
        case .restingHeartRate: .restingHeartRate
        case .sleep: .sleepHours
        case .activeMinutes: .activeMinutes
        case .bloodOxygen: .bloodOxygen
        }
    }

    /// The colour of the metric's line on its tile.
    var color: Color {
        switch self {
        case .steps, .activeMinutes: .activity
        case .restingHeartRate: .heart
        case .sleep: .sleep
        case .bloodOxygen: .textSecondary
        }
    }

    var title: String {
        switch self {
        case .steps: String(localized: "Steps")
        case .restingHeartRate: String(localized: "Resting HR")
        case .sleep: String(localized: "Sleep")
        case .activeMinutes: String(localized: "Active minutes")
        case .bloodOxygen: String(localized: "Blood oxygen")
        }
    }

    struct Reading {
        var value: String?
        var unit: String?
        var trend: [Double?]
        var delta: MetricDelta?
    }

    @MainActor
    func reading(history: HealthHistory, day: DayKey, overrides: [DayKey: Double], average override: Double? = nil,
                 hidesDelta: Bool = false) -> Reading {
        func value(_ d: DayKey) -> Double? { overrides[d] ?? history.value(trend, on: d) }
        let current = value(day)
        let week = (0..<7).reversed().map { value(day.adding(days: -$0)) }
        let previous = (1...7).compactMap { value(day.adding(days: -$0)) }
        let average = override ?? (previous.isEmpty ? nil : previous.reduce(0, +) / Double(previous.count))
        var delta: MetricDelta?
        if !hidesDelta, let current, let average {
            delta = makeDelta(current - average, average: average)
        }
        switch self {
        case .steps:
            return Reading(value: current.map { FBFormat.number(Int($0)) }, trend: week, delta: delta)
        case .restingHeartRate:
            return Reading(value: current.map { FBFormat.number($0) }, unit: String(localized: "bpm"), trend: week, delta: delta)
        case .sleep:
            return Reading(value: current.map { FBFormat.duration(minutes: $0 * 60) }, trend: week, delta: delta)
        case .activeMinutes:
            return Reading(value: current.map { FBFormat.number($0) }, unit: String(localized: "min"), trend: week, delta: delta)
        case .bloodOxygen:
            return Reading(value: current.map { FBFormat.percent($0 / 100) }, trend: week, delta: delta)
        }
    }

    private func makeDelta(_ diff: Double, average: Double) -> MetricDelta {
        switch self {
        case .steps:
            guard average > 0 else { return MetricDelta(.flat, "0") }
            let share = diff / average
            if abs(share) < 0.01 { return MetricDelta(.flat, FBFormat.percent(0)) }
            return MetricDelta(share > 0 ? .up : .down, FBFormat.percent(abs(share)))
        case .sleep:
            let minutes = diff * 60
            if abs(minutes) < 5 { return MetricDelta(.flat, "0") }
            return MetricDelta(minutes > 0 ? .up : .down, FBFormat.compactDuration(minutes: abs(minutes)))
        case .restingHeartRate, .activeMinutes, .bloodOxygen:
            let rounded = diff.rounded()
            if rounded == 0 { return MetricDelta(.flat, "0") }
            return MetricDelta(rounded > 0 ? .up : .down, FBFormat.number(abs(rounded)))
        }
    }
}
