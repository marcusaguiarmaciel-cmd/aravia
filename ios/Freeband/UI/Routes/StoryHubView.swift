import PhotosUI
import SwiftUI

/// The Story Hub: instant transparent PNG / photo overlays (< 200 ms) AND 3D animated map videos,
/// with automatic home Privacy Zone and optional watermark removal for Premium (supporters or
/// active 24h ad-unlocked pass).
struct StoryHubView: View {
    enum Mode: String, CaseIterable, Identifiable {
        case overlay = "Foto / Overlay"
        case video = "Vídeo 3D"
        var id: String { rawValue }
    }

    let track: GpsTrack
    let chunks: [RouteChunk]
    let title: String
    let date: Date

    @Environment(\.dismiss) private var dismiss
    @StateObject private var store = StoryTemplateStore.shared
    @StateObject private var privacy = RoutePrivacy.shared
    @StateObject private var supporterAccess = SupporterAccess.shared
    @StateObject private var rewarded = RewardedPremium.shared
    @StateObject private var account = Account.shared
    @StateObject private var nest = AraraNest.shared
    @State private var mode: Mode = .overlay
    @State private var previews: [StoryTemplate: UIImage] = [:]
    @State private var path: [UUID] = []
    @State private var generating: StoryTemplate?
    @State private var confirmDelete: StoryTemplate?

    // Instant Overlay state
    @State private var accent: StoryOverlay.Accent = StoryOverlay.accents[0]
    @State private var pickedPhotoItem: PhotosPickerItem?
    @State private var pickedPhoto: UIImage?
    @State private var overlayImage: UIImage?
    @State private var shareFileURL: URL?
    @State private var saveStatus: String?
    @State private var showsEarlyAccess = false

    private let columns = [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)]

    private var safeTrack: GpsTrack { privacy.safeTrack(for: track) }
    private var safeChunks: [RouteChunk] { RouteReplayView.chunks(safeTrack) }
    private var araraStage: AraraStage { AraraProgress(xp: nest.totalXP(mine: nest.accountXP ?? 0)).stage }
    private var hasPremium: Bool { account.hasEarlyAccess || rewarded.isAdPassActive }
    private var showWatermark: Bool { !(hasPremium && supporterAccess.lab.hideStoryWatermark) }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Picker(selection: $mode) {
                        ForEach(Mode.allCases) { Text(verbatim: $0.rawValue).tag($0) }
                    } label: { Text(verbatim: "Formato") }
                    .pickerStyle(.segmented)

                    PrivacyZoneRow(compact: true)
                        .padding(12)
                        .background(Color.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))

                    if mode == .overlay {
                        overlaySection
                    } else {
                        videoSection
                    }
                }
                .padding(16)
                .fbScrollContent()
            }
            .background(Color.canvas.ignoresSafeArea())
            .navigationTitle(Text(verbatim: "Hub de Story"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Text(verbatim: "Fechar") }
                }
                if mode == .video {
                    ToolbarItem(placement: .primaryAction) {
                        Menu {
                            Button {
                                var fresh = StoryTemplate(name: "Meu modelo")
                                fresh.id = UUID()
                                store.templates.append(fresh)
                                path.append(fresh.id)
                            } label: { Label { Text(verbatim: "Novo modelo") } icon: { Image(systemName: "plus") } }
                            Button { store.restoreBuiltIns() } label: {
                                Label { Text(verbatim: "Restaurar modelos do Aravia") } icon: { Image(systemName: "arrow.counterclockwise") }
                            }
                        } label: { Image(systemName: "plus.circle") }
                        .tint(Color.action)
                    }
                }
            }
            .navigationDestination(for: UUID.self) { id in
                StoryTemplateEditor(id: id, track: safeTrack, chunks: safeChunks, title: title, date: date) { template in
                    generating = template
                }
            }
            .task(id: "\(mode.rawValue)-\(privacy.meters)-\(store.templates.count)") {
                if mode == .video { await loadPreviews() }
            }
            .onAppear { rebuildOverlay() }
            .onChange(of: accent) { _, _ in rebuildOverlay() }
            .onChange(of: privacy.meters) { _, _ in rebuildOverlay() }
            .onChange(of: showWatermark) { _, _ in rebuildOverlay() }
            .onChange(of: pickedPhotoItem) { _, item in
                Task {
                    if let data = try? await item?.loadTransferable(type: Data.self), let img = UIImage(data: data) {
                        pickedPhoto = img
                    } else {
                        pickedPhoto = nil
                    }
                    rebuildOverlay()
                }
            }
            .fullScreenCover(item: $generating) { template in
                StoryVideoView(track: safeTrack, chunks: safeChunks, title: title, date: date, template: template)
            }
            .sheet(isPresented: $showsEarlyAccess) { EarlyAccessSheet() }
            .sheet(item: Binding(get: { shareFileURL.map { ShareItem(urls: [$0]) } }, set: { if $0 == nil { shareFileURL = nil } })) { item in
                ActivitySheet(items: item.urls).ignoresSafeArea()
            }
            .confirmationDialog(Text(verbatim: "Excluir o modelo \(confirmDelete?.name ?? "")?"),
                                isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } }),
                                titleVisibility: .visible) {
                Button(role: .destructive) {
                    if let t = confirmDelete { store.delete(t) }
                } label: { Text(verbatim: "Excluir") }
            }
        }
        .tint(Color.action)
    }

    @ViewBuilder private var overlaySection: some View {
        VStack(alignment: .leading, spacing: 12) {
            ZStack {
                // Dark checkerboard-like backing so transparent PNG reads clearly.
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(LinearGradient(colors: [Color(red: 0x16 / 255, green: 0x20 / 255, blue: 0x2B / 255),
                                                  Color(red: 0x0E / 255, green: 0x15 / 255, blue: 0x1C / 255)],
                                         startPoint: .top, endPoint: .bottom))
                if let overlayImage {
                    Image(uiImage: overlayImage)
                        .resizable()
                        .scaledToFit()
                } else {
                    ProgressView().tint(.white)
                }
            }
            .aspectRatio(9.0 / 16.0, contentMode: .fit)
            .frame(maxHeight: 420)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .frame(maxWidth: .infinity)

            HStack(spacing: 8) {
                ForEach(StoryOverlay.accents) { option in
                    let selected = option == accent
                    Button { accent = option } label: {
                        HStack(spacing: 6) {
                            Circle().fill(Color(uiColor: option.uiColor)).frame(width: 12, height: 12)
                                .overlay(Circle().stroke(Color.black.opacity(0.25), lineWidth: 1))
                            Text(verbatim: option.label).font(.caption.weight(.semibold))
                        }
                        .foregroundStyle(selected ? .white : Color.textPrimary)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 7)
                        .background(selected ? Color.action : Color.surface, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }

            HStack(spacing: 10) {
                PhotosPicker(selection: $pickedPhotoItem, matching: .images) {
                    Label { Text(verbatim: pickedPhoto == nil ? "Colocar sobre foto" : "Trocar foto") }
                          icon: { Image(systemName: "photo.on.rectangle") }
                        .font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .tint(Color.action)

                if pickedPhoto != nil {
                    Button {
                        pickedPhotoItem = nil
                        pickedPhoto = nil
                        rebuildOverlay()
                    } label: {
                        Text(verbatim: "PNG transparente")
                            .font(.subheadline.weight(.medium))
                            .frame(minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                    .tint(Color.textSecondary)
                }
            }

            if hasPremium {
                Toggle(isOn: $supporterAccess.lab.hideStoryWatermark) {
                    Text(verbatim: "Remover marca \"Feito no Aravia\"")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(Color.textPrimary)
                }
                .tint(Color.action)
                .padding(12)
                .background(Color.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            } else {
                PremiumPassCard(compact: true, onOpenTools: { showsEarlyAccess = true })
            }

            HStack(spacing: 10) {
                Button {
                    guard let img = overlayImage else { return }
                    shareFileURL = StoryOverlay.writeTempPng(img, name: "\(Int(date.timeIntervalSince1970))")
                } label: {
                    Label { Text(verbatim: "Compartilhar PNG") } icon: { Image(systemName: "square.and.arrow.up") }
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                .buttonStyle(.borderedProminent)
                .tint(Color.action)

                Button {
                    guard let img = overlayImage else { return }
                    Task { saveStatus = await StoryOverlay.savePngToPhotos(img) }
                } label: {
                    Label { Text(verbatim: "Salvar") } icon: { Image(systemName: "photo.badge.arrow.down") }
                        .font(.subheadline.weight(.semibold))
                        .frame(minHeight: 48)
                }
                .buttonStyle(.bordered)
                .tint(Color.action)
            }

            if let saveStatus {
                Text(verbatim: saveStatus)
                    .font(.footnote)
                    .foregroundStyle(Color.textSecondary)
            }
        }
    }

    @ViewBuilder private var videoSection: some View {
        Text(verbatim: "Escolha um modelo para gerar, ou toque e segure para editar, duplicar ou excluir.")
            .font(.subheadline)
            .foregroundStyle(Color.textSecondary)
        LazyVGrid(columns: columns, spacing: 18) {
            ForEach(store.templates) { template in
                card(template)
            }
        }
    }

    private func rebuildOverlay() {
        let base = StoryOverlay.renderTransparent(
            track: safeTrack,
            title: title,
            date: date,
            stage: araraStage,
            accentRgb: accent.rgb,
            showWatermark: showWatermark
        )
        overlayImage = pickedPhoto.map { StoryOverlay.renderOverPhoto($0, overlay: base) } ?? base
    }

    private func card(_ template: StoryTemplate) -> some View {
        Button { generating = template } label: {
            VStack(alignment: .leading, spacing: 8) {
                ZStack {
                    RoundedRectangle(cornerRadius: 16, style: .continuous)
                        .fill(Color(uiColor: template.ink))
                    if let image = previews[template] {
                        Image(uiImage: image)
                            .resizable()
                            .scaledToFill()
                    } else {
                        ProgressView().tint(.white)
                    }
                }
                .aspectRatio(9.0 / 16.0, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .overlay(alignment: .bottomTrailing) {
                    Image(systemName: "play.circle.fill")
                        .font(.system(size: 30))
                        .foregroundStyle(.white, Color(uiColor: template.accent))
                        .padding(8)
                }
                HStack {
                    Text(verbatim: template.name)
                        .font(.headline)
                        .foregroundStyle(Color.textPrimary)
                        .lineLimit(1)
                    Spacer()
                    Button { path.append(template.id) } label: {
                        Image(systemName: "slider.horizontal.3")
                    }
                    .buttonStyle(.borderless)
                    .tint(Color.action)
                }
                Text(verbatim: "\(template.camera.name) · \(Int(template.seconds)) s")
                    .font(.caption)
                    .foregroundStyle(Color.textSecondary)
            }
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button { path.append(template.id) } label: { Label { Text(verbatim: "Editar") } icon: { Image(systemName: "slider.horizontal.3") } }
            Button { store.duplicate(template) } label: { Label { Text(verbatim: "Duplicar") } icon: { Image(systemName: "plus.square.on.square") } }
            Button(role: .destructive) { confirmDelete = template } label: {
                Label { Text(verbatim: "Excluir") } icon: { Image(systemName: "trash") }
            }
        }
    }

    /// One frame per look, drawn with the real map; only looks whose settings changed are drawn again.
    private func loadPreviews() async {
        let current = Set(store.templates)
        previews = previews.filter { current.contains($0.key) }
        for template in store.templates where previews[template] == nil {
            if Task.isCancelled { return }
            let renderer = RouteVideoRenderer(track: safeTrack, chunks: safeChunks, info: .init(title: title, date: date), template: template)
            if let image = try? await renderer.preview(at: 0.55) {
                previews[template] = image.preparingThumbnail(of: CGSize(width: 360, height: 640)) ?? image
            }
        }
    }
}

/// Every setting of a look, with a live frame of this route on top. Changes are kept as they are made.
struct StoryTemplateEditor: View {
    let id: UUID
    let track: GpsTrack
    let chunks: [RouteChunk]
    let title: String
    let date: Date
    let generate: (StoryTemplate) -> Void

    @StateObject private var store = StoryTemplateStore.shared
    @State private var draft = StoryTemplate(name: "")
    @State private var loaded = false
    @State private var preview: UIImage?
    @State private var previewAt = 0.55
    @State private var drawing = false

    var body: some View {
        Form {
            Section {
                ZStack {
                    Color(uiColor: draft.ink)
                    if let preview {
                        Image(uiImage: preview).resizable().scaledToFit()
                    }
                    if drawing { ProgressView().tint(.white) }
                }
                .aspectRatio(9.0 / 16.0, contentMode: .fit)
                .frame(maxHeight: 420)
                .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                .frame(maxWidth: .infinity)
                .listRowBackground(Color.clear)
                Picker(selection: $previewAt) {
                    Text(verbatim: "Começo").tag(0.02)
                    Text(verbatim: "Meio").tag(0.55)
                    Text(verbatim: "Final").tag(1.0)
                } label: { Text(verbatim: "Ver") }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)
            }

            Section {
                TextField(text: $draft.name) { Text(verbatim: "Nome do modelo") }
                TextField(text: $draft.customTitle) { Text(verbatim: "Título no vídeo (vazio = nome da rota)") }
                Toggle(isOn: $draft.showDate) { Text(verbatim: "Mostrar data e hora") }
            } header: { Text(verbatim: "Texto") }

            Section {
                Picker(selection: $draft.map) {
                    ForEach(StoryTemplate.MapLook.allCases) { Text(verbatim: $0.name).tag($0) }
                } label: { Text(verbatim: "Estilo do mapa") }
                slider("Escurecer o mapa", value: $draft.mapDim, range: 0...0.7, text: "\(Int(draft.mapDim * 100))%")
            } header: { Text(verbatim: "Mapa") }

            Section {
                Picker(selection: $draft.camera) {
                    ForEach(StoryTemplate.Camera.allCases) { Text(verbatim: $0.name).tag($0) }
                } label: { Text(verbatim: "Câmera") }
                slider("Duração do percurso", value: $draft.seconds, range: 6...30, step: 1, text: "\(Int(draft.seconds)) s")
            } header: { Text(verbatim: "Câmera") }

            Section {
                Picker(selection: $draft.lineColoring) {
                    ForEach(StoryTemplate.LineColoring.allCases) { Text(verbatim: $0.name).tag($0) }
                } label: { Text(verbatim: "Cor da linha") }
                if draft.lineColoring == .single {
                    ColorPicker(selection: color(\.lineHex), supportsOpacity: false) { Text(verbatim: "Cor") }
                }
                slider("Espessura", value: $draft.lineWidth, range: 4...18, step: 1, text: "\(Int(draft.lineWidth))")
                ColorPicker(selection: color(\.accentHex), supportsOpacity: false) { Text(verbatim: "Destaque (ponto e barra)") }
            } header: { Text(verbatim: "Rota") }

            Section {
                Picker(selection: $draft.layout) {
                    ForEach(StoryTemplate.Layout.allCases) { Text(verbatim: $0.name).tag($0) }
                } label: { Text(verbatim: "Disposição") }
                Toggle(isOn: $draft.shades) { Text(verbatim: "Sombra atrás do texto") }
                if draft.shades {
                    ColorPicker(selection: color(\.inkHex), supportsOpacity: false) { Text(verbatim: "Cor da sombra") }
                }
                Toggle(isOn: $draft.showDistance) { Text(verbatim: "Distância") }
                Toggle(isOn: $draft.showTime) { Text(verbatim: "Tempo") }
                Toggle(isOn: $draft.showSpeed) { Text(verbatim: "Ritmo (pace)") }
                Toggle(isOn: $draft.showProgress) { Text(verbatim: "Barra de progresso") }
                Toggle(isOn: $draft.showBrand) { Text(verbatim: "Marca ARAVIA") }
            } header: { Text(verbatim: "Informações") }
        }
        .scrollContentBackground(.hidden)
        .background(Color.canvas.ignoresSafeArea())
        .navigationTitle(Text(verbatim: draft.name.isEmpty ? "Modelo" : draft.name))
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            Button { generate(draft) } label: {
                Label { Text(verbatim: "Gerar vídeo com este modelo") } icon: { Image(systemName: "film") }
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .tint(Color.action)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
        }
        .onAppear {
            if !loaded, let t = store.templates.first(where: { $0.id == id }) {
                draft = t
                loaded = true
            }
        }
        .onChange(of: draft) { _, new in if loaded { store.update(new) } }
        .task(id: PreviewKey(template: draft, at: previewAt)) { await drawPreview() }
    }

    private struct PreviewKey: Equatable {
        let template: StoryTemplate
        let at: Double
    }

    private func drawPreview() async {
        guard loaded else { return }
        // Wait for the sliders to settle before drawing the map again.
        try? await Task.sleep(for: .milliseconds(450))
        if Task.isCancelled { return }
        drawing = true
        defer { drawing = false }
        let renderer = RouteVideoRenderer(track: track, chunks: chunks, info: .init(title: title, date: date), template: draft)
        if let image = try? await renderer.preview(at: previewAt), !Task.isCancelled {
            preview = image.preparingThumbnail(of: CGSize(width: 540, height: 960)) ?? image
        }
    }

    private func slider(_ label: String, value: Binding<Double>, range: ClosedRange<Double>, step: Double? = nil,
                        text: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(verbatim: label)
                Spacer()
                Text(verbatim: text).foregroundStyle(Color.textSecondary).monospacedDigit()
            }
            if let step {
                Slider(value: value, in: range, step: step)
            } else {
                Slider(value: value, in: range)
            }
        }
        .tint(Color.action)
    }

    private func color(_ key: WritableKeyPath<StoryTemplate, UInt32>) -> Binding<Color> {
        Binding(
            get: { Color(uiColor: UIColor(hex: draft[keyPath: key])) },
            set: { draft[keyPath: key] = UIColor($0).hex }
        )
    }
}
