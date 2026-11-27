import SwiftData
import SwiftUI

/// Universal watch connection card via Apple HealthKit (Apple Watch, Garmin, Polar, Suunto,
/// Amazfit/Zepp, Xiaomi/Mi Fitness, Oura, Whoop, etc.).
/// 100% on-device read-only; no proprietary pairing keys or watch resets needed.
struct HealthKitCard: View {
    var compact: Bool = false
    var onConnected: () -> Void = {}

    @Environment(\.modelContext) private var context
    @EnvironmentObject private var model: AppModel
    @StateObject private var hk = HealthKitBridge.shared
    @StateObject private var ext = ExternalHealthStore.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text(verbatim: "Qualquer relógio (Apple Saúde)")
                    .font(compact ? .headline : .title3.weight(.semibold))
                    .foregroundStyle(Color.textPrimary)
                Spacer()
                if hk.connected {
                    Text(verbatim: "Conectado")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Color.good)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 3)
                        .background(Color.good.opacity(0.16), in: Capsule())
                }
            }

            Text(verbatim: "Funciona com Apple Watch, Garmin, Polar, Suunto, Amazfit/Zepp, Xiaomi/Mi Fitness, Oura e Whoop — sem chave e sem redefinir o relógio.")
                .font(.footnote)
                .foregroundStyle(Color.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            if !ext.detectedSources.isEmpty {
                Text(verbatim: "Fontes detectadas: \(ext.detectedSources.joined(separator: " · "))")
                    .font(.footnote.weight(.medium))
                    .foregroundStyle(Color.textPrimary)
            }

            if let ts = ext.lastHealthKitSync {
                let extra = hk.lastMessage.map { " · \($0)" } ?? ""
                Text(verbatim: "Última leitura: \(ts.formatted(date: .abbreviated, time: .shortened))\(extra)")
                    .font(.caption)
                    .foregroundStyle(Color.textSecondary)
            }

            if hk.syncing {
                ProgressView()
                    .tint(Color.action)
            }

            if let err = hk.error {
                Text(verbatim: err)
                    .font(.footnote)
                    .foregroundStyle(Color.low)
                    .fixedSize(horizontal: false, vertical: true)
            }

            switch hk.availability {
            case .available:
                if !hk.connected {
                    Button {
                        Task {
                            await hk.requestAndSync(context: context, model: model)
                            if hk.connected { onConnected() }
                        }
                    } label: {
                        Text(verbatim: hk.syncing ? "Sincronizando…" : "Conectar via Apple Saúde")
                            .font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color.action)
                    .disabled(hk.syncing)
                } else {
                    HStack(spacing: 8) {
                        Button {
                            Task { await hk.sync(context: context, model: model) }
                        } label: {
                            Text(verbatim: hk.syncing ? "Sincronizando…" : "Sincronizar agora")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity, minHeight: 42)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Color.action)
                        .disabled(hk.syncing)

                        Button {
                            BandPresentation.openSystemSettings()
                        } label: {
                            Text(verbatim: "Permissões")
                                .font(.subheadline.weight(.medium))
                                .frame(minHeight: 42)
                        }
                        .buttonStyle(.bordered)
                        .tint(Color.action)
                        .disabled(hk.syncing)
                    }
                }
            case .missingEntitlement:
                Text(verbatim: "A integração direta com o Apple Saúde entra na versão da App Store. Enquanto isso, você já pode usar cintas cardíacas Bluetooth, conexão direta Xiaomi/Redmi ou importar rotas na aba Treinar.")
                    .font(.caption)
                    .foregroundStyle(Color.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            case .unavailable:
                Text(verbatim: "O Apple Saúde não está disponível neste aparelho. Você ainda pode usar cintas cardíacas Bluetooth, conexão direta Xiaomi/Redmi ou importar rotas na aba Treinar.")
                    .font(.caption)
                    .foregroundStyle(Color.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}

/// Universal Bluetooth SIG Heart Rate sensor card (`0x180D` / `0x2A37`):
/// Connects in 1 tap to any BLE chest strap (Polar H10/H9, Garmin HRM, Coospo, Magene, Wahoo)
/// or smartwatch broadcasting HR over BLE — feeds WorkoutController live & supports 60s Spot HRV.
struct StandardHrCard: View {
    @StateObject private var hr = StandardHrLink.shared
    @State private var expanded = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 8) {
                Text(verbatim: "Cinta cardíaca ou sensor Bluetooth")
                    .font(.headline)
                    .foregroundStyle(Color.textPrimary)
                Spacer()
                if hr.status == .connected {
                    Text(verbatim: hr.bpm.map { "\($0) bpm" } ?? "Ao vivo")
                        .font(.caption.weight(.bold).monospacedDigit())
                        .foregroundStyle(Color.good)
                        .padding(.horizontal, 9)
                        .padding(.vertical, 3)
                        .background(Color.good.opacity(0.16), in: Capsule())
                }
            }

            Text(verbatim: "Polar, Garmin HRM, Coospo, Magene, Wahoo ou qualquer relógio que transmita batimentos por Bluetooth ao vivo na academia ou corrida.")
                .font(.footnote)
                .foregroundStyle(Color.textSecondary)
                .fixedSize(horizontal: false, vertical: true)

            if hr.status == .connected {
                let meta = [
                    hr.deviceName,
                    hr.battery.map { "bateria \($0)%" },
                    hr.hasRr ? "R-R (HRV) ativo" : nil,
                ].compactMap { $0 }.joined(separator: " · ")
                if !meta.isEmpty {
                    Text(verbatim: meta)
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(Color.textPrimary)
                }

                if hr.spotHrvRunning {
                    Text(verbatim: "Medindo HRV em repouso… \(hr.spotHrvElapsedSec)s / 60s (fique parado e respirando normalmente)")
                        .font(.footnote)
                        .foregroundStyle(Color.action)
                    ProgressView(value: min(Double(hr.spotHrvElapsedSec) / 60.0, 1.0))
                        .tint(Color.action)
                } else if let ms = hr.spotHrvRmssdMs {
                    Text(verbatim: "HRV registrado hoje: \(Int(ms.rounded())) ms (RMSSD) — já salvo para sua Recuperação.")
                        .font(.footnote.weight(.medium))
                        .foregroundStyle(Color.good)
                }

                HStack(spacing: 8) {
                    if hr.hasRr {
                        Button {
                            if hr.spotHrvRunning { hr.cancelSpotHrv() } else { hr.startSpotHrv() }
                        } label: {
                            Text(verbatim: hr.spotHrvRunning ? "Parar HRV" : "Medir HRV (60 s)")
                                .font(.subheadline.weight(.semibold))
                                .frame(maxWidth: .infinity, minHeight: 42)
                        }
                        .buttonStyle(.borderedProminent)
                        .tint(Color.action)
                    }
                    Button {
                        hr.disconnect()
                    } label: {
                        Text(verbatim: "Desconectar")
                            .font(.subheadline.weight(.medium))
                            .frame(maxWidth: hr.hasRr ? nil : .infinity, minHeight: 42)
                    }
                    .buttonStyle(.bordered)
                    .tint(Color.action)
                }
            } else {
                Button {
                    expanded = true
                    if hr.status == .scanning { hr.stopScan() } else { hr.startScan() }
                } label: {
                    Text(verbatim: hr.status == .scanning ? "Parar busca de sensores" : "Procurar cinta ou sensor cardíaco")
                        .font(.subheadline.weight(.medium))
                        .frame(maxWidth: .infinity, minHeight: 42)
                }
                .buttonStyle(.bordered)
                .tint(Color.action)

                if hr.status == .scanning || hr.status == .connecting {
                    HStack(spacing: 8) {
                        ProgressView().tint(Color.action)
                        Text(verbatim: hr.status.label)
                            .font(.footnote)
                            .foregroundStyle(Color.textSecondary)
                    }
                }

                if let err = hr.error {
                    Text(verbatim: err)
                        .font(.footnote)
                        .foregroundStyle(Color.low)
                }

                if expanded && !hr.devices.isEmpty {
                    VStack(spacing: 8) {
                        ForEach(hr.devices) { d in
                            HStack {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(verbatim: d.name)
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundStyle(Color.textPrimary)
                                    let sub = [
                                        d.alreadyConnected ? "Conectado ao iPhone" : nil,
                                        d.rssi.map { "\($0) dBm" },
                                    ].compactMap { $0 }.joined(separator: " · ")
                                    if !sub.isEmpty {
                                        Text(verbatim: sub)
                                            .font(.caption)
                                            .foregroundStyle(Color.textSecondary)
                                    }
                                }
                                Spacer()
                                Button {
                                    hr.connect(d.id)
                                } label: {
                                    Text(verbatim: "Usar")
                                        .font(.subheadline.weight(.semibold))
                                }
                                .buttonStyle(.borderedProminent)
                                .tint(Color.action)
                            }
                            .padding(10)
                            .background(Color.canvas, in: RoundedRectangle(cornerRadius: 12))
                        }
                    }
                }
            }
        }
        .padding(16)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}
