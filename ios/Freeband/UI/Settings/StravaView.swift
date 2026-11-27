import SwiftData
import SwiftUI

/// Strava's orange, from its brand guidelines.
extension Color {
    static let strava = Color(red: 252 / 255, green: 76 / 255, blue: 2 / 255)
}

/// Settings › Strava: connect, send by itself after each sync, disconnect.
struct StravaView: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var strava = Strava.shared
    @State private var error: String?

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image(systemName: strava.isConnected ? "checkmark.circle.fill" : "figure.run.circle")
                    .font(.system(size: 60, weight: .semibold))
                    .foregroundStyle(strava.isConnected ? Color.good : Color.strava)
                    .padding(.top, 32)
                Text(verbatim: strava.isConnected ? "Strava conectado" : "Conecte o Strava")
                    .font(.title2.weight(.bold))
                    .foregroundStyle(Color.textPrimary)
                Text(verbatim: strava.isConnected
                     ? "Depois de cada sincronização, os treinos novos vão para o Strava: com o mapa quando o relógio gravou GPS, e como atividade manual quando não."
                     : "Faça login uma vez e os treinos do relógio vão para o seu Strava, com o mapa das rotas de GPS. O Aravia só pede permissão para enviar atividades.")
                    .font(.subheadline)
                    .foregroundStyle(Color.textSecondary)
                    .multilineTextAlignment(.center)

                if !StravaConfig.isConfigured {
                    Text(verbatim: "Este build não tem as chaves do Strava.")
                        .font(.footnote)
                        .foregroundStyle(Color.low)
                } else if let athlete = strava.athlete, strava.isConnected {
                    Label { Text(verbatim: athlete) } icon: { Image(systemName: "person.crop.circle") }
                        .font(.headline)
                        .foregroundStyle(Color.textPrimary)
                        .padding(14)
                        .frame(maxWidth: .infinity)
                        .background(Color.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    Toggle(isOn: Binding(get: { strava.sendsAutomatically }, set: { strava.setAutomatic($0) })) {
                        Text(verbatim: "Enviar treinos novos sozinho")
                    }
                    .tint(Color.strava)
                    .padding(14)
                    .background(Color.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                    Button {
                        Task { await strava.sendNew(context: model.context) }
                    } label: {
                        HStack {
                            if strava.busy { ProgressView() }
                            Text(verbatim: "Enviar o que falta agora")
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                    .disabled(strava.busy || !strava.sendsAutomatically)
                    if let status = strava.status {
                        Text(verbatim: status).font(.footnote).foregroundStyle(Color.textSecondary).multilineTextAlignment(.center)
                    }
                    Text(verbatim: "Só vão sozinhos os treinos feitos depois que você conectou. Um treino mais antigo pode ser enviado pela tela dele.")
                        .font(.footnote)
                        .foregroundStyle(Color.textSecondary)
                        .multilineTextAlignment(.center)
                    Button(role: .destructive) { strava.signOut() } label: {
                        Text(verbatim: "Desconectar").frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                } else {
                    StravaConnectButton { self.error = $0 }
                }
                if let error {
                    Text(verbatim: error)
                        .font(.footnote)
                        .foregroundStyle(Color.low)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.horizontal, 24)
        }
        .fbScreen()
        .navigationTitle(Text(verbatim: "Strava"))
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// "Conectar com o Strava", shared by Settings and the Quick Start.
struct StravaConnectButton: View {
    @StateObject private var strava = Strava.shared
    private let failed: (String?) -> Void

    init(failed: @escaping (String?) -> Void = { _ in }) { self.failed = failed }

    var body: some View {
        Button {
            failed(nil)
            Task {
                do { try await strava.signIn() } catch Strava.StravaError.cancelled {
                } catch { failed(error.localizedDescription) }
            }
        } label: {
            HStack {
                if strava.busy { ProgressView().tint(.white) }
                Text(verbatim: "Conectar com o Strava")
            }
            .font(.headline)
            .frame(maxWidth: .infinity, minHeight: 50)
        }
        .buttonStyle(.borderedProminent)
        .tint(Color.strava)
        .disabled(strava.busy || !StravaConfig.isConfigured)
    }
}

/// On a workout's or a route's screen: send it now, or open it on Strava once it is there.
/// `compact` is the toolbar form, an icon with errors in an alert.
struct StravaSendButton: View {
    let start: Date
    let compact: Bool
    @Environment(\.modelContext) private var context
    @StateObject private var strava = Strava.shared
    @State private var error: String?

    init(start: Date, compact: Bool = false) {
        self.start = start
        self.compact = compact
    }

    var body: some View {
        if strava.isConnected {
            if compact {
                action
                    .alert(Text(verbatim: "Strava"), isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
                        Button("OK") { error = nil }
                    } message: { Text(verbatim: error ?? "") }
            } else {
                VStack(spacing: 6) {
                    action
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .buttonStyle(.bordered)
                        .tint(Color.strava)
                    if let error {
                        Text(verbatim: error).font(.footnote).foregroundStyle(Color.low).multilineTextAlignment(.center)
                    }
                }
            }
        }
    }

    @ViewBuilder private var action: some View {
        if let id = strava.activityID(near: start), let url = URL(string: "https://www.strava.com/activities/\(id)") {
            Link(destination: url) {
                Label { Text(verbatim: "Ver no Strava") } icon: { Image(systemName: "checkmark.circle") }
                    .frame(maxWidth: compact ? nil : .infinity, minHeight: compact ? nil : 44)
            }
        } else {
            Button(action: send) {
                if strava.busy && !compact {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 44)
                } else {
                    Label { Text(verbatim: "Enviar para o Strava") } icon: { Image(systemName: "arrow.up.forward.circle") }
                        .frame(maxWidth: compact ? nil : .infinity, minHeight: compact ? nil : 44)
                }
            }
            .disabled(strava.busy)
        }
    }

    private func send() {
        error = nil
        Task {
            guard let item = Strava.items(since: start.addingTimeInterval(-300), context: context)
                .first(where: { abs($0.start.timeIntervalSince(start)) < 300 }) else {
                error = "Não achei este treino."
                return
            }
            do { _ = try await strava.sendNow(item) } catch { self.error = error.localizedDescription }
        }
    }
}
