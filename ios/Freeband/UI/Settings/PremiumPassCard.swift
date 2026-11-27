import SwiftUI

/// Opt-in 24h Premium Pass card ("Assista 2 anúncios -> 24h Premium", zero in-app purchases).
/// Shown on the Band tab (and compactly inside StoryHub when the watermark is visible).
/// Paid supporters (`account.hasEarlyAccess`) see zero ads.
struct PremiumPassCard: View {
    var compact: Bool = false
    var onOpenTools: () -> Void = {}

    @StateObject private var account = Account.shared
    @StateObject private var rewarded = RewardedPremium.shared
    @State private var now = Date()

    var body: some View {
        if !account.hasEarlyAccess {
            let active = rewarded.isAdPassActive
            let watched = rewarded.adsWatched
            let nextIndex = min(watched + 1, RewardedPremium.requiredAds)

            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 8) {
                    Text(verbatim: active ? "Aravia Premium ativo" : "Premium grátis por 24h")
                        .font(compact ? .headline : .title3.weight(.semibold))
                        .foregroundStyle(Color.textPrimary)
                    Spacer()
                    Text(verbatim: active ? rewarded.remainingLabel(now: now) : "\(watched)/\(RewardedPremium.requiredAds) anúncios")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(active ? Color.good : Color.action)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 3)
                        .background((active ? Color.good : Color.action).opacity(0.16), in: Capsule())
                }

                if active {
                    Text(verbatim: "Todos os recursos Premium estão liberados por 24h (Stories sem marca d'água, Laboratório e prévias). Sem compras no app.")
                        .font(.footnote)
                        .foregroundStyle(Color.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Button(action: onOpenTools) {
                        Text(verbatim: "Abrir ferramentas Premium")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity, minHeight: 42)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.action)
                } else {
                    Text(verbatim: compact
                         ? "Assista 2 anúncios curtos opcionais para remover a marca d'água e liberar todo o Premium por 24h (sem compras no app)."
                         : "Por enquanto não há compras no app: assista 2 anúncios curtos opcionais para liberar Stories sem marca d'água, Laboratório e recursos Premium por 24 horas. Nenhum dado de saúde sai do seu celular.")
                        .font(.footnote)
                        .foregroundStyle(Color.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)

                    HStack(spacing: 8) {
                        ForEach(0 ..< RewardedPremium.requiredAds, id: \.self) { idx in
                            let done = idx < watched
                            Text(verbatim: done ? "✓ Anúncio \(idx + 1)" : "Anúncio \(idx + 1)")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(done ? Color.good : Color.textSecondary)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 7)
                                .background(done ? Color.good.opacity(0.18) : Color.canvas, in: RoundedRectangle(cornerRadius: 10))
                        }
                    }

                    if let msg = rewarded.statusMessage {
                        Text(verbatim: msg)
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(Color.good)
                    }
                    if let err = rewarded.error {
                        Text(verbatim: err)
                            .font(.footnote)
                            .foregroundStyle(Color.low)
                    }

                    Button {
                        rewarded.watchNextAd()
                    } label: {
                        HStack(spacing: 8) {
                            if rewarded.loadingAd {
                                ProgressView().tint(.white)
                                Text(verbatim: "Carregando anúncio…").font(.subheadline.weight(.semibold))
                            } else {
                                Text(verbatim: "Assistir anúncio (\(nextIndex)/\(RewardedPremium.requiredAds)) · liberar 24h")
                                    .font(.subheadline.weight(.semibold))
                            }
                        }
                        .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.action)
                    .disabled(rewarded.loadingAd || rewarded.showingAd)

                    if RewardedPremium.usingTestIDs {
                        Text(verbatim: "Anúncio de teste oficial do Google AdMob ativo durante o beta.")
                            .font(.caption2)
                            .foregroundStyle(Color.textSecondary)
                    }
                }
            }
            .padding(compact ? 14 : 16)
            .background(Color.surface, in: RoundedRectangle(cornerRadius: compact ? 16 : 20, style: .continuous))
            .task(id: rewarded.passExpiresAt) {
                rewarded.refreshExpiry()
                while rewarded.isAdPassActive && !Task.isCancelled {
                    try? await Task.sleep(for: .seconds(30))
                    now = Date()
                    rewarded.refreshExpiry()
                }
            }
        }
    }
}
