import AVFoundation
import CoreLocation
import MapLibre
import UIKit

/// Renders a route replay into a vertical 1080×1920 MP4 without recording the screen: every
/// frame is a MapLibre snapshot of the 3D map (same style as the Routes screen) with the route
/// drawn over it, then the title and numbers, written with AVAssetWriter.
@MainActor
final class RouteVideoRenderer {
    struct Info {
        let title: String
        let date: Date
    }

    static let pixelSize = CGSize(width: 1080, height: 1920)
    static let fps: Int32 = 24
    /// Seconds of route playback, then a still on the whole route.
    static let introSeconds: Double = 0.8
    /// The arara's ending (AraraFinale).
    static let outroSeconds: Double = AraraFinale.seconds

    private let track: GpsTrack
    private let chunks: [RouteChunk]
    private let info: Info
    private let template: StoryTemplate
    private var snapshotter: MLNMapSnapshotter?
    private let styler = SnapshotStyler()
    private var cancelled = false

    init(track: GpsTrack, chunks: [RouteChunk], info: Info, template: StoryTemplate) {
        self.track = track
        self.chunks = chunks
        self.info = info
        self.template = template
    }

    /// One composed frame at `fraction` of the route (1 = the final still), for the Hub's previews.
    func preview(at fraction: Double) async throws -> UIImage {
        if fraction >= 1 {
            // The ending, with the wing open.
            let overview = try await snapshot(camera: overviewCamera(), zoom: overviewZoom(), elapsed: track.duration)
            return finaleFrame(map: overview, t: AraraFinale.seconds - 0.8, stats: finaleStats())
        }
        let elapsed = track.duration * min(max(fraction, 0), 1)
        let heading = cameraHeading(elapsed: elapsed, current: nil)
        return try await frameImage(elapsed: elapsed, heading: heading, overview: fraction >= 1)
    }

    /// Follows the walking direction, or circles slowly for the drone camera.
    private func cameraHeading(elapsed: TimeInterval, current: Double?) -> Double {
        if template.camera == .drone {
            let start = RouteReplayView.nextHeading(track, elapsed: 0, current: nil)
            return (start + 160 * elapsed / max(track.duration, 1)).truncatingRemainder(dividingBy: 360)
        }
        return RouteReplayView.nextHeading(track, elapsed: elapsed, current: current)
    }

    func cancel() {
        cancelled = true
        snapshotter?.cancel()
    }

    /// Renders the video and returns its file URL. `progress` goes from 0 to 1.
    func render(progress: @escaping (Double) -> Void) async throws -> URL {
        // Leftovers of earlier videos are dropped, so the phone never piles them up.
        let tmp = FileManager.default.temporaryDirectory
        for old in (try? FileManager.default.contentsOfDirectory(at: tmp, includingPropertiesForKeys: nil)) ?? []
        where old.lastPathComponent.hasPrefix("aravia-") && old.pathExtension == "mp4" {
            try? FileManager.default.removeItem(at: old)
        }
        let url = tmp.appendingPathComponent("aravia-\(Int(Date().timeIntervalSince1970)).mp4")
        try? FileManager.default.removeItem(at: url)

        let writer = try AVAssetWriter(outputURL: url, fileType: .mp4)
        let settings: [String: Any] = [
            AVVideoCodecKey: AVVideoCodecType.h264,
            AVVideoWidthKey: Int(Self.pixelSize.width),
            AVVideoHeightKey: Int(Self.pixelSize.height),
            AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 10_000_000,
                                              AVVideoProfileLevelKey: AVVideoProfileLevelH264HighAutoLevel],
        ]
        let input = AVAssetWriterInput(mediaType: .video, outputSettings: settings)
        input.expectsMediaDataInRealTime = false
        let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
            kCVPixelBufferWidthKey as String: Int(Self.pixelSize.width),
            kCVPixelBufferHeightKey as String: Int(Self.pixelSize.height),
        ])
        writer.add(input)
        // A silent stereo AAC track: Instagram and other apps refuse some videos that have no sound.
        let audio = AVAssetWriterInput(mediaType: .audio, outputSettings: [
            AVFormatIDKey: kAudioFormatMPEG4AAC,
            AVSampleRateKey: 44_100,
            AVNumberOfChannelsKey: 2,
            AVEncoderBitRateKey: 64_000,
        ])
        audio.expectsMediaDataInRealTime = false
        writer.add(audio)
        let silence = SilentAudio()
        // The sound ends exactly where the picture ends: a longer track makes Instagram fail.
        silence.limit = Double(Int(Self.introSeconds * Double(Self.fps)) + Int(template.seconds * Double(Self.fps))
                               + Int(Self.outroSeconds * Double(Self.fps))) / Double(Self.fps)
        guard writer.startWriting() else { throw writer.error ?? RenderError.writer }
        writer.startSession(atSourceTime: .zero)

        let fps = Double(Self.fps)
        let playFrames = Int(template.seconds * fps)
        let introFrames = Int(Self.introSeconds * fps)
        let outroFrames = Int(Self.outroSeconds * fps)
        let total = Double(introFrames + playFrames + outroFrames)
        var frame: Int64 = 0

        func append(_ image: UIImage) async throws {
            // While the picture waits, the writer may be waiting for sound: give it sound ahead.
            let ahead = CMTime(value: frame, timescale: Self.fps).seconds + 1
            let started = Date()
            while !input.isReadyForMoreMediaData {
                silence.fill(audio, until: ahead)
                if Date().timeIntervalSince(started) > 20 { throw RenderError.writer }
                try await Task.sleep(for: .milliseconds(5))
            }
            guard let buffer = Self.pixelBuffer(from: image, pool: adaptor.pixelBufferPool) else { throw RenderError.buffer }
            let time = CMTime(value: frame, timescale: Self.fps)
            guard adaptor.append(buffer, withPresentationTime: time) else { throw writer.error ?? RenderError.writer }
            frame += 1
            // Keep the sound a little ahead of the picture, so the writer can interleave the two.
            silence.fill(audio, until: CMTime(value: frame, timescale: Self.fps).seconds + 0.5)
            progress(Double(frame) / total)
        }

        // Intro: hold the first frame.
        var heading = cameraHeading(elapsed: 0, current: nil)
        let first = try await frameImage(elapsed: 0, heading: heading, overview: false)
        for _ in 0..<introFrames { try await append(first) }

        // The route.
        let step = track.duration / Double(max(playFrames - 1, 1))
        for i in 0..<playFrames {
            if cancelled { throw CancellationError() }
            let elapsed = min(track.duration, Double(i) * step)
            heading = cameraHeading(elapsed: elapsed, current: heading)
            try await append(try await frameImage(elapsed: elapsed, heading: heading, overview: false))
        }

        // Ending: the arara flies in over the whole route, lands and opens a wing with the numbers.
        let overview = try await snapshot(camera: overviewCamera(), zoom: overviewZoom(), elapsed: track.duration)
        let stats = finaleStats()
        for i in 0..<outroFrames {
            if cancelled { throw CancellationError() }
            try await append(finaleFrame(map: overview, t: Double(i) / fps, stats: stats))
        }

        input.markAsFinished()
        // The rest of the sound, to the last frame.
        let end = CMTime(value: frame, timescale: Self.fps).seconds
        var waited = 0
        while !silence.fill(audio, until: end), waited < 2000 {
            try await Task.sleep(for: .milliseconds(5))
            waited += 1
        }
        if !silence.finished { audio.markAsFinished() }
        await writer.finishWriting()
        if writer.status != .completed { throw writer.error ?? RenderError.writer }
        snapshotter = nil
        return url
    }

    enum RenderError: LocalizedError {
        case writer, buffer, snapshot
        var errorDescription: String? {
            switch self {
            case .writer: "Não foi possível gravar o arquivo de vídeo."
            case .buffer: "Não foi possível preparar um quadro do vídeo."
            case .snapshot: "Não foi possível desenhar o mapa. Confira a internet e tente de novo."
            }
        }
    }

    // MARK: one frame

    private func frameImage(elapsed: TimeInterval, heading: Double, overview: Bool) async throws -> UIImage {
        let camera = overview ? overviewCamera() : followCamera(elapsed: elapsed, heading: heading)
        let zoom = overview ? overviewZoom() : template.camera.zoom
        let map = try await snapshot(camera: camera, zoom: zoom, elapsed: elapsed)
        return compose(map: map, elapsed: elapsed, overview: overview)
    }

    private func followCamera(elapsed: TimeInterval, heading: Double) -> MLNMapCamera {
        let now = track.position(at: elapsed)
        return MLNMapCamera(lookingAtCenter: now.coordinate, altitude: 260, pitch: template.camera.pitch, heading: heading)
    }

    /// The snapshotter ignores the camera's altitude unless the zoom level is set, so the zoom is
    /// worked out from the route's size: the whole route fits about 70% of the frame's width.
    private func overviewZoom() -> Double {
        var minLat = 90.0, maxLat = -90.0, minLon = 180.0, maxLon = -180.0
        for p in track.points {
            minLat = min(minLat, p.latitude); maxLat = max(maxLat, p.latitude)
            minLon = min(minLon, p.longitude); maxLon = max(maxLon, p.longitude)
        }
        let a = GpsPoint(time: .now, latitude: minLat, longitude: minLon, speedKmh: nil)
        let b = GpsPoint(time: .now, latitude: maxLat, longitude: maxLon, speedKmh: nil)
        let span = max(GpsTrack.meters(a, b), 150)
        let lat = (minLat + maxLat) / 2 * Double.pi / 180
        let pointsAcross = Self.pixelSize.width / 2 * 0.7
        // MapLibre: 512 points span the equator at zoom 0.
        let zoom = log2(40_075_016.686 * cos(lat) * Double(pointsAcross) / (512 * span))
        return min(17, max(11, zoom))
    }

    private func overviewCamera() -> MLNMapCamera {
        var minLat = 90.0, maxLat = -90.0, minLon = 180.0, maxLon = -180.0
        for p in track.points {
            minLat = min(minLat, p.latitude); maxLat = max(maxLat, p.latitude)
            minLon = min(minLon, p.longitude); maxLon = max(maxLon, p.longitude)
        }
        let center = CLLocationCoordinate2D(latitude: (minLat + maxLat) / 2, longitude: (minLon + maxLon) / 2)
        let a = GpsPoint(time: .now, latitude: minLat, longitude: minLon, speedKmh: nil)
        let b = GpsPoint(time: .now, latitude: maxLat, longitude: maxLon, speedKmh: nil)
        let span = max(GpsTrack.meters(a, b), 200)
        return MLNMapCamera(lookingAtCenter: center, acrossDistance: span * 1.9, pitch: 45, heading: 330)
    }

    private func snapshot(camera: MLNMapCamera, zoom: Double, elapsed: TimeInterval) async throws -> UIImage {
        let pointSize = CGSize(width: Self.pixelSize.width / 2, height: Self.pixelSize.height / 2)
        let options = MLNMapSnapshotOptions(styleURL: template.map.styleURL, camera: camera, size: pointSize)
        options.zoomLevel = zoom
        options.scale = 2
        options.showsLogo = false
        options.showsAttribution = true
        let snapshotter: MLNMapSnapshotter
        if let existing = self.snapshotter {
            existing.options = options
            snapshotter = existing
        } else {
            snapshotter = MLNMapSnapshotter(options: options)
            snapshotter.delegate = styler
            self.snapshotter = snapshotter
        }

        let track = self.track, chunks = self.chunks, template = self.template
        return try await withCheckedThrowingContinuation { cont in
            snapshotter.start(overlayHandler: { overlay in
                Self.drawRoute(in: overlay.context, track: track, chunks: chunks, template: template, elapsed: elapsed) {
                    overlay.point(for: $0)
                }
            }, completionHandler: { snapshot, error in
                if let image = snapshot?.image {
                    cont.resume(returning: image)
                } else {
                    cont.resume(throwing: error ?? RenderError.snapshot)
                }
            })
        }
    }

    /// The whole route faint, the part walked so far in speed colours, and the walker.
    nonisolated private static func drawRoute(in ctx: CGContext, track: GpsTrack, chunks: [RouteChunk], template: StoryTemplate,
                                              elapsed: TimeInterval,
                                              point: (CLLocationCoordinate2D) -> CGPoint) {
        ctx.setLineCap(.round)
        ctx.setLineJoin(.round)

        // With a tilted camera, points behind it or far past the horizon project to wild screen
        // positions, and a line to them streaks across the frame. Those points break the line.
        let frame = ctx.boundingBoxOfClipPath
        let safe = frame.insetBy(dx: -frame.width, dy: -frame.height)
        let maxJump = hypot(frame.width, frame.height)
        func visible(_ p: CGPoint) -> Bool { p.x.isFinite && p.y.isFinite && safe.contains(p) }

        func stroke(_ coords: [CLLocationCoordinate2D], color: UIColor, width: CGFloat) {
            guard coords.count > 1 else { return }
            ctx.beginPath()
            var last: CGPoint?
            for c in coords {
                let p = point(c)
                guard visible(p) else { last = nil; continue }
                if let last, hypot(p.x - last.x, p.y - last.y) < maxJump {
                    ctx.addLine(to: p)
                } else {
                    ctx.move(to: p)
                }
                last = p
            }
            ctx.setStrokeColor(color.cgColor)
            ctx.setLineWidth(width)
            ctx.strokePath()
        }

        let width = CGFloat(template.lineWidth)
        stroke(track.points.map(\.coordinate), color: UIColor.black.withAlphaComponent(0.35), width: max(3, width * 0.65))

        let now = track.position(at: elapsed)
        for chunk in chunks where chunk.range.lowerBound <= now.index {
            let upper = min(chunk.range.upperBound, now.index)
            var coords = track.points[chunk.range.lowerBound...upper].map(\.coordinate)
            if chunk.range.contains(now.index) { coords.append(now.coordinate) }
            let color = template.lineColoring == .speed ? RouteChunkPalette.colors[chunk.paletteIndex] : template.lineColor
            stroke(coords, color: color, width: width)
        }

        let p = point(now.coordinate)
        guard visible(p) else { return }
        let walker = template.accent
        ctx.setFillColor(walker.withAlphaComponent(0.25).cgColor)
        ctx.fillEllipse(in: CGRect(x: p.x - 20, y: p.y - 20, width: 40, height: 40))
        ctx.setFillColor(UIColor.white.cgColor)
        ctx.fillEllipse(in: CGRect(x: p.x - 11, y: p.y - 11, width: 22, height: 22))
        ctx.setFillColor(walker.cgColor)
        ctx.fillEllipse(in: CGRect(x: p.x - 7.5, y: p.y - 7.5, width: 15, height: 15))
    }

    // MARK: ending

    /// Pace in minutes per kilometre ("5:32"), from a speed in km/h; "--:--" when nearly still.
    static func pace(kmh: Double) -> String {
        guard kmh >= 1 else { return "--:--" }
        let seconds = Int((3600 / kmh).rounded())
        return String(format: "%d:%02d", seconds / 60, seconds % 60)
    }

    private func finaleStats() -> [AraraFinale.Stat] {
        let avg = track.duration > 0 ? track.distanceMeters / track.duration * 3.6 : 0
        var stats: [AraraFinale.Stat] = []
        if template.showDistance { stats.append(.init(value: RouteNames.km(track.distanceMeters), label: "distância")) }
        if template.showTime { stats.append(.init(value: RouteNames.clock(track.duration), label: "tempo")) }
        if template.showSpeed {
            stats.append(.init(value: "\(Self.pace(kmh: avg)) /km", label: "ritmo médio"))
        }
        return stats
    }

    private func finaleFrame(map: UIImage, t: Double, stats: [AraraFinale.Stat]) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let size = Self.pixelSize
        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            AraraFinale.draw(in: context.cgContext, size: size, t: t, map: map, ink: template.ink,
                             stats: stats, showBrand: template.showBrand)
        }
    }

    // MARK: title and numbers

    private func compose(map: UIImage, elapsed: TimeInterval, overview: Bool) -> UIImage {
        let size = Self.pixelSize
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let now = track.position(at: elapsed)
        let avg = track.duration > 0 ? track.distanceMeters / track.duration * 3.6 : 0
        let t = template

        return UIGraphicsImageRenderer(size: size, format: format).image { context in
            let ctx = context.cgContext
            map.draw(in: CGRect(origin: .zero, size: size))
            if t.mapDim > 0 {
                ctx.setFillColor(UIColor.black.withAlphaComponent(min(t.mapDim, 0.7)).cgColor)
                ctx.fill(CGRect(origin: .zero, size: size))
            }

            let space = CGColorSpaceCreateDeviceRGB()
            let ink = t.ink
            if t.shades {
                if let top = CGGradient(colorsSpace: space, colors: [ink.withAlphaComponent(0.8).cgColor, ink.withAlphaComponent(0).cgColor] as CFArray, locations: [0, 1]) {
                    ctx.drawLinearGradient(top, start: .zero, end: CGPoint(x: 0, y: 420), options: [])
                }
                if let bottom = CGGradient(colorsSpace: space, colors: [ink.withAlphaComponent(0).cgColor, ink.withAlphaComponent(0.9).cgColor] as CFArray, locations: [0, 1]) {
                    ctx.drawLinearGradient(bottom, start: CGPoint(x: 0, y: size.height - 640), end: CGPoint(x: 0, y: size.height), options: [])
                }
            }
            // Without shades the text sits on the map, so it takes the ink colour on a light map.
            let text: UIColor = t.shades || t.mapDim > 0.25 ? .white : ink
            let soft = text.withAlphaComponent(0.8)

            let margin: CGFloat = 72
            let width = size.width - 2 * margin
            let title = t.customTitle.trimmingCharacters(in: .whitespaces).isEmpty ? info.title : t.customTitle
            let titleSize: CGFloat = t.layout == .minimal ? 52 : 70
            Self.draw(title, font: .systemFont(ofSize: titleSize, weight: .heavy), color: text,
                      at: CGPoint(x: margin, y: 150), width: width)
            if t.showDate {
                let date = info.date.formatted(.dateTime.day().month(.wide).hour().minute().locale(Locale(identifier: "pt_BR")))
                Self.draw(date, font: .systemFont(ofSize: t.layout == .minimal ? 32 : 38, weight: .semibold), color: soft,
                          at: CGPoint(x: margin, y: 150 + titleSize * 1.3), width: width)
            }

            let done = overview ? 1 : min(1, elapsed / max(track.duration, 1))
            let speed = overview ? avg : now.speedKmh
            let distance = RouteNames.km(overview ? track.distanceMeters : now.distance)
            let time = RouteNames.clock(overview ? track.duration : elapsed)
            let speedText = Self.pace(kmh: speed)
            var stats: [(String, String)] = []
            if t.showDistance { stats.append((distance, "distância")) }
            if t.showTime { stats.append((time, "tempo")) }
            if t.showSpeed { stats.append((speedText, overview ? "ritmo médio /km" : "ritmo /km")) }

            func progressBar(y: CGFloat, height: CGFloat) {
                guard t.showProgress else { return }
                ctx.setFillColor(text.withAlphaComponent(0.25).cgColor)
                ctx.addPath(UIBezierPath(roundedRect: CGRect(x: margin, y: y, width: width, height: height), cornerRadius: height / 2).cgPath)
                ctx.fillPath()
                ctx.setFillColor(t.accent.cgColor)
                ctx.addPath(UIBezierPath(roundedRect: CGRect(x: margin, y: y, width: max(height, width * done), height: height),
                                         cornerRadius: height / 2).cgPath)
                ctx.fillPath()
            }

            switch t.layout {
            case .classic:
                let barY = size.height - 470
                progressBar(y: barY, height: 12)
                if !stats.isEmpty {
                    let colW = width / CGFloat(stats.count)
                    for (i, s) in stats.enumerated() {
                        let x = margin + CGFloat(i) * colW
                        Self.draw(s.0, font: .monospacedDigitSystemFont(ofSize: 66, weight: .bold), color: text,
                                  at: CGPoint(x: x, y: barY + 60), width: colW, align: .center)
                        Self.draw(s.1, font: .systemFont(ofSize: 34, weight: .medium), color: soft,
                                  at: CGPoint(x: x, y: barY + 150), width: colW, align: .center)
                    }
                }
            case .minimal:
                let line = stats.map { "\($0.0) \($0.1)" }.joined(separator: "  ·  ")
                Self.draw(line, font: .monospacedDigitSystemFont(ofSize: 40, weight: .semibold), color: text,
                          at: CGPoint(x: margin, y: size.height - 330), width: width)
                progressBar(y: size.height - 260, height: 6)
            case .big:
                if let main = stats.first {
                    Self.draw(main.0, font: .monospacedDigitSystemFont(ofSize: 190, weight: .black), color: text,
                              at: CGPoint(x: margin, y: size.height - 640), width: width)
                    Self.draw(main.1.uppercased(), font: .systemFont(ofSize: 40, weight: .heavy), color: t.accent,
                              at: CGPoint(x: margin, y: size.height - 410), width: width, kern: 6)
                }
                let rest = stats.dropFirst().map { "\($0.0) \($0.1)" }.joined(separator: "   ")
                Self.draw(rest, font: .monospacedDigitSystemFont(ofSize: 42, weight: .semibold), color: soft,
                          at: CGPoint(x: margin, y: size.height - 340), width: width)
                progressBar(y: size.height - 260, height: 10)
            }

            if t.showBrand {
                Self.draw("ARAVIA", font: .systemFont(ofSize: 34, weight: .heavy),
                          color: t.shades ? AraviaPalette.palha : text,
                          at: CGPoint(x: margin, y: size.height - 170), width: width, align: .center, kern: 10)
            }
        }
    }

    private static func draw(_ text: String, font: UIFont, color: UIColor, at origin: CGPoint, width: CGFloat,
                             align: NSTextAlignment = .left, kern: CGFloat = 0) {
        let style = NSMutableParagraphStyle()
        style.alignment = align
        style.lineBreakMode = .byTruncatingTail
        let shadow = NSShadow()
        shadow.shadowColor = UIColor.black.withAlphaComponent(0.35)
        shadow.shadowBlurRadius = 8
        shadow.shadowOffset = CGSize(width: 0, height: 2)
        let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color, .paragraphStyle: style,
                                                     .shadow: shadow, .kern: kern]
        (text as NSString).draw(in: CGRect(x: origin.x, y: origin.y, width: width, height: font.lineHeight * 1.2),
                                withAttributes: attrs)
    }

    private static func pixelBuffer(from image: UIImage, pool: CVPixelBufferPool?) -> CVPixelBuffer? {
        guard let pool, let cg = image.cgImage else { return nil }
        var buffer: CVPixelBuffer?
        CVPixelBufferPoolCreatePixelBuffer(nil, pool, &buffer)
        guard let buffer else { return nil }
        CVPixelBufferLockBaseAddress(buffer, [])
        defer { CVPixelBufferUnlockBaseAddress(buffer, []) }
        guard let ctx = CGContext(data: CVPixelBufferGetBaseAddress(buffer),
                                  width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer),
                                  bitsPerComponent: 8, bytesPerRow: CVPixelBufferGetBytesPerRow(buffer),
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: CVPixelBufferGetWidth(buffer), height: CVPixelBufferGetHeight(buffer)))
        return buffer
    }
}

/// Gives the video's snapshots the same buildings as the Routes screen.
private final class SnapshotStyler: NSObject, MLNMapSnapshotterDelegate {
    func mapSnapshotter(_ snapshotter: MLNMapSnapshotter, didFinishLoading style: MLNStyle) {
        RouteMapStyle.tuneBuildings(style)
    }
}

/// Silence for the video's sound track, as 16-bit stereo PCM handed to the AAC encoder.
private final class SilentAudio {
    private static let rate: Int32 = 44_100
    private static let chunk = 1024
    private var written: Int64 = 0
    private let format: CMAudioFormatDescription?
    /// The video's length in seconds; no sound is written past it.
    var limit: Double = .infinity
    private(set) var finished = false

    init() {
        var asbd = AudioStreamBasicDescription(
            mSampleRate: Float64(Self.rate), mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
            mBytesPerPacket: 4, mFramesPerPacket: 1, mBytesPerFrame: 4, mChannelsPerFrame: 2,
            mBitsPerChannel: 16, mReserved: 0)
        var description: CMAudioFormatDescription?
        CMAudioFormatDescriptionCreate(allocator: kCFAllocatorDefault, asbd: &asbd, layoutSize: 0, layout: nil,
                                       magicCookieSize: 0, magicCookie: nil, extensions: nil,
                                       formatDescriptionOut: &description)
        format = description
    }

    /// Appends silence up to `seconds` while the writer accepts it, without ever waiting.
    /// Returns true once everything up to `seconds` is written.
    @discardableResult
    func fill(_ input: AVAssetWriterInput, until seconds: Double) -> Bool {
        guard let format, !finished else { return true }
        let end = Int64((limit * Double(Self.rate)).rounded(.down))
        defer {
            // The whole track is written: close it now, or the writer keeps the last frames
            // waiting for sound that never comes.
            if written >= end && limit.isFinite { input.markAsFinished(); finished = true }
        }
        let target = Int64((min(seconds, limit) * Double(Self.rate)).rounded(.down))
        while written < target {
            guard input.isReadyForMoreMediaData else { return false }
            let count = Int(min(Int64(Self.chunk), target - written))
            let bytes = count * 4
            var block: CMBlockBuffer?
            guard CMBlockBufferCreateWithMemoryBlock(allocator: kCFAllocatorDefault, memoryBlock: nil, blockLength: bytes,
                                                     blockAllocator: kCFAllocatorDefault, customBlockSource: nil,
                                                     offsetToData: 0, dataLength: bytes,
                                                     flags: kCMBlockBufferAssureMemoryNowFlag, blockBufferOut: &block) == noErr,
                  let block else { return true }
            CMBlockBufferFillDataBytes(with: 0, blockBuffer: block, offsetIntoDestination: 0, dataLength: bytes)
            var sample: CMSampleBuffer?
            CMAudioSampleBufferCreateReadyWithPacketDescriptions(
                allocator: kCFAllocatorDefault, dataBuffer: block, formatDescription: format, sampleCount: count,
                presentationTimeStamp: CMTime(value: written, timescale: Self.rate), packetDescriptions: nil,
                sampleBufferOut: &sample)
            guard let sample, input.append(sample) else { return true }
            written += Int64(count)
        }
        return true
    }
}
