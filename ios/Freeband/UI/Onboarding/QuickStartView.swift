import ActivityKit
import SwiftData
import SwiftUI
import UserNotifications

/// Quick Start: right after the first sign-in on this phone, one screen per thing the person can
/// set, each with "Pular", then a summary with a button for every skipped step. What belongs to
/// the account (the arara's name, the nest) comes filled in when another phone set it; what
/// belongs to the phone is asked here. Closing the app mid-way resumes at the same step.
/// Settings › Refazer o Quick Start runs it again.
struct QuickStartView: View {
    enum Step: Int, CaseIterable {
        case arara, profile, band, monitoring, connected, alerts, appearance, videos, strava, nest, done

        var title: String {
            switch self {
            case .arara: "Sua arara"
            case .profile: "Seu perfil"
            case .band: "Seu relógio"
            case .monitoring: "O que o relógio mede"
            case .connected: "Ficar conectado"
            case .alerts: "Avisos"
            case .appearance: "Aparência"
            case .videos: "Seus vídeos"
            case .strava: "Strava"
            case .nest: "Ninho"
            case .done: "Pronto"
            }
        }
    }

    enum Outcome: String { case set, skipped }

    static func key(_ uid: String) -> String { "aravia.quickStart.\(uid)" }

    /// Whether this phone already went through the Quick Start for this account.
    static func isDone(_ uid: String?) -> Bool {
        guard let uid else { return true }
        return UserDefaults.standard.bool(forKey: key(uid) + ".done")
    }

    static func reset(_ uid: String?) {
        guard let uid else { return }
        for suffix in [".done", ".step", ".outcomes"] { UserDefaults.standard.removeObject(forKey: key(uid) + suffix) }
    }

    let finish: () -> Void

    @EnvironmentObject private var model: AppModel
    @StateObject private var nest = AraraNest.shared
    @StateObject private var account = Account.shared
    @StateObject private var strava = Strava.shared
    @Query(sort: \Band.addedAt) private var bands: [Band]
    @AppStorage(AppearanceSetting.storageKey) private var appearance = AppearanceSetting.system.rawValue
    @AppStorage(AppModel.stayConnectedKey) private var stayConnected = false
    @AppStorage(AppModel.shareLocationKey) private var shareLocation = false
    @State private var step: Step = .arara
    @State private var outcomes: [Int: Outcome] = [:]
    @State private var araraName = ""
    @State private var addingBand = false
    @State private var showsDrive = false
    @State private var pickingFolder = false
    @State private var showsNest = false
    @State private var notificationsAsked = false
    @State private var stravaError: String?
    @StateObject private var drive = GoogleDrive.shared
    @StateObject private var folder = VideoFolder.shared

    private var uid: String { account.uid ?? "none" }

    var body: some View {
        VStack(spacing: 0) {
            ProgressSegments(step: step.rawValue + 1, total: Step.allCases.count)
                .padding(.horizontal, FBSpace.gutter)
                .padding(.top, FBSpace.s)
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(verbatim: step.title)
                        .font(.system(size: 32, weight: .bold))
                        .foregroundStyle(Color.textPrimary)
                    content
                }
                .padding(20)
                .fbScrollContent()
            }
            if step != .done {
                HStack {
                    Button { advance(.skipped) } label: { Text(verbatim: "Pular").frame(minWidth: 90, minHeight: 50) }
                        .tint(Color.textSecondary)
                    Spacer()
                    Button { Task { await confirm() } } label: {
                        Text(verbatim: "Continuar").font(.headline).frame(minWidth: 160, minHeight: 50)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.action)
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 12)
            }
        }
        .background(Color.canvas.ignoresSafeArea())
        .onAppear(perform: restore)
        .sheet(isPresented: $addingBand) {
            KeyImportSheet(title: "Conectar relógio") { added in
                addingBand = false
                if !added.isEmpty { outcomes[Step.band.rawValue] = .set; save() }
            }
        }
        .sheet(isPresented: $showsDrive) { NavigationStack { GoogleDriveView() } }
        .sheet(isPresented: $pickingFolder) {
            FolderPicker { url in try? folder.set(url) }.ignoresSafeArea()
        }
        .sheet(isPresented: $showsNest) { NestSheet(myXP: nest.accountXP ?? 0) }
    }

    // MARK: steps

    @ViewBuilder private var content: some View {
        switch step {
        case .arara:
            explain("Ela cresce com os seus passos, treinos e rotas, e é a mesma em todos os seus celulares.")
            AraraView(stage: .egg).frame(height: 180).frame(maxWidth: .infinity)
            TextField(text: $araraName) { Text(verbatim: "Nome da arara") }
                .textFieldStyle(.roundedBorder)
                .font(.title3)
        case .profile:
            explain("Idade, altura, peso e as horas de sono de que você precisa entram nas pontuações de recuperação, sono e esforço. Toque em Salvar no alto da tela.")
            NavigationStack { ProfileView() }.frame(height: 460)
        case .band:
            explain("O Aravia funciona com qualquer relógio pelo Apple Saúde (Apple Watch, Garmin, Polar, Amazfit, Xiaomi, Oura), com cintas cardíacas Bluetooth padrão (0x180D) e também direto por Bluetooth com Xiaomi/Redmi.")
            HealthKitCard(compact: true) {
                outcomes[Step.band.rawValue] = .set
                save()
            }
            StandardHrCard()
            if let direct = bands.first(where: { !$0.isHealthKit }) {
                explain("Relógio Xiaomi/Redmi conectado: \(direct.displayName).")
            } else {
                big("Conectar Xiaomi / Redmi direto (chave Mi Fitness)", "applewatch") { addingBand = true }
            }
        case .monitoring:
            if let band = bands.first(where: { !$0.isHealthKit }) {
                explain("Escolha o que o relógio mede sozinho ao longo do dia. Só aparecem as opções que este relógio aceita.")
                NavigationStack { MonitoringView(bandID: band.id) }.frame(height: 460)
            } else {
                explain("Esse ajuste é para relógios Xiaomi/Redmi ligados direto por Bluetooth. Para Apple Saúde ou cintas Bluetooth, pode tocar em Pular.")
            }
        case .connected:
            explain("Manter a conexão deixa os dados sempre atualizados e permite encontrar o celular pelo relógio.")
            Toggle(isOn: Binding(get: { stayConnected }, set: { model.setStayConnected($0) })) {
                Text(verbatim: "Manter conectado em segundo plano")
            }
            Toggle(isOn: Binding(get: { shareLocation }, set: { model.setShareLocation($0) })) {
                Text(verbatim: "Mandar a localização do celular quando o relógio pedir")
            }
            .tint(Color.action)
        case .alerts:
            explain("Avisos do fim do descanso entre séries e o treino na tela de bloqueio e na Ilha Dinâmica.")
            big(notificationsAsked ? "Notificações pedidas" : "Permitir notificações", "bell.badge") {
                Task {
                    _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
                    notificationsAsked = true
                }
            }
            Text(verbatim: ActivityAuthorizationInfo().areActivitiesEnabled
                 ? "Atividades ao Vivo: ligadas."
                 : "Atividades ao Vivo desligadas: ligue em Ajustes › Aravia.")
                .font(.footnote)
                .foregroundStyle(Color.textSecondary)
        case .appearance:
            explain("Siga o celular ou escolha um tema fixo.")
            SegmentedPicker("Aparência", selection: Binding(
                get: { AppearanceSetting(rawValue: appearance) ?? .system },
                set: { appearance = $0.rawValue }), options: AppearanceSetting.allCases) { $0.title }
        case .videos:
            explain("Os vídeos das rotas podem ir direto para a nuvem, sem ocupar o celular.")
            big(drive.isConnected ? "Google Drive: \(drive.email ?? "conectado")" : "Conectar Google Drive", "icloud.and.arrow.up") { showsDrive = true }
            big(folder.name.map { "Pasta: \($0)" } ?? "Escolher pasta (iCloud ou Arquivos)", "folder") { pickingFolder = true }
            explain("Ou só toque em Continuar para guardar no celular.")
        case .strava:
            if strava.isConnected {
                explain("Conectado como \(strava.athlete ?? "você"). Os treinos novos vão para o Strava depois de cada sincronização.")
            } else {
                explain("Os treinos do relógio vão sozinhos para o Strava, com o mapa das rotas de GPS.")
                StravaConnectButton { stravaError = $0 }
                if let stravaError { explain(stravaError) }
            }
        case .nest:
            explain("Num ninho, os pontos de todos se somam numa arara só.")
            big(nest.inNest ? "Ninho \(nest.code ?? "")" : "Criar ou entrar num ninho", "person.2.fill") { showsNest = true }
        case .done:
            explain("Tudo pode ser mudado depois em Ajustes.")
            ForEach(Step.allCases.filter { $0 != .done }, id: \.rawValue) { s in
                HStack {
                    Image(systemName: outcomes[s.rawValue] == .set ? "checkmark.circle.fill" : "circle.dashed")
                        .foregroundStyle(outcomes[s.rawValue] == .set ? Color.good : Color.textSecondary)
                    Text(verbatim: s.title).foregroundStyle(Color.textPrimary)
                    Spacer()
                    if outcomes[s.rawValue] != .set {
                        Button { step = s; save() } label: { Text(verbatim: "Configurar") }.tint(Color.action)
                    }
                }
                .padding(.vertical, 4)
            }
            Button {
                UserDefaults.standard.set(true, forKey: Self.key(uid) + ".done")
                finish()
            } label: {
                Text(verbatim: "Começar a usar").font(.headline).frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .tint(Color.action)
            .padding(.top, 8)
        }
    }

    private func explain(_ text: String) -> some View {
        Text(verbatim: text).font(.body).foregroundStyle(Color.textSecondary).fixedSize(horizontal: false, vertical: true)
    }

    private func big(_ title: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Label { Text(verbatim: title) } icon: { Image(systemName: icon) }
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 50)
        }
        .buttonStyle(.bordered)
        .tint(Color.action)
    }

    // MARK: flow

    /// Continuar: keeps what this step set, then moves on.
    private func confirm() async {
        switch step {
        case .arara:
            if !araraName.trimmingCharacters(in: .whitespaces).isEmpty, araraName != nest.name { await nest.rename(araraName) }
            advance(.set)
        case .band: advance(bands.isEmpty ? .skipped : .set)
        case .monitoring: advance(bands.isEmpty ? .skipped : .set)
        case .alerts: advance(notificationsAsked ? .set : .skipped)
        case .videos: advance(drive.isConnected || folder.name != nil ? .set : .skipped)
        case .strava: advance(strava.isConnected ? .set : .skipped)
        case .nest: advance(nest.inNest ? .set : .skipped)
        default: advance(.set)
        }
    }

    private func advance(_ outcome: Outcome) {
        outcomes[step.rawValue] = outcome
        // A step already handled (from the summary) goes back to the summary.
        let fromSummary = Step.allCases.filter { $0 != .done }.allSatisfy { outcomes[$0.rawValue] != nil }
        step = fromSummary ? .done : Step(rawValue: step.rawValue + 1) ?? .done
        save()
    }

    private func save() {
        UserDefaults.standard.set(step.rawValue, forKey: Self.key(uid) + ".step")
        UserDefaults.standard.set(Dictionary(uniqueKeysWithValues: outcomes.map { (String($0.key), $0.value.rawValue) }),
                                  forKey: Self.key(uid) + ".outcomes")
    }

    private func restore() {
        araraName = nest.name
        if let saved = UserDefaults.standard.object(forKey: Self.key(uid) + ".step") as? Int, let s = Step(rawValue: saved) { step = s }
        if let saved = UserDefaults.standard.dictionary(forKey: Self.key(uid) + ".outcomes") as? [String: String] {
            outcomes = Dictionary(uniqueKeysWithValues: saved.compactMap { k, v in Int(k).flatMap { i in Outcome(rawValue: v).map { (i, $0) } } })
        }
    }
}
