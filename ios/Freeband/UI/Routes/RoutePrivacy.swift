import SwiftUI

/// Automatic home-privacy zone for routes shared outside the phone (Story videos, transparent
/// overlays, exported GPX files and Strava uploads). The complete track always remains untouched
/// on the device.
@MainActor
final class RoutePrivacy: ObservableObject {
    static let shared = RoutePrivacy()
    static let defaultMeters = 500
    static let options = [0, 200, 500, 1000]
    private static let key = "aravia.routePrivacyMeters"

    enum Outcome {
        case ready(track: GpsTrack, trimmedMeters: Int)
        case tooShort(original: GpsTrack, radiusMeters: Int)
    }

    @Published var meters: Int {
        didSet {
            let clean = Self.options.contains(meters) ? meters : Self.defaultMeters
            if clean != meters { meters = clean; return }
            UserDefaults.standard.set(clean, forKey: Self.key)
        }
    }

    private init() {
        if UserDefaults.standard.object(forKey: Self.key) != nil {
            let saved = UserDefaults.standard.integer(forKey: Self.key)
            meters = Self.options.contains(saved) ? saved : Self.defaultMeters
        } else {
            meters = Self.defaultMeters
        }
    }

    static func label(_ radiusMeters: Int) -> String {
        switch radiusMeters {
        case 0: "Desligado"
        case 1000: "1 km"
        default: "\(radiusMeters) m"
        }
    }

    func apply(to track: GpsTrack, radiusMeters: Int? = nil) -> Outcome {
        let r = radiusMeters ?? meters
        guard r > 0 else { return .ready(track: track, trimmedMeters: 0) }
        let trimmed = track.trimmedForPrivacy(radiusMeters: Double(r))
        return trimmed.points.count >= 2
            ? .ready(track: trimmed, trimmedMeters: r)
            : .tooShort(original: track, radiusMeters: r)
    }

    /// Returns the privacy-trimmed track when long enough, otherwise the original track.
    func safeTrack(for track: GpsTrack) -> GpsTrack {
        switch apply(to: track) {
        case .ready(let t, _): t
        case .tooShort(let original, _): original
        }
    }
}

struct PrivacyZoneRow: View {
    var compact: Bool = false
    @StateObject private var privacy = RoutePrivacy.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(verbatim: privacy.meters > 0
                 ? "Proteção da casa: ocultar \(RoutePrivacy.label(privacy.meters)) no início e no fim"
                 : "Proteção da casa: desligada (rota inteira ao compartilhar)")
                .font(compact ? .footnote.weight(.semibold) : .subheadline.weight(.semibold))
                .foregroundStyle(Color.textPrimary)
            if !compact {
                Text(verbatim: "A rota completa continua salva aqui; só o trecho compartilhado (Story, GPX e Strava) é recortado.")
                    .font(.caption)
                    .foregroundStyle(Color.textSecondary)
            }
            HStack(spacing: 8) {
                ForEach(RoutePrivacy.options, id: \.self) { m in
                    let selected = privacy.meters == m
                    Button {
                        privacy.meters = m
                    } label: {
                        Text(verbatim: RoutePrivacy.label(m))
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(selected ? .white : Color.textPrimary)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 7)
                            .background(selected ? Color.action : Color.surface, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}
