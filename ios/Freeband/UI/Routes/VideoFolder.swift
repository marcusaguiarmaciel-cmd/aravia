import SwiftUI
import UniformTypeIdentifiers

/// The folder the person picked for their Story videos: any folder the Files app offers, such as
/// iCloud Drive or Google Drive. Access survives relaunches through a security-scoped bookmark,
/// which needs no iCloud entitlement, so it works with a free Apple ID signing.
@MainActor
final class VideoFolder: ObservableObject {
    static let shared = VideoFolder()
    private static let key = "aravia.videoFolderBookmark"

    @Published private(set) var name: String?

    private init() { name = resolve()?.lastPathComponent }

    private func resolve() -> URL? {
        guard let data = UserDefaults.standard.data(forKey: Self.key) else { return nil }
        var stale = false
        guard let url = try? URL(resolvingBookmarkData: data, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else { return nil }
        if stale, url.startAccessingSecurityScopedResource() {
            defer { url.stopAccessingSecurityScopedResource() }
            if let fresh = try? url.bookmarkData() { UserDefaults.standard.set(fresh, forKey: Self.key) }
        }
        return url
    }

    func set(_ url: URL) throws {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        UserDefaults.standard.set(try url.bookmarkData(), forKey: Self.key)
        name = url.lastPathComponent
    }

    func clear() {
        UserDefaults.standard.removeObject(forKey: Self.key)
        name = nil
    }

    /// Copies the video into the folder and returns its name there. The upload to iCloud or Google
    /// Drive is done by their apps in the background.
    func store(_ file: URL, as fileName: String) async throws -> String {
        guard let folder = resolve() else { throw CocoaError(.fileNoSuchFile) }
        return try await Task.detached(priority: .utility) {
            let scoped = folder.startAccessingSecurityScopedResource()
            defer { if scoped { folder.stopAccessingSecurityScopedResource() } }
            var target = folder.appendingPathComponent(fileName)
            var n = 2
            while FileManager.default.fileExists(atPath: target.path) {
                target = folder.appendingPathComponent("\((fileName as NSString).deletingPathExtension) (\(n)).mp4")
                n += 1
            }
            var coordError: NSError?
            var copyError: Error?
            NSFileCoordinator().coordinate(writingItemAt: target, options: .forReplacing, error: &coordError) { dest in
                do { try FileManager.default.copyItem(at: file, to: dest) } catch { copyError = error }
            }
            if let e = coordError ?? copyError { throw e }
            return target.lastPathComponent
        }.value
    }
}

/// Picks a folder through the Files app.
struct FolderPicker: UIViewControllerRepresentable {
    let onPick: (URL) -> Void

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder])
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(onPick: onPick) }

    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let onPick: (URL) -> Void
        init(onPick: @escaping (URL) -> Void) { self.onPick = onPick }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            if let url = urls.first { onPick(url) }
        }
    }
}
