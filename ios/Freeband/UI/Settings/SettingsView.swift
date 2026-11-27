import SwiftData
import SwiftUI
import UIKit

enum AppearanceSetting: String, CaseIterable, Identifiable {
    case system, light, dark

    var id: String { rawValue }

    static let storageKey = "appearance"

    var title: String {
        switch self {
        case .system: String(localized: "appearance.system", defaultValue: "System", comment: "Appearance option")
        case .light: String(localized: "appearance.light", defaultValue: "Light", comment: "Appearance option")
        case .dark: String(localized: "appearance.dark", defaultValue: "Dark", comment: "Appearance option")
        }
    }

    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .light: .light
        case .dark: .dark
        }
    }
}

/// Modal container presented from the top-right ProfileAvatarButton across all tabs.
struct ProfileHubSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            SettingsView()
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button { dismiss() } label: { Text(verbatim: "Fechar") }
                    }
                }
                .routeDestinations()
        }
        .tint(.textPrimary)
    }
}

/// Perfil e Ajustes: account identity, Aravia Premium / Beta-Tester benefits, personal profile,
/// connected apps (Strava, Google Drive, Siri), appearance, and a clean link to Advanced tools.
struct SettingsView: View {
    @StateObject private var account = Account.shared
    @StateObject private var rewarded = RewardedPremium.shared
    @AppStorage(AppearanceSetting.storageKey) private var appearance = AppearanceSetting.system.rawValue
    @State private var showsEarlyAccess = false
    @State private var showsRedeem = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: FBSpace.l) {
                accountHeaderCard

                section("Aravia Premium") {
                    PremiumPassCard(onOpenTools: { showsEarlyAccess = true })

                    ListCard {
                        if rewarded.hasPremium {
                            Button { showsEarlyAccess = true } label: {
                                ListRow(
                                    title: "Laboratório e ferramentas Premium",
                                    value: account.hasEarlyAccess ? (account.supporter?.tierName ?? "Apoiador") : rewarded.remainingLabel(),
                                    systemImage: "sparkles",
                                    showsDivider: !account.hasEarlyAccess
                                )
                            }
                            .buttonStyle(.fbRow)
                        }
                        if !account.hasEarlyAccess {
                            Button { showsRedeem = true } label: {
                                ListRow(
                                    title: "Ativar código de apoiador",
                                    subtitle: "Para quem apoiou em aravia.app",
                                    systemImage: "ticket",
                                    showsDivider: false
                                )
                            }
                            .buttonStyle(.fbRow)
                        }
                    }
                }

                section("Apps conectados") {
                    ListCard {
                        link(.strava, "Strava", value: Strava.shared.athlete ?? "Desconectado", icon: "figure.run.circle")
                        link(.googleDrive, "Google Drive", value: GoogleDrive.shared.email ?? "Desconectado", icon: "icloud.and.arrow.up")
                        link(.shortcuts, "Atalhos e Siri", icon: "square.2.layers.3d", last: true)
                    }
                }

                section("Seu corpo e preferências") {
                    ListCard {
                        link(.profile, "Perfil físico", value: profileSummary, icon: "person")
                        Button {
                            QuickStartView.reset(Account.shared.uid)
                            UserDefaults.standard.set(true, forKey: "aravia.quickStartPending")
                        } label: {
                            ListRow(title: "Refazer guia inicial", systemImage: "wand.and.stars", accessory: .none)
                        }
                        .buttonStyle(.fbRow)
                        Button { BandPresentation.openSystemSettings() } label: {
                            ListRow(title: "Idioma", value: languageName, systemImage: "character.bubble", showsDivider: false)
                        }
                        .buttonStyle(.fbRow)
                    }

                    SegmentedPicker("Aparência", selection: Binding(
                        get: { AppearanceSetting(rawValue: appearance) ?? .system },
                        set: { appearance = $0.rawValue }), options: AppearanceSetting.allCases) { $0.title }
                }

                section("Sistema") {
                    ListCard {
                        link(.advancedSettings, "Dados, exportação e avançado", icon: "slider.horizontal.3")
                        link(.about, "Sobre o Aravia", icon: "info.circle", last: true)
                    }
                }
            }
            .padding(.horizontal, FBSpace.gutter)
            .padding(.top, FBSpace.xs)
            .padding(.bottom, FBSpace.xl)
            .fbScrollContent()
        }
        .fbScreen()
        .navigationTitle(Text(verbatim: "Perfil e Ajustes"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showsEarlyAccess) { EarlyAccessSheet() }
        .sheet(isPresented: $showsRedeem) { RedeemSupporterSheet(onUnlocked: { showsEarlyAccess = true }) }
    }

    private var accountHeaderCard: some View {
        NavigationLink(value: Route.account) {
            Card(padding: FBSpace.m) {
                HStack(spacing: 14) {
                    let initial = account.username?.first.map { String($0).uppercased() }
                        ?? account.email?.first.map { String($0).uppercased() }
                        ?? "A"
                    Circle()
                        .fill(Color.action.opacity(0.16))
                        .frame(width: 50, height: 50)
                        .overlay {
                            Text(verbatim: initial)
                                .font(.title3.weight(.bold))
                                .foregroundStyle(Color.action)
                        }

                    VStack(alignment: .leading, spacing: 3) {
                        HStack(spacing: 6) {
                            Text(verbatim: account.username.map { "@\($0)" } ?? "Sua conta")
                                .textStyle(.headline)
                                .foregroundStyle(.textPrimary)
                            if let sup = account.supporter, sup.founderBadge {
                                Text(verbatim: "Beta-Tester")
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(Color.action)
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 2)
                                    .background(Color.action.opacity(0.15), in: Capsule())
                            } else if rewarded.isAdPassActive {
                                Text(verbatim: "Premium")
                                    .font(.caption2.weight(.semibold))
                                    .foregroundStyle(Color.good)
                                    .padding(.horizontal, 7)
                                    .padding(.vertical, 2)
                                    .background(Color.good.opacity(0.15), in: Capsule())
                            }
                        }
                        Text(verbatim: account.email ?? "Gerenciar conta, privacidade e sessão")
                            .textStyle(.subhead)
                            .foregroundStyle(.textSecondary)
                            .lineLimit(1)
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

    private func link(_ route: Route, _ title: String, value: String? = nil, icon: String, last: Bool = false) -> some View {
        NavigationLink(value: route) {
            ListRow(title: title, value: value, systemImage: icon, showsDivider: !last)
        }
        .buttonStyle(.fbRow)
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: FBSpace.xs) {
            SectionHeader(title: title)
            content()
        }
    }

    private var profileSummary: String? {
        let profile = Profile.load()
        return profile == .unknown ? "Não preenchido" : nil
    }

    private var languageName: String {
        let code = Bundle.main.preferredLocalizations.first ?? "pt"
        return Locale.current.localizedString(forLanguageCode: code)?.capitalized(with: Locale.current) ?? code
    }
}

/// Technical & power-user controls kept out of the main Profile screen: exports, file reparsing,
/// webhooks, saved bands list, demo mode, diagnostics, and local data deletion.
struct AdvancedSettingsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var session: AppSession
    @Environment(\.modelContext) private var context
    @Query(sort: \Band.addedAt) private var bands: [Band]
    @Query private var hooks: [Webhook]
    @State private var share: ShareItem?
    @State private var exportError: String?

    private var sampleCount: Int { (try? context.fetchCount(FetchDescriptor<Sample>())) ?? 0 }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: FBSpace.l) {
                section("Seus dados") {
                    ListCard {
                        Button { export { try Exporter.write(csv: allSamples(), bands: bands) } } label: {
                            ListRow(title: "Exportar tabela (CSV)", systemImage: "tablecells", accessory: .none)
                        }
                        .buttonStyle(.fbRow)
                        Button { export { try Exporter.write(json: allSamples(), bands: bands) } } label: {
                            ListRow(title: "Exportar backup completo (JSON)", systemImage: "curlybraces", accessory: .none)
                        }
                        .buttonStyle(.fbRow)
                        Button { Task { await model.reparseAll() } } label: {
                            ListRow(title: "Reprocessar arquivos salvos",
                                    value: model.ingestBusy ? "Processando…" : nil,
                                    systemImage: "arrow.clockwise", accessory: .none)
                        }
                        .buttonStyle(.fbRow)
                        .disabled(model.ingestBusy)
                        ListRow(title: "Modo demonstração",
                                subtitle: "30 dias fictícios para testar. Seus dados reais ficam intactos.",
                                systemImage: "sparkles",
                                accessory: .toggle(Binding(get: { session.isDemo }, set: { session.setDemo($0) })),
                                showsDivider: false)
                    }
                    Footnote(exportError ?? "\(FBFormat.number(sampleCount)) leituras salvas neste celular.")
                    FBDestructiveButton(title: "Apagar dados de saúde deste celular",
                                        confirmTitle: "Apagar dados",
                                        message: "Leituras, sono, pontuação e arquivos brutos serão apagados. Seus relógios e sua conta continuam salvos.") {
                        model.deleteAllData()
                    }
                }

                section("Ferramentas técnicas") {
                    ListCard {
                        NavigationLink(value: Route.bands) {
                            ListRow(title: "Relógios salvos", value: FBFormat.number(bands.count), systemImage: "rectangle.portrait")
                        }
                        .buttonStyle(.fbRow)
                        NavigationLink(value: Route.webhooks) {
                            ListRow(title: "Webhooks", value: webhookSummary, systemImage: "arrow.up.forward.app")
                        }
                        .buttonStyle(.fbRow)
                        NavigationLink(value: Route.diagnostics) {
                            ListRow(title: "Diagnósticos e logs", systemImage: "stethoscope", showsDivider: false)
                        }
                        .buttonStyle(.fbRow)
                    }
                }
            }
            .padding(.horizontal, FBSpace.gutter)
            .padding(.top, FBSpace.xs)
            .padding(.bottom, FBSpace.xl)
            .fbScrollContent()
        }
        .fbScreen()
        .navigationTitle(Text(verbatim: "Avançado"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $share) { ActivitySheet(items: $0.urls) }
    }

    private func section<Content: View>(_ title: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: FBSpace.xs) {
            SectionHeader(title: title)
            content()
        }
    }

    private var webhookSummary: String? {
        let active = hooks.filter(\.enabled).count
        return active == 0 ? nil : "\(active) ativo(s)"
    }

    private func allSamples() -> [Sample] {
        (try? context.fetch(FetchDescriptor<Sample>(sortBy: [SortDescriptor(\.ts)]))) ?? []
    }

    private func export(_ make: () throws -> URL) {
        do {
            share = ShareItem(urls: [try make()])
            exportError = nil
        } catch {
            exportError = "Não foi possível exportar: \(error.localizedDescription)"
        }
    }
}

/// Every band on this phone: add, merge duplicates, open one to remove it.
struct BandsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var router: AppRouter
    @Query(sort: \Band.addedAt) private var bands: [Band]
    @AppStorage("selectedBand") private var selectedBand = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: FBSpace.l) {
                if !bands.isEmpty {
                    ListCard {
                        ForEach(Array(bands.enumerated()), id: \.element.id) { i, band in
                            NavigationLink(value: Route.aboutBand(band.id)) {
                                ListRow(title: band.displayName,
                                        subtitle: StatusLine.synced(band.lastSyncAt, now: Date()),
                                        value: band.id.uuidString == selectedBand || (selectedBand.isEmpty && i == 0)
                                            ? String(localized: "Shown") : nil,
                                        systemImage: "rectangle.portrait", showsDivider: i < bands.count - 1)
                            }
                            .buttonStyle(.fbRow)
                        }
                    }
                }
                VStack(alignment: .leading, spacing: FBSpace.xs) {
                    Button(String(localized: "Add band")) { router.addingBand = true }
                        .buttonStyle(.fbSecondary(fullWidth: true))
                    Button(String(localized: "Merge duplicates")) { model.mergeDuplicateBands() }
                        .buttonStyle(.fbTertiary(fullWidth: true))
                    Footnote(String(localized: "Mi Fitness can list one band more than once. Merging keeps one band per device and moves its records, files and keys."))
                }
            }
            .padding(.horizontal, FBSpace.gutter)
            .padding(.bottom, FBSpace.xl)
        }
        .fbScreen()
        .navigationTitle(String(localized: "Bands"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// What the formulas need to know about you. Saving scores the last 90 days again.
struct ProfileView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var draft = Profile.load()
    @State private var saved = Profile.load()
    @AppStorage(UnitSetting.storageKey) private var units = UnitSetting.system.rawValue

    private var unitSetting: UnitSetting { UnitSetting(rawValue: units) ?? .system }

    private static let currentYear = Calendar.current.component(.year, from: Date())

    private var birthYear: Binding<Int> {
        Binding(get: { draft.age.map { Self.currentYear - $0 } ?? 0 },
                set: { draft.age = $0 == 0 ? nil : Self.currentYear - $0 })
    }

    private func optionalInt(_ keyPath: WritableKeyPath<Profile, Int?>) -> Binding<Int> {
        Binding(get: { draft[keyPath: keyPath] ?? 0 }, set: { draft[keyPath: keyPath] = $0 == 0 ? nil : $0 })
    }

    private func optionalDouble(_ keyPath: WritableKeyPath<Profile, Double?>) -> Binding<Int> {
        Binding(get: { Int(draft[keyPath: keyPath] ?? 0) }, set: { draft[keyPath: keyPath] = $0 == 0 ? nil : Double($0) })
    }

    var body: some View {
        Form {
            Section {
                Picker(String(localized: "Birth year"), selection: birthYear) {
                    Text("Not set").tag(0)
                    ForEach((1930...(Self.currentYear - 10)).reversed(), id: \.self) { Text(verbatim: "\($0)").tag($0) }
                }
                Picker(String(localized: "Sex"), selection: $draft.sex) {
                    Text("Not set").tag(Profile.Sex.unknown)
                    Text("Female").tag(Profile.Sex.female)
                    Text("Male").tag(Profile.Sex.male)
                }
                Picker(String(localized: "Units"), selection: $units) {
                    ForEach(UnitSetting.allCases) { Text($0.title).tag($0.rawValue) }
                }
                Picker(String(localized: "Height"), selection: optionalDouble(\.heightCm)) {
                    Text("Not set").tag(0)
                    ForEach(120...220, id: \.self) { cm in
                        Text(Measurement(value: Double(cm), unit: UnitLength.centimeters)
                            .formatted(.measurement(width: .abbreviated, usage: .personHeight).locale(unitSetting.locale))).tag(cm)
                    }
                }
                Picker(String(localized: "Weight"), selection: optionalDouble(\.weightKg)) {
                    Text("Not set").tag(0)
                    ForEach(35...200, id: \.self) { kg in
                        Text(Measurement(value: Double(kg), unit: UnitMass.kilograms)
                            .formatted(.measurement(width: .abbreviated, usage: .personWeight).locale(unitSetting.locale))).tag(kg)
                    }
                }
            } footer: {
                Text("Age sets your maximum heart rate (208 − 0.7 × age); sex sets how strain weighs effort. Unknown values use averages, and the scores say so.")
            }
            .fbFormRow()

            Section {
                Picker(String(localized: "Maximum heart rate"), selection: optionalInt(\.maxHROverride)) {
                    Text("From age").tag(0)
                    ForEach(150...220, id: \.self) { Text(FBFormat.bpm(Double($0))).tag($0) }
                }
                Picker(String(localized: "Sleep need"), selection: optionalInt(\.sleepNeedMinutes)) {
                    Text("8 hours (default)").tag(0)
                    ForEach(Array(stride(from: 360, through: 600, by: 15)), id: \.self) {
                        Text(FBFormat.duration(minutes: Double($0))).tag($0)
                    }
                }
            } footer: {
                Text("Set a maximum heart rate only if you measured it. Sleep need is what a full sleep score asks for.")
            }
            .fbFormRow()
        }
        .fbFormStyle()
        .navigationTitle(String(localized: "Profile"))
        .navigationBarTitleDisplayMode(.inline)
        .fbPinnedPrimary(String(localized: "Save"), isEnabled: draft != saved, background: .canvas) {
            draft.save()
            saved = draft
            model.rescoreAll()
            dismiss()
        }
    }
}
