import SwiftData
import SwiftUI
import UniformTypeIdentifiers

/// The Treinar tab: organized into 3 calm sub-sections (Agora, Rotas, Histórico) so the Arara,
/// quick workout launch, GPS routes/Stories, and session history each breathe in their own space.
struct TrainView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var router: AppRouter
    @StateObject private var controller = WorkoutController.shared
    @StateObject private var store = TrainingStore.shared
    @StateObject private var ext = ExternalHealthStore.shared
    @StateObject private var care = AraraCare.shared
    @StateObject private var hr = StandardHrLink.shared
    @Query(filter: #Predicate<ActivityBlob> { $0.type == 1 && $0.detailType == 2 }, sort: \ActivityBlob.ts, order: .reverse)
    private var tracks: [ActivityBlob]
    @State private var showsWorkout = false
    @StateObject private var nest = AraraNest.shared
    @State private var showsNest = false
    @State private var showsCare = false
    @State private var importingGpx = false
    @State private var importMessage: String?

    var body: some View {
        NavigationStack(path: $router.trainPath) {
            SelectedBand { band in
                Group {
                    if let band {
                        BandHealthReader(band: band) { history in
                            content(band: band, history: history)
                        }
                    } else {
                        content(band: nil, history: nil)
                    }
                }
            }
            .navigationTitle(Text(verbatim: "Treinar"))
            .navigationBarTitleDisplayMode(.large)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    ProfileAvatarButton()
                }
            }
            .routeDestinations()
            .fullScreenCover(isPresented: $showsWorkout) {
                ActiveWorkoutView()
            }
            .sheet(isPresented: $showsCare) {
                AraraCareSheet()
            }
            .fileImporter(
                isPresented: $importingGpx,
                allowedContentTypes: [UTType(filenameExtension: "gpx") ?? .xml, .xml],
                allowsMultipleSelection: false
            ) { result in
                switch result {
                case .success(let urls):
                    guard let url = urls.first else { return }
                    let accessed = url.startAccessingSecurityScopedResource()
                    defer { if accessed { url.stopAccessingSecurityScopedResource() } }
                    if let xml = try? String(contentsOf: url, encoding: .utf8),
                       let added = ext.importGpx(xml: xml, fallbackName: url.deletingPathExtension().lastPathComponent) {
                        importMessage = "Rota \"\(added.title)\" importada com sucesso!"
                    } else {
                        importMessage = "Não foi possível ler pontos GPS válidos desse arquivo."
                    }
                case .failure(let err):
                    importMessage = "Erro ao abrir arquivo: \(err.localizedDescription)"
                }
            }
            .onAppear { if controller.isRunning { showsWorkout = true } }
        }
    }

    private func content(band: Band?, history: HealthHistory?) -> some View {
        let mine = tracks.filter { band == nil || $0.bandID == band?.id }
        let totalRoutesCount = mine.count + ext.routes.count
        var steps = 0
        if let history, let first = history.firstDay, let last = history.lastDay, first.index <= last.index {
            for i in first.index...last.index { steps += history.summary(DayKey(index: i))?.steps ?? 0 }
        }
        let contribution = AraraNest.Contribution(steps: steps, routes: totalRoutesCount, workouts: store.sessions.count)
        let own = AraraProgress(totalSteps: steps, workouts: store.sessions.count, routes: totalRoutesCount)
        // The account's points (every phone of this person), once read; this phone's until then.
        let myXP = nest.accountXP ?? own.xp
        let progress = AraraProgress(xp: nest.totalXP(mine: myXP))
        let allRouteDates = mine.map(\.ts) + ext.routes.map(\.date)
        let activeWeek = care.activeDaysThisWeek(history: history, sessions: store.sessions, routeDates: allRouteDates)

        return ScrollView {
            VStack(alignment: .leading, spacing: FBSpace.m) {
                SegmentedPicker("Seção de treino", selection: $router.trainSection, options: TrainSection.allCases) { $0.title }

                switch router.trainSection {
                case .now:
                    nowSection(
                        band: band,
                        history: history,
                        progress: progress,
                        own: own,
                        myXP: myXP,
                        contribution: contribution,
                        activeWeek: activeWeek
                    )
                case .routes:
                    routesSection(mine: mine)
                case .history:
                    historySection
                }
            }
            .padding(.horizontal, FBSpace.gutter)
            .padding(.top, FBSpace.xs)
            .padding(.bottom, FBSpace.xl)
            .fbScrollContent()
        }
        .fbScreen()
    }

    @ViewBuilder
    private func nowSection(
        band: Band?,
        history: HealthHistory?,
        progress: AraraProgress,
        own: AraraProgress,
        myXP: Int,
        contribution: AraraNest.Contribution,
        activeWeek: Int
    ) -> some View {
        AraraCard(
            progress: progress,
            name: nest.name,
            members: nest.inNest ? nest.members.count : 0,
            celebratedAt: nest.celebratedAt,
            activeDaysThisWeek: activeWeek,
            weeklyGoal: care.weeklyGoal,
            resting: care.resting,
            restReason: care.reason,
            openNest: { showsNest = true },
            openCare: { showsCare = true }
        )
        .sheet(isPresented: $showsNest) { NestSheet(myXP: myXP) }
        .task(id: own.xp) {
            await nest.syncAccount(contribution)
            if nest.accountXP == nil { await nest.sync(myXP: own.xp) }
            nest.noteStage(AraraProgress(xp: nest.totalXP(mine: nest.accountXP ?? own.xp)).stage)
        }
        .onChange(of: nest.pendingInvite) { _, invite in if invite != nil { showsNest = true } }
        .onAppear { if nest.pendingInvite != nil { showsNest = true } }

        TodayPlanCard(plan: TodayPlan(history: history))

        VStack(alignment: .leading, spacing: FBSpace.xs) {
            SectionHeader(title: controller.isRunning ? "Treino em andamento" : "Começar treino")

            if controller.isRunning, let s = controller.session {
                Button { showsWorkout = true } label: {
                    Label { Text(verbatim: "Voltar ao treino de \(s.kind.name.lowercased())") } icon: { Image(systemName: "waveform.path.ecg") }
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 50)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.action)
            } else {
                HStack(spacing: 10) {
                    ForEach(TrainingSession.Kind.allCases) { kind in
                        Button {
                            controller.start(kind, band: band, model: model)
                            showsWorkout = true
                        } label: {
                            VStack(spacing: 8) {
                                Image(systemName: kind.symbol).font(.title2).foregroundStyle(Color.action)
                                Text(verbatim: kind.name).font(.subheadline.weight(.semibold))
                            }
                            .frame(maxWidth: .infinity, minHeight: 86)
                            .foregroundStyle(Color.textPrimary)
                            .background(Color.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }

            // Live heart-rate strap indicator or quick link to connect one
            Button { router.tab = .watches } label: {
                ListCard {
                    ListRow(
                        title: hr.status == .connected ? (hr.deviceName ?? "Cinta cardíaca conectada") : "Conectar cinta cardíaca ao vivo",
                        subtitle: hr.status == .connected ? "Batimentos em tempo real ativos para o treino" : "Polar, Garmin HRM, Coospo, Wahoo e outras",
                        value: hr.status == .connected ? hr.bpm.map { "\($0) bpm" } : nil,
                        systemImage: hr.status == .connected ? "heart.fill" : "heart",
                        showsDivider: false
                    )
                }
            }
            .buttonStyle(.fbRow)
        }
    }

    @ViewBuilder
    private func routesSection(mine: [ActivityBlob]) -> some View {
        // Quick actions for Stories & route privacy right at the top
        VStack(alignment: .leading, spacing: FBSpace.xs) {
            Card(padding: FBSpace.s) {
                PrivacyZoneRow()
            }

            HStack(spacing: FBSpace.xs) {
                Button { importingGpx = true } label: {
                    Label { Text(verbatim: "Importar rota (.gpx)") } icon: { Image(systemName: "square.and.arrow.down") }
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 42)
                }
                .buttonStyle(.bordered)
                .tint(Color.action)

                NavigationLink { RoutesList().navigationTitle(Text(verbatim: "Todas as rotas")) } label: {
                    Text(verbatim: "Gerenciar")
                        .font(.subheadline.weight(.medium))
                        .frame(minHeight: 42)
                }
                .buttonStyle(.bordered)
                .tint(Color.action)
            }

            if let importMessage {
                Text(verbatim: importMessage)
                    .font(.footnote)
                    .foregroundStyle(Color.textSecondary)
            }
        }

        if mine.isEmpty && ext.routes.isEmpty {
            EmptyState(
                systemImage: "map",
                title: "Nenhuma corrida ou rota ainda",
                message: "Grave uma corrida ou pedalada com GPS no relógio, sincronize pelo Apple Saúde ou importe um arquivo .gpx acima para gerar mapa 3D e Stories."
            )
        } else {
            if !ext.routes.isEmpty {
                VStack(alignment: .leading, spacing: FBSpace.xs) {
                    SectionHeader(title: "Apple Saúde e importadas", trailing: "\(ext.routes.count)")
                    ForEach(ext.routes) { route in
                        NavigationLink { RouteReplayView(externalRoute: route) } label: {
                            ExternalRouteRow(route: route)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(Color.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            if !mine.isEmpty {
                VStack(alignment: .leading, spacing: FBSpace.xs) {
                    SectionHeader(title: "Gravadas no relógio", trailing: "\(mine.count)")
                    ForEach(mine.prefix(20), id: \.fileID) { blob in
                        NavigationLink { RouteReplayView(blob: blob) } label: {
                            RouteRow(blob: blob)
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(Color.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var historySection: some View {
        if store.sessions.isEmpty {
            EmptyState(
                systemImage: "dumbbell",
                title: "Nenhum treino registrado ainda",
                message: "Seus treinos de musculação e esteira feitos no Aravia aparecem organizados aqui.",
                actionTitle: "Começar agora"
            ) {
                router.trainSection = .now
            }
        } else {
            VStack(alignment: .leading, spacing: FBSpace.xs) {
                SectionHeader(title: "Treinos no Aravia", trailing: "\(store.sessions.count)")
                ForEach(store.sessions.prefix(40)) { session in
                    NavigationLink { SessionDetailView(session: session) } label: { SessionRow(session: session) }
                        .buttonStyle(.plain)
                }
            }
        }
    }
}

// MARK: arara

private struct AraraCard: View {
    let progress: AraraProgress
    let name: String
    let members: Int
    let celebratedAt: Date?
    let activeDaysThisWeek: Int
    let weeklyGoal: Int
    let resting: Bool
    let restReason: AraraCare.RestReason
    let openNest: () -> Void
    let openCare: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                AraraView(stage: progress.stage, celebratedAt: celebratedAt, resting: resting)
                    .frame(width: 130, height: 130)
                VStack(alignment: .leading, spacing: 6) {
                    Button(action: openNest) {
                        HStack(spacing: 6) {
                            Text(verbatim: name)
                                .font(.title2.weight(.heavy))
                                .foregroundStyle(Color.textPrimary)
                                .lineLimit(1)
                            Image(systemName: members > 1 ? "person.2.fill" : "person.badge.plus")
                                .font(.subheadline)
                                .foregroundStyle(Color.action)
                        }
                    }
                    .buttonStyle(.plain)
                    Text(verbatim: resting
                         ? "\(progress.stage.name) · descansando"
                         : (members > 1 ? "\(progress.stage.name) · cuidada por \(members) pessoas" : progress.stage.name))
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(resting ? Color.textSecondary : Color.action)
                    Text(verbatim: resting ? restReason.message : progress.stage.line)
                        .font(.footnote)
                        .foregroundStyle(Color.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    if let next = progress.next {
                        ProgressView(value: progress.fraction).tint(Color.action)
                        Text(verbatim: "\(progress.xp) de \(next.threshold) pontos para virar \(next.name.lowercased())")
                            .font(.caption)
                            .foregroundStyle(Color.textSecondary)
                    } else {
                        Text(verbatim: "\(progress.xp) pontos").font(.caption).foregroundStyle(Color.textSecondary)
                    }
                }
            }

            Divider()

            HStack {
                if resting {
                    Label {
                        Text(verbatim: "Modo descanso: \(restReason.title)")
                            .font(.caption.weight(.medium))
                            .foregroundStyle(Color.textSecondary)
                    } icon: {
                        Image(systemName: "moon.zzz.fill").foregroundStyle(Color.action)
                    }
                } else {
                    Text(verbatim: "Semana: \(activeDaysThisWeek) de \(weeklyGoal) dias ativos")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(activeDaysThisWeek >= weeklyGoal ? Color.good : Color.textSecondary)
                }
                Spacer()
                Button(action: openCare) {
                    Text(verbatim: resting ? "Voltar à rotina" : "Descansar")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.action)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(14)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

private struct AraraCareSheet: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var care = AraraCare.shared

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(verbatim: "Sua arara nunca perde pontos nem regride de fase. O modo descanso pausa a meta semanal com calma quando você precisa se recuperar.")
                        .font(.footnote)
                        .foregroundStyle(Color.textSecondary)
                    if care.resting {
                        Button {
                            care.endRest()
                            dismiss()
                        } label: {
                            Label { Text(verbatim: "Voltar à rotina ativa") } icon: { Image(systemName: "sun.max.fill") }
                                .foregroundStyle(Color.good)
                        }
                    } else {
                        ForEach(AraraCare.RestReason.allCases) { reason in
                            Button {
                                care.startRest(reason)
                                dismiss()
                            } label: {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: reason.title).font(.headline).foregroundStyle(Color.textPrimary)
                                    Text(verbatim: reason.message).font(.caption).foregroundStyle(Color.textSecondary)
                                }
                                .padding(.vertical, 4)
                            }
                        }
                    }
                } header: { Text(verbatim: "Modo descanso") }
                .listRowBackground(Color.surface)

                Section {
                    Stepper(value: Binding(get: { care.weeklyGoal }, set: { care.setWeeklyGoal($0) }), in: 2 ... 6) {
                        Text(verbatim: "Meta semanal: \(care.weeklyGoal) dias ativos")
                    }
                } header: { Text(verbatim: "Ritmo sem culpa") } footer: {
                    Text(verbatim: "Um dia conta como ativo com 5.000 passos, um treino no Aravia ou uma rota de GPS.")
                }
                .listRowBackground(Color.surface)
            }
            .fbScreen()
            .navigationTitle(Text(verbatim: "Cuidar da rotina"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: { Text(verbatim: "OK") }
                }
            }
        }
        .tint(Color.action)
    }
}

// MARK: today's plan

/// The day's suggestion: last night's sleep and this morning's recovery, lowered a step after a
/// very hard day (strain 15 or more on the 0–21 scale).
@MainActor
struct TodayPlan {
    enum Level { case unknown, rest, light, moderate, hard }

    let level: Level
    let recovery: Int?
    let sleep: Int?
    let strainYesterday: Double?

    init(history: HealthHistory?) {
        let todayKey = DayKey.current()
        let today = history?.score(todayKey)
        let yesterday = history?.score(todayKey.adding(days: -1))
        recovery = today?.recoveryScore
        sleep = today?.sleepScore
        strainYesterday = yesterday?.strain
        guard let r = recovery ?? sleep else { level = .unknown; return }
        var l: Level = r >= 67 ? .hard : r >= 45 ? .moderate : r >= 30 ? .light : .rest
        if (sleep ?? 100) < 50, l == .hard { l = .moderate }
        if (strainYesterday ?? 0) >= 15 {
            l = l == .hard ? .moderate : l == .moderate ? .light : l
        }
        level = l
    }

    var title: String {
        switch level {
        case .unknown: "Sem dados de hoje"
        case .rest: "Dia de descanso"
        case .light: "Dia leve"
        case .moderate: "Dia moderado"
        case .hard: "Hoje dá para puxar"
        }
    }

    var advice: String {
        switch level {
        case .unknown: "Durma com o relógio e sincronize de manhã: o Aravia usa o sono e a recuperação para sugerir o treino."
        case .rest: "O corpo ainda está se recuperando. Uma caminhada curta e alongamento bastam."
        case .light: "Caminhada ou esteira leve, 30 a 40 minutos, conversando sem perder o fôlego (zonas 1 e 2)."
        case .moderate: "Treino normal: esteira em ritmo constante ou musculação com cargas de sempre (zona 3)."
        case .hard: "Recuperação boa. Bom dia para intervalados na esteira ou para subir carga na musculação (zonas 4 e 5)."
        }
    }

    var color: Color {
        switch level {
        case .unknown: .textSecondary
        case .rest: .low
        case .light: .fair
        case .moderate: .activity
        case .hard: .good
        }
    }
}

private struct TodayPlanCard: View {
    let plan: TodayPlan

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Circle().fill(plan.color).frame(width: 10, height: 10)
                Text(verbatim: "Treino do dia").font(.caption.weight(.semibold)).foregroundStyle(Color.textSecondary)
            }
            Text(verbatim: plan.title).font(.title3.weight(.bold)).foregroundStyle(Color.textPrimary)
            Text(verbatim: plan.advice).font(.subheadline).foregroundStyle(Color.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
            if plan.level != .unknown {
                HStack(spacing: 14) {
                    if let r = plan.recovery { chip("Recuperação", "\(r)") }
                    if let s = plan.sleep { chip("Sono", "\(s)") }
                    if let st = plan.strainYesterday { chip("Esforço ontem", String(format: "%.1f", st).replacingOccurrences(of: ".", with: ",")) }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func chip(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(verbatim: value).font(.headline.monospacedDigit()).foregroundStyle(Color.textPrimary)
            Text(verbatim: label).font(.caption2).foregroundStyle(Color.textSecondary)
        }
    }
}

// MARK: history

private struct SessionRow: View {
    let session: TrainingSession

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: session.kind.symbol)
                .font(.title3)
                .frame(width: 36)
                .foregroundStyle(Color.action)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: session.kind.name).font(.headline).foregroundStyle(Color.textPrimary)
                Text(session.start, format: .dateTime.day().month(.abbreviated).hour().minute())
                    .font(.subheadline)
                    .foregroundStyle(Color.textSecondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(verbatim: RouteNames.clock(session.duration)).font(.headline.monospacedDigit()).foregroundStyle(Color.textPrimary)
                if let avg = session.avgBPM {
                    Text(verbatim: "\(avg) bpm").font(.subheadline.monospacedDigit()).foregroundStyle(Color.textSecondary)
                }
            }
        }
        .padding(12)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
}
