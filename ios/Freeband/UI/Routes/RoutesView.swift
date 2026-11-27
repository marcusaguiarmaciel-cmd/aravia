import CoreLocation
import SwiftData
import SwiftUI
import UniformTypeIdentifiers

/// Every workout GPS track the watch has sent or imported (HealthKit / GPX), newest first.
struct RoutesList: View {
    @Query(filter: #Predicate<ActivityBlob> { $0.type == 1 && $0.detailType == 2 }, sort: \ActivityBlob.ts, order: .reverse)
    private var tracks: [ActivityBlob]
    @StateObject private var ext = ExternalHealthStore.shared
    @State private var importingGpx = false
    @State private var importMessage: String?

    var body: some View {
        SelectedBand { band in
            let mine = tracks.filter { band == nil || $0.bandID == band?.id }
            List {
                Section {
                    PrivacyZoneRow()
                }
                .listRowBackground(Color.surface)

                if let importMessage {
                    Section {
                        Text(verbatim: importMessage)
                            .font(.footnote)
                            .foregroundStyle(Color.textSecondary)
                    }
                    .listRowBackground(Color.surface)
                }

                if mine.isEmpty && ext.routes.isEmpty {
                    Section {
                        Text(verbatim: "Nenhuma rota ainda. Grave um treino ao ar livre com GPS no relógio, sincronize pelo Apple Saúde ou importe um arquivo .gpx acima.")
                            .foregroundStyle(.secondary)
                    }
                    .listRowBackground(Color.surface)
                } else {
                    if !ext.routes.isEmpty {
                        Section {
                            ForEach(ext.routes) { route in
                                NavigationLink {
                                    RouteReplayView(externalRoute: route)
                                } label: {
                                    ExternalRouteRow(route: route)
                                }
                                .listRowBackground(Color.surface)
                            }
                            .onDelete { offsets in
                                for idx in offsets { ext.deleteRoute(id: ext.routes[idx].id) }
                            }
                        } header: { Text(verbatim: "Apple Saúde e GPX") }
                    }
                    if !mine.isEmpty {
                        Section {
                            ForEach(mine, id: \.fileID) { blob in
                                NavigationLink {
                                    RouteReplayView(blob: blob)
                                } label: {
                                    RouteRow(blob: blob)
                                }
                                .listRowBackground(Color.surface)
                            }
                        } header: { Text(verbatim: "Relógio") }
                    }
                }
            }
            .fbScreen()
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { importingGpx = true } label: {
                        Label { Text(verbatim: "Importar GPX") } icon: { Image(systemName: "square.and.arrow.down") }
                    }
                }
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
                        importMessage = "Não foi possível ler pontos GPS válidos desse arquivo .gpx."
                    }
                case .failure(let err):
                    importMessage = "Erro ao abrir arquivo: \(err.localizedDescription)"
                }
            }
        }
    }
}

struct ExternalRouteRow: View {
    let route: ExternalHealthStore.ExternalRoute

    var body: some View {
        let track = route.track
        HStack(spacing: 12) {
            Image(systemName: "figure.run")
                .font(.title2)
                .frame(width: 36)
                .foregroundStyle(Color.action)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: route.title).font(.headline)
                Text(verbatim: "\(route.date.formatted(date: .abbreviated, time: .shortened)) · \(route.sourceName)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            VStack(alignment: .trailing, spacing: 2) {
                Text(verbatim: RouteNames.km(track.distanceMeters)).font(.headline.monospacedDigit())
                Text(verbatim: RouteNames.clock(track.duration)).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

enum RouteNames {
    static func sport(_ subtype: Int) -> String {
        guard let sport = ActivityFileID.Sport(rawValue: UInt8(clamping: subtype)) else { return "Treino" }
        return switch sport {
        case .outdoorRunning: "Corrida ao ar livre"
        case .outdoorWalkingV1, .outdoorWalkingV2: "Caminhada ao ar livre"
        case .treadmill: "Esteira"
        case .outdoorCycling, .outdoorCyclingV2: "Pedalada ao ar livre"
        case .indoorCycling: "Bicicleta indoor"
        case .freestyle: "Treino livre"
        case .poolSwimming: "Natação"
        default: "Treino"
        }
    }

    static func icon(_ subtype: Int) -> String {
        guard let sport = ActivityFileID.Sport(rawValue: UInt8(clamping: subtype)) else { return "figure.walk" }
        return switch sport {
        case .outdoorRunning: "figure.run"
        case .outdoorCycling, .outdoorCyclingV2: "figure.outdoor.cycle"
        default: "figure.walk"
        }
    }

    static func km(_ meters: Double) -> String {
        String(format: "%.2f km", meters / 1000).replacingOccurrences(of: ".", with: ",")
    }

    static func clock(_ seconds: TimeInterval) -> String {
        let s = max(0, Int(seconds))
        return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
    }
}

struct RouteRow: View {
    let blob: ActivityBlob

    var body: some View {
        let track = GpsTrack.parse(blob.data, version: blob.version)
        HStack(spacing: 12) {
            Image(systemName: RouteNames.icon(blob.subtype))
                .font(.title2)
                .frame(width: 36)
                .foregroundStyle(Color.action)
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: RouteNames.sport(blob.subtype)).font(.headline)
                Text(blob.ts, format: .dateTime.day().month(.abbreviated).hour().minute())
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if let track {
                VStack(alignment: .trailing, spacing: 2) {
                    Text(verbatim: RouteNames.km(track.distanceMeters)).font(.headline.monospacedDigit())
                    Text(verbatim: RouteNames.clock(track.duration)).font(.subheadline.monospacedDigit()).foregroundStyle(.secondary)
                }
            } else {
                Text(verbatim: "v\(blob.version) não suportada").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}


/// A run of consecutive fixes drawn in one speed colour.
struct RouteChunk: Identifiable {
    let id: Int
    let range: ClosedRange<Int>
    let paletteIndex: Int
}

/// The workout replayed on a 3D map: a camera that follows the route, the path so far coloured
/// by speed, play, pause, speed and a time scrubber. "Story" opens the video & instant overlay Hub.
struct RouteReplayView: View {
    let blob: ActivityBlob?
    let externalRoute: ExternalHealthStore.ExternalRoute?

    init(blob: ActivityBlob) {
        self.blob = blob
        self.externalRoute = nil
    }

    init(externalRoute: ExternalHealthStore.ExternalRoute) {
        self.blob = nil
        self.externalRoute = externalRoute
    }

    @StateObject private var privacy = RoutePrivacy.shared
    @State private var track: GpsTrack?
    @State private var chunks: [RouteChunk] = []
    @State private var elapsed: TimeInterval = 0
    @State private var playing = false
    @State private var rate: Double = 30
    @State private var heading: Double?
    @State private var overviewToken = 0
    @State private var gpxURL: URL?
    @State private var showsStory = false

    private var following: Bool { heading != nil }
    private var title: String {
        if let externalRoute { return externalRoute.title }
        if let blob { return RouteNames.sport(blob.subtype) }
        return "Rota"
    }
    private var routeDate: Date { externalRoute?.date ?? blob?.ts ?? Date() }
    private var routeKey: String { externalRoute?.id ?? blob?.fileID ?? "route" }

    var body: some View {
        Group {
            if let track {
                ZStack(alignment: .bottom) {
                    RouteMap3D(track: track, chunks: chunks, elapsed: elapsed, followHeading: heading,
                               overviewToken: overviewToken)
                        .ignoresSafeArea(edges: .bottom)
                    controls(track)
                        .padding(12)
                }
            } else {
                ContentUnavailableView {
                    Label { Text(verbatim: "Rota ilegível") } icon: { Image(systemName: "map") }
                } description: {
                    Text(verbatim: "Este arquivo de GPS ainda não é suportado.")
                }
            }
        }
        .navigationTitle(Text(verbatim: title))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if track != nil {
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        playing = false
                        showsStory = true
                    } label: {
                        Label { Text(verbatim: "Story") } icon: { Image(systemName: "video.badge.waveform") }
                    }
                }
            }
            if let track, let start = track.start, blob != nil {
                ToolbarItem(placement: .topBarTrailing) { StravaSendButton(start: start, compact: true) }
            }
            if let gpxURL {
                ToolbarItem(placement: .topBarTrailing) {
                    ShareLink(item: gpxURL) { Label { Text(verbatim: "Exportar GPX") } icon: { Image(systemName: "square.and.arrow.up") } }
                }
            }
        }
        .fullScreenCover(isPresented: $showsStory) {
            if let track {
                StoryHubView(track: track, chunks: chunks, title: title, date: routeDate)
            }
        }
        .task { load() }
        .onChange(of: privacy.meters) { _, _ in rebuildExportGpx() }
        .task(id: playing) { await play() }
        .onDisappear { playing = false }
    }

    // MARK: controls

    @ViewBuilder private func controls(_ track: GpsTrack) -> some View {
        let now = track.position(at: elapsed)
        VStack(spacing: 10) {
            HStack {
                stat(RouteNames.clock(elapsed), "tempo")
                stat(RouteNames.km(now.distance), "distância")
                stat(String(format: "%.1f", now.speedKmh).replacingOccurrences(of: ".", with: ","), "km/h")
                stat((track.start ?? .now).addingTimeInterval(elapsed).formatted(date: .omitted, time: .shortened), "horário")
            }
            Slider(value: Binding(get: { elapsed }, set: { elapsed = $0; if following { follow(track, smooth: false) } }),
                   in: 0...max(track.duration, 1))
                .tint(Color.action)
            HStack(spacing: 8) {
                Button {
                    if elapsed >= track.duration { elapsed = 0 }
                    if !playing && !following { follow(track, smooth: false) }
                    playing.toggle()
                } label: {
                    Label { Text(verbatim: playing ? "Pausar" : (elapsed >= track.duration ? "De novo" : "Play")) }
                          icon: { Image(systemName: playing ? "pause.fill" : "play.fill") }
                        .frame(minWidth: 96)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.action)
                Picker(selection: $rate) {
                    Text(verbatim: "10×").tag(10.0)
                    Text(verbatim: "30×").tag(30.0)
                    Text(verbatim: "60×").tag(60.0)
                } label: { Text(verbatim: "Velocidade") }
                .pickerStyle(.segmented)
                Button {
                    if following {
                        heading = nil
                        overviewToken += 1
                    } else {
                        follow(track, smooth: false)
                    }
                } label: {
                    Image(systemName: following ? "map" : "location.north.line")
                }
                .buttonStyle(.bordered)
                .accessibilityLabel(Text(verbatim: following ? "Visão geral" : "Seguir"))
            }
        }
        .padding(14)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(verbatim: value).font(.title3.weight(.semibold).monospacedDigit()).lineLimit(1).minimumScaleFactor(0.7)
            Text(verbatim: label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: playback

    private func load() {
        guard track == nil else { return }
        let parsed: GpsTrack?
        if let externalRoute {
            parsed = externalRoute.track
        } else if let blob {
            parsed = GpsTrack.parse(blob.data, version: blob.version)
        } else {
            parsed = nil
        }
        guard let parsed else { return }
        track = parsed
        chunks = Self.chunks(parsed)
        rebuildExportGpx()
    }

    private func rebuildExportGpx() {
        guard let track else { return }
        let safe = privacy.safeTrack(for: track)
        let name = "\(title) \(routeDate.formatted(date: .numeric, time: .shortened))"
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("rota-\(routeKey).gpx")
        if (try? safe.gpx(name: name).write(to: file, atomically: true, encoding: .utf8)) != nil { gpxURL = file }
    }

    private func play() async {
        guard playing, let track else { return }
        var last = Date()
        while playing && !Task.isCancelled {
            try? await Task.sleep(for: .milliseconds(33))
            let now = Date()
            elapsed = min(track.duration, elapsed + now.timeIntervalSince(last) * rate)
            last = now
            if following { follow(track, smooth: true) }
            if elapsed >= track.duration { playing = false }
        }
    }

    private func follow(_ track: GpsTrack, smooth: Bool) {
        heading = Self.nextHeading(track, elapsed: elapsed, current: smooth ? heading : nil)
    }

    /// The camera looks a few fixes ahead and turns smoothly at corners.
    static func nextHeading(_ track: GpsTrack, elapsed: TimeInterval, current: Double?) -> Double {
        let now = track.position(at: elapsed)
        let here = track.points[now.index]
        let ahead = track.points[min(now.index + 12, track.points.count - 1)]
        let target = GpsTrack.meters(here, ahead) > 3 ? GpsTrack.bearing(here, ahead) : (current ?? 0)
        guard let h = current else { return target }
        let diff = (target - h + 540).truncatingRemainder(dividingBy: 360) - 180
        return (h + diff * 0.06 + 360).truncatingRemainder(dividingBy: 360)
    }

    static func chunks(_ track: GpsTrack) -> [RouteChunk] {
        let pts = track.points
        guard pts.count > 1 else { return [] }
        var out: [RouteChunk] = []
        var start = 0
        var palette = RouteChunkPalette.index(pts[1].speedKmh)
        for i in 2..<pts.count {
            let p = RouteChunkPalette.index(pts[i].speedKmh)
            if p != palette {
                out.append(RouteChunk(id: out.count, range: start...(i - 1), paletteIndex: palette))
                start = i - 1
                palette = p
            }
        }
        out.append(RouteChunk(id: out.count, range: start...(pts.count - 1), paletteIndex: palette))
        return out
    }
}
