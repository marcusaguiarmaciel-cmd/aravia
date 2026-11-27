import SwiftUI

/// Ajustes › Conta: who is signed in, the legal texts, sign out and delete the account.
struct AccountView: View {
    @StateObject private var account = Account.shared
    @StateObject private var rewarded = RewardedPremium.shared
    @State private var reading: LegalDoc?
    @State private var showsEarlyAccess = false
    @State private var showsRedeem = false
    @State private var deleting = false
    @State private var password = ""
    @State private var error: String?
    @State private var busy = false

    var body: some View {
        List {
            Section {
                LabeledContent { Text(verbatim: account.username.map { "@\($0)" } ?? "—") } label: { Text(verbatim: "Usuário") }
                LabeledContent { Text(verbatim: account.email ?? "—") } label: { Text(verbatim: "E-mail") }
                LabeledContent {
                    Text(verbatim: account.emailVerified ? "Confirmado" : "Não confirmado")
                        .foregroundStyle(account.emailVerified ? Color.good : Color.low)
                } label: { Text(verbatim: "Situação") }
                if let sup = account.supporter {
                    LabeledContent {
                        Text(verbatim: sup.founderBadge ? "\(sup.tierName) · Beta-Tester" : sup.tierName)
                            .foregroundStyle(Color.action)
                    } label: { Text(verbatim: "Apoiador") }
                }
            }
            .listRowBackground(Color.surface)

            Section {
                if rewarded.hasPremium {
                    Button { showsEarlyAccess = true } label: {
                        LabeledContent {
                            Text(verbatim: account.hasEarlyAccess ? "Apoiador" : rewarded.remainingLabel())
                                .foregroundStyle(Color.good)
                        } label: {
                            Text(verbatim: "Laboratório e ferramentas Premium")
                        }
                    }
                }
                if !account.hasEarlyAccess {
                    Button { showsRedeem = true } label: {
                        Text(verbatim: "Ativar código de apoiador (aravia.app)")
                    }
                }
            } header: { Text(verbatim: "Acesso Antecipado e Premium") }
            .listRowBackground(Color.surface)

            Section {
                Button { reading = .terms } label: { Text(verbatim: "Termos de Uso") }
                Button { reading = .privacy } label: { Text(verbatim: "Política de Privacidade") }
                LabeledContent { Text(verbatim: "versão \(account.acceptedVersion ?? 0)") } label: { Text(verbatim: "Aceite registrado") }
            } header: { Text(verbatim: "Documentos") } footer: {
                Text(verbatim: "Dúvidas ou pedidos sobre os seus dados: \(Legal.contactEmail).")
            }
            .listRowBackground(Color.surface)

            Section {
                Button { account.signOut() } label: { Text(verbatim: "Sair da conta") }
                Button(role: .destructive) { deleting = true } label: { Text(verbatim: "Excluir conta") }
            } footer: {
                Text(verbatim: "Excluir apaga para sempre a conta, o nome de usuário, os aceites e a sua participação no ninho. Os dados de saúde ficam só neste celular; para apagá-los, use Ajustes › Apagar dados deste celular.")
            }
            .listRowBackground(Color.surface)
        }
        .fbScreen()
        .navigationTitle(Text(verbatim: "Conta"))
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: $reading) { LegalDocView(doc: $0) }
        .sheet(isPresented: $showsEarlyAccess) { EarlyAccessSheet() }
        .sheet(isPresented: $showsRedeem) { RedeemSupporterSheet(onUnlocked: { showsEarlyAccess = true }) }
        .alert(Text(verbatim: "Excluir a conta?"), isPresented: $deleting) {
            SecureField(text: $password) { Text(verbatim: "Sua senha") }
            Button(role: .destructive) {
                Task {
                    busy = true
                    defer { busy = false; password = "" }
                    do { try await account.deleteAccount(password: password) } catch { self.error = error.localizedDescription }
                }
            } label: { Text(verbatim: "Excluir para sempre") }
            Button(role: .cancel) { password = "" } label: { Text(verbatim: "Cancelar") }
        } message: {
            Text(verbatim: "Isso não pode ser desfeito. Digite a sua senha para confirmar.")
        }
        .alert(Text(verbatim: "Não deu para excluir"), isPresented: Binding(get: { error != nil }, set: { if !$0 { error = nil } })) {
            Button { error = nil } label: { Text(verbatim: "OK") }
        } message: { Text(verbatim: error ?? "") }
        .overlay { if busy { ProgressView().controlSize(.large) } }
    }
}
