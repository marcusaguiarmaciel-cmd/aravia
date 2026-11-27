import SwiftUI

/// Dedicated Early Access / Laboratório panel unlocked for supporters OR users with an active
/// 24h ad-unlocked Premium pass.
struct EarlyAccessSheet: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var account = Account.shared
    @StateObject private var supporterAccess = SupporterAccess.shared
    @StateObject private var rewarded = RewardedPremium.shared
    @State private var syncing = false
    @State private var statusMessage: String?

    var body: some View {
        let sup = account.supporter
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    HStack(spacing: 8) {
                        Text(verbatim: sup != nil ? "Acesso Antecipado" : "Aravia Premium (24h)")
                            .font(.title2.weight(.bold))
                            .foregroundStyle(Color.textPrimary)
                        Spacer()
                        Text(verbatim: badgeLabel(sup))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(Color.good)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 4)
                            .background(Color.good.opacity(0.16), in: Capsule())
                    }

                    if let sup {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(verbatim: sup.tierName)
                                .font(.headline)
                                .foregroundStyle(Color.textPrimary)
                            Text(verbatim: "Salvo na conta @\(account.username ?? "—") · funciona em qualquer celular em que você fizer login.")
                                .font(.footnote)
                                .foregroundStyle(Color.textSecondary)
                            Text(verbatim: validityText(sup))
                                .font(.footnote)
                                .foregroundStyle(Color.textSecondary)
                            if let masked = sup.codeMasked {
                                Text(verbatim: "Código vinculado: \(masked)")
                                    .font(.caption)
                                    .foregroundStyle(Color.textSecondary)
                            }
                            if sup.founderBadge {
                                Text(verbatim: "Selo de Beta-Tester ativo · seu @usuario entra nos créditos do Aravia")
                                    .font(.caption.weight(.medium))
                                    .foregroundStyle(Color.action)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 6)
                                    .background(Color.action.opacity(0.14), in: RoundedRectangle(cornerRadius: 8))
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .background(Color.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    } else {
                        VStack(alignment: .leading, spacing: 6) {
                            Text(verbatim: "Passe Premium de 24 horas")
                                .font(.headline)
                                .foregroundStyle(Color.textPrimary)
                            Text(verbatim: "Liberado gratuitamente assistindo 2 anúncios opcionais · \(rewarded.remainingLabel()). Sem compras no app.")
                                .font(.footnote)
                                .foregroundStyle(Color.textSecondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(14)
                        .background(Color.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }

                    VStack(alignment: .leading, spacing: 12) {
                        Text(verbatim: "Ferramentas Premium (Laboratório)")
                            .font(.headline)
                            .foregroundStyle(Color.textPrimary)

                        labToggle(
                            title: "Stories sem marca d'água",
                            subtitle: "Remove o rodapé \"Feito no Aravia\" nos overlays transparentes e fotos dos Stories.",
                            isOn: $supporterAccess.lab.hideStoryWatermark
                        )
                        Divider()
                        labToggle(
                            title: "Canal de recursos em prévia",
                            subtitle: "Ativa automaticamente novas ferramentas experimentais assim que chegam em cada atualização.",
                            isOn: $supporterAccess.lab.earlyChannel
                        )
                        Divider()
                        labToggle(
                            title: "Métricas experimentais de treino",
                            subtitle: "Habilita prévias das próximas métricas avançadas de corrida e carga de treino.",
                            isOn: $supporterAccess.lab.experimentalMetrics
                        )
                    }
                    .padding(14)
                    .background(Color.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))

                    if let rawUrl = sup?.earlyUrl, let url = URL(string: rawUrl) {
                        Link(destination: url) {
                            Text(verbatim: "Abrir canal de builds antecipadas")
                                .font(.headline)
                                .frame(maxWidth: .infinity, minHeight: 46)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Color.action)
                    }

                    if sup != nil {
                        Button {
                            guard !syncing else { return }
                            syncing = true
                            statusMessage = nil
                            Task {
                                defer { syncing = false }
                                do {
                                    let updated = try await account.syncSupporterNow()
                                    statusMessage = updated.map { "Conta sincronizada: \($0.tierName)." } ?? "Sua faixa atual continua ativa."
                                } catch {
                                    statusMessage = error.localizedDescription
                                }
                            }
                        } label: {
                            Text(verbatim: syncing ? "Sincronizando…" : "Verificar upgrade de faixa")
                                .frame(maxWidth: .infinity, minHeight: 44)
                        }
                        .buttonStyle(.bordered)
                        .tint(Color.action)
                        .disabled(syncing)
                    }

                    if let statusMessage {
                        Text(verbatim: statusMessage)
                            .font(.footnote)
                            .foregroundStyle(Color.textSecondary)
                    }
                }
                .padding(18)
                .fbScrollContent()
            }
            .background(Color.canvas.ignoresSafeArea())
            .navigationTitle(Text(verbatim: "Laboratório"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button { dismiss() } label: { Text(verbatim: "OK") }
                }
            }
        }
        .tint(Color.action)
    }

    private func badgeLabel(_ sup: SupporterAccess.Entitlement?) -> String {
        if sup?.lifetime == true { return "Vitalício" }
        if sup != nil { return "Ativo" }
        return rewarded.remainingLabel()
    }

    private func validityText(_ sup: SupporterAccess.Entitlement) -> String {
        if sup.lifetime { return "Acesso vitalício garantido na sua conta." }
        if let until = sup.validUntil, until.count >= 10 {
            let label = sup.premiumMonths == 1 ? "mês" : "meses"
            return "Válido até \(until.prefix(10)) (\(sup.premiumMonths) \(label))."
        }
        return "Benefício vinculado permanentemente à sua conta."
    }

    private func labToggle(title: String, subtitle: String, isOn: Binding<Bool>) -> some View {
        Toggle(isOn: isOn) {
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: title).font(.subheadline.weight(.medium)).foregroundStyle(Color.textPrimary)
                Text(verbatim: subtitle).font(.caption).foregroundStyle(Color.textSecondary)
            }
        }
        .tint(Color.action)
    }
}

/// Lets a signed-in user redeem their unique supporter code (`ARV1`/`ARV3`/`ARV12`/`ARVV-...`) or
/// auto-sync an approved support made with their `@usuario`.
struct RedeemSupporterSheet: View {
    var onUnlocked: () -> Void = {}

    @Environment(\.dismiss) private var dismiss
    @StateObject private var account = Account.shared
    @State private var code = ""
    @State private var busy = false
    @State private var error: String?
    @State private var info: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(verbatim: "Ativar Acesso Antecipado")
                        .font(.title2.weight(.bold))
                        .foregroundStyle(Color.textPrimary)
                    Text(verbatim: "Cole o código gerado após o seu apoio em aravia.app (faixas Filhote, Jovem, Adulta ou Arara-real). Ele fica salvo na sua conta @\(account.username ?? "—") para sempre.")
                        .font(.subheadline)
                        .foregroundStyle(Color.textSecondary)

                    TextField(text: $code) { Text(verbatim: "ARV3-XXXX-XXXX-XXXX-XXXX") }
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                        .font(.body.monospaced())
                        .padding(12)
                        .background(Color.surface, in: RoundedRectangle(cornerRadius: 12))
                        .onChange(of: code) { _, new in
                            let clean = String(new.uppercased().prefix(32))
                            if clean != new { code = clean }
                            error = nil
                            info = nil
                        }

                    Button {
                        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
                        guard !trimmed.isEmpty else {
                            error = "Cole o seu código de apoiador acima."
                            return
                        }
                        busy = true; error = nil; info = nil
                        Task {
                            defer { busy = false }
                            do {
                                _ = try await account.redeemSupporterCode(trimmed)
                                onUnlocked()
                                dismiss()
                            } catch {
                                self.error = error.localizedDescription
                            }
                        }
                    } label: {
                        Text(verbatim: busy ? "Validando…" : "Ativar na minha conta")
                            .font(.headline)
                            .frame(maxWidth: .infinity, minHeight: 48)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.action)
                    .disabled(busy)

                    Button {
                        busy = true; error = nil; info = nil
                        Task {
                            defer { busy = false }
                            do {
                                if let ent = try await account.syncSupporterNow(), ent.earlyAccess {
                                    onUnlocked()
                                    dismiss()
                                } else {
                                    info = "Nenhum apoio aprovado encontrado automaticamente para @\(account.username ?? "—"). Se você não digitou seu @usuario na hora do pagamento, cole o código acima."
                                }
                            } catch {
                                self.error = error.localizedDescription
                            }
                        }
                    } label: {
                        Text(verbatim: "Já apoiei com @\(account.username ?? "minha conta") · verificar")
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                    .tint(Color.action)
                    .disabled(busy)

                    if let error {
                        Text(verbatim: error).font(.footnote).foregroundStyle(Color.low)
                    }
                    if let info {
                        Text(verbatim: info).font(.footnote).foregroundStyle(Color.textSecondary)
                    }
                }
                .padding(18)
            }
            .background(Color.canvas.ignoresSafeArea())
            .navigationTitle(Text(verbatim: "Código de apoiador"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Text(verbatim: "Fechar") }
                }
            }
        }
        .tint(Color.action)
    }
}
