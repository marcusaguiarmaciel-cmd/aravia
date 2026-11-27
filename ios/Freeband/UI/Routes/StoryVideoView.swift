import AVKit
import CoreTransferable
import Photos
import UniformTypeIdentifiers
import SwiftUI

/// Generates the Story video of a route (RouteVideoRenderer) and offers it to share. Nothing is
/// recorded from the screen: the frames are drawn off screen, so the person only waits for the
/// progress bar and then shares.
struct StoryVideoView: View {
    let track: GpsTrack
    let chunks: [RouteChunk]
    let title: String
    let date: Date
    var template = StoryTemplate.builtIn[0]

    enum Phase: Equatable {
        case rendering(Double), done(URL), failed(String)
    }

    @Environment(\.dismiss) private var dismiss
    @State private var phase: Phase = .rendering(0)
    @State private var renderer: RouteVideoRenderer?
    @State private var player: AVPlayer?
    @State private var attempt = 0
    @StateObject private var folder = VideoFolder.shared
    @State private var picking = false
    @State private var saved: String?
    @State private var saveError: String?
    @StateObject private var drive = GoogleDrive.shared
    @State private var upload: Double?
    @State private var driveLink: URL?
    @State private var showDriveLogin = false
    @State private var photosSaved = false
    @State private var assetID: String?
    @State private var sharing: URL?

    var body: some View {
        NavigationStack {
            Group {
                switch phase {
                case .rendering(let p):
                    rendering(p)
                case .done(let url):
                    ready(url)
                case .failed(let message):
                    failed(message)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .padding(20)
            .background(Color.canvas.ignoresSafeArea())
            .navigationTitle(Text(verbatim: "Vídeo para Story"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { renderer?.cancel(); dismiss() } label: { Text(verbatim: "Fechar") }
                }
            }
        }
        .task(id: attempt) { await render() }
        .sheet(item: Binding(get: { sharing.map(SharedFile.init) }, set: { sharing = $0?.url })) { file in
            ActivitySheet(items: [file.url])
                .ignoresSafeArea()
        }
        .sheet(isPresented: $showDriveLogin, onDismiss: {
            if drive.isConnected, saved == nil, case .done(let video) = phase { Task { await save(video) } }
        }) {
            NavigationStack {
                GoogleDriveView()
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button { showDriveLogin = false } label: { Text(verbatim: "OK") }
                        }
                    }
            }
        }
        .sheet(isPresented: $picking) {
            FolderPicker { url in
                do {
                    try folder.set(url)
                    if case .done(let video) = phase { Task { await save(video) } }
                } catch { saveError = error.localizedDescription }
            }
            .ignoresSafeArea()
        }
        .onDisappear {
            renderer?.cancel(); player?.pause()
            // The file stays until the next video is made (RouteVideoRenderer clears old ones):
            // Instagram may still be reading it after this screen closes.
        }
    }

    private func rendering(_ p: Double) -> some View {
        VStack(spacing: 18) {
            Spacer()
            Image(systemName: "film.stack")
                .font(.system(size: 54, weight: .semibold))
                .foregroundStyle(Color.action)
            Text(verbatim: "Gerando o seu vídeo")
                .font(.title2.weight(.bold))
                .foregroundStyle(Color.textPrimary)
            Text(verbatim: "O mapa 3D é desenhado quadro a quadro. Leva cerca de um minuto; deixe o app aberto.")
                .font(.subheadline)
                .foregroundStyle(Color.textSecondary)
                .multilineTextAlignment(.center)
            ProgressView(value: p)
                .tint(Color.action)
                .padding(.horizontal, 24)
            Text(verbatim: "\(Int(p * 100))%")
                .font(.headline.monospacedDigit())
                .foregroundStyle(Color.textSecondary)
            Spacer()
        }
    }

    private func ready(_ url: URL) -> some View {
        VStack(spacing: 16) {
            VideoPlayer(player: player)
                .aspectRatio(9.0 / 16.0, contentMode: .fit)
                .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
                .frame(maxHeight: .infinity)
                .onAppear {
                    if player == nil { player = AVPlayer(url: url) }
                    player?.play()
                }
            // The classic share sheet with the file itself, as the first versions did: iOS
            // hands it to Instagram as a movie (Story, Reel, Post or message).
            Button { sharing = url } label: {
                Label { Text(verbatim: "Compartilhar (Instagram, Stories…)") } icon: { Image(systemName: "square.and.arrow.up") }
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            .tint(Color.action)
            Button { Task { _ = await saveToPhotos(url) } } label: {
                Label { Text(verbatim: photosSaved ? "Salvo no rolo da câmera" : "Salvar no rolo da câmera") }
                    icon: { Image(systemName: photosSaved ? "checkmark.circle.fill" : "photo.badge.arrow.down") }
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
            .tint(Color.action)
            .disabled(photosSaved)
            cloudStatus
        }
    }

    private func saveToPhotos(_ url: URL) async -> String? {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            saveError = "Sem permissão para salvar no rolo. Libere em Ajustes › Aravia › Fotos › Adicionar Fotos."
            return nil
        }
        var placeholder: String?
        do {
            try await PHPhotoLibrary.shared().performChanges {
                placeholder = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: url)?
                    .placeholderForCreatedAsset?.localIdentifier
            }
            photosSaved = true
            assetID = placeholder
            return placeholder
        } catch {
            saveError = "Não deu para salvar no rolo: \(error.localizedDescription)"
            return nil
        }
    }

    @ViewBuilder private var cloudStatus: some View {
        if let upload {
            ProgressView(value: upload) {
                Text(verbatim: "Enviando para o Google Drive… \(Int(upload * 100))%")
                    .font(.footnote)
                    .foregroundStyle(Color.textSecondary)
            }
            .tint(Color.action)
        } else if let saved {
            HStack(spacing: 6) {
                Image(systemName: "checkmark.icloud")
                Text(verbatim: drive.isConnected ? "No Google Drive › Aravia › \(saved)" : "Guardado em \(folder.name ?? "") › \(saved)")
                    .lineLimit(2)
            }
            .font(.footnote.weight(.semibold))
            .foregroundStyle(Color.good)
            if let driveLink {
                Link(destination: driveLink) { Text(verbatim: "Abrir no Google Drive") }
                    .font(.footnote)
                    .tint(Color.action)
            }
        } else {
            HStack(spacing: 10) {
                Button { showDriveLogin = true } label: {
                    Label { Text(verbatim: "Google Drive") } icon: { Image(systemName: "icloud.and.arrow.up") }
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                Button { picking = true } label: {
                    Label { Text(verbatim: "iCloud / pasta") } icon: { Image(systemName: "folder") }
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
            }
            .buttonStyle(.bordered)
            .tint(Color.action)
            Text(verbatim: saveError ?? "Conecte uma vez; os próximos vídeos vão direto para a nuvem e não ficam ocupando o celular.")
                .font(.footnote)
                .foregroundStyle(saveError == nil ? Color.textSecondary : Color.low)
                .multilineTextAlignment(.center)
        }
    }

    private func save(_ url: URL) async {
        saveError = nil
        let f = DateFormatter()
        f.locale = Locale(identifier: "pt_BR")
        f.dateFormat = "yyyy-MM-dd HH'h'mm"
        let name = "Aravia \(f.string(from: date)).mp4"
        do {
            if drive.isConnected {
                upload = 0
                defer { upload = nil }
                driveLink = try await drive.upload(url, name: name) { p in upload = p }
                saved = name
            } else {
                saved = try await folder.store(url, as: name)
            }
        } catch {
            saved = nil
            driveLink = nil
            saveError = "Não deu para guardar: \(error.localizedDescription)"
        }
    }

    private func failed(_ message: String) -> some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 44))
                .foregroundStyle(Color.low)
            Text(verbatim: message)
                .multilineTextAlignment(.center)
                .foregroundStyle(Color.textPrimary)
            Button { attempt += 1 } label: { Text(verbatim: "Tentar de novo").frame(minWidth: 160, minHeight: 44) }
                .buttonStyle(.borderedProminent)
                .tint(Color.action)
            Spacer()
        }
    }

    private func render() async {
        phase = .rendering(0)
        player = nil
        saved = nil
        photosSaved = false
        assetID = nil
        driveLink = nil
        let r = RouteVideoRenderer(track: track, chunks: chunks, info: .init(title: title, date: date), template: template)
        renderer = r
        UIApplication.shared.isIdleTimerDisabled = true
        defer { UIApplication.shared.isIdleTimerDisabled = false }
        do {
            let url = try await r.render { p in
                if case .rendering = phase { phase = .rendering(p) }
            }
            phase = .done(url)
            if drive.isConnected || folder.name != nil { await save(url) }
        } catch is CancellationError {
            return
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }
}

private struct SharedFile: Identifiable {
    let url: URL
    var id: URL { url }
}
