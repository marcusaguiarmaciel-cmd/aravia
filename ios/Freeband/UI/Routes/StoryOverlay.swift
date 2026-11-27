import Photos
import SwiftUI
import UIKit

/// Instant (< 200 ms) Story photo & transparent PNG overlay generator: draws the route silhouette,
/// key workout numbers, the user's Arara and an optional "FEITO NO ARAVIA" mark — either on an
/// alpha-transparent PNG (ready to paste as a sticker on Instagram Stories) or composited over a
/// photo from the user's gallery.
enum StoryOverlay {
    static let size = CGSize(width: 1080, height: 1920)

    struct Accent: Identifiable, Hashable {
        let label: String
        let rgb: UInt32
        var id: UInt32 { rgb }
        var uiColor: UIColor { UIColor(hex: rgb) }
    }

    static let accents: [Accent] = [
        Accent(label: "Terracota", rgb: 0xE05A3A),
        Accent(label: "Ouro", rgb: 0xE9B43A),
        Accent(label: "Azul", rgb: 0x4EA3E8),
        Accent(label: "Branco", rgb: 0xFFFFFF),
    ]

    static func renderTransparent(
        track: GpsTrack,
        title: String,
        date: Date,
        stage: AraraStage,
        accentRgb: UInt32,
        showWatermark: Bool = true
    ) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = false
        let renderer = UIGraphicsImageRenderer(size: size, format: format)

        return renderer.image { ctx in
            let cg = ctx.cgContext
            let accent = UIColor(hex: accentRgb)
            let bold = { (sz: CGFloat) -> UIFont in UIFont(name: "Barlow-Bold", size: sz) ?? .boldSystemFont(ofSize: sz) }
            let semi = { (sz: CGFloat) -> UIFont in UIFont(name: "Barlow-SemiBold", size: sz) ?? .systemFont(ofSize: sz, weight: .semibold) }

            let margin: CGFloat = 76
            let df = DateFormatter()
            df.locale = Locale(identifier: "pt_BR")
            df.dateFormat = "d 'de' MMMM · HH:mm"
            drawText(title, at: CGPoint(x: margin, y: 175), font: bold(68), color: .white, align: .left, kern: 0)
            drawText(df.string(from: date), at: CGPoint(x: margin, y: 260), font: semi(36),
                     color: UIColor(red: 245 / 255, green: 240 / 255, blue: 232 / 255, alpha: 0.88), align: .left, kern: 0)

            // User's Arara perched at top-right.
            let birdSize: CGFloat = 240
            let birdLeft = size.width - margin - birdSize
            let birdTop: CGFloat = 135
            let scale = birdSize / 200.0
            cg.saveGState()
            cg.translateBy(x: birdLeft, y: birdTop)
            cg.scaleBy(x: scale, y: scale)
            for layer in AraraView.layers(stage) where layer.role != .fixed {
                cg.addPath(layer.path.cgPath)
                cg.setFillColor(UIColor(hex: layer.rgb).withAlphaComponent(layer.alpha).cgColor)
                cg.fillPath()
            }
            cg.restoreGState()

            // Route silhouette in the middle safe box.
            drawRoute(cg, track: track, box: CGRect(x: 110, y: 440, width: size.width - 220, height: 770), accent: accent)

            // Big workout numbers at bottom.
            let dur = max(1.0, track.duration)
            let distKm = String(format: "%.2f", track.distanceMeters / 1000.0).replacingOccurrences(of: ".", with: ",")
            let clock = RouteNames.clock(dur)
            let avgKmh = track.distanceMeters / dur * 3.6
            let pace: String
            if avgKmh >= 1.0 {
                let s = Int((3600.0 / avgKmh).rounded())
                pace = String(format: "%d:%02d", s / 60, s % 60)
            } else {
                pace = "--:--"
            }

            let baseY: CGFloat = 1300
            drawText("\(distKm) km", at: CGPoint(x: size.width / 2, y: baseY), font: bold(152), color: .white, align: .center, kern: 0)
            drawText("DISTÂNCIA", at: CGPoint(x: size.width / 2, y: baseY + 170), font: bold(34), color: accent, align: .center, kern: 5)

            let rowY = baseY + 255
            drawText(clock, at: CGPoint(x: size.width * 0.28, y: rowY), font: bold(76), color: .white, align: .center, kern: 0)
            drawText("TEMPO", at: CGPoint(x: size.width * 0.28, y: rowY + 90), font: semi(30),
                     color: UIColor(red: 245 / 255, green: 240 / 255, blue: 232 / 255, alpha: 0.86), align: .center, kern: 4)

            drawText("\(pace) /km", at: CGPoint(x: size.width * 0.72, y: rowY), font: bold(76), color: .white, align: .center, kern: 0)
            drawText("RITMO MÉDIO", at: CGPoint(x: size.width * 0.72, y: rowY + 90), font: semi(30),
                     color: UIColor(red: 245 / 255, green: 240 / 255, blue: 232 / 255, alpha: 0.86), align: .center, kern: 4)

            if showWatermark {
                drawText("FEITO NO ARAVIA", at: CGPoint(x: size.width / 2, y: size.height - 155), font: bold(30),
                         color: UIColor(red: 233 / 255, green: 217 / 255, blue: 184 / 255, alpha: 0.9), align: .center, kern: 7)
                let cy = size.height - 96
                cg.saveGState()
                cg.setStrokeColor(UIColor(red: 224 / 255, green: 90 / 255, blue: 58 / 255, alpha: 0.86).cgColor)
                cg.setLineWidth(3)
                for i in -3 ... 3 {
                    let cx = size.width / 2 + CGFloat(i) * 32
                    cg.move(to: CGPoint(x: cx, y: cy - 7))
                    cg.addLine(to: CGPoint(x: cx + 7, y: cy))
                    cg.addLine(to: CGPoint(x: cx, y: cy + 7))
                    cg.addLine(to: CGPoint(x: cx - 7, y: cy))
                    cg.closePath()
                }
                cg.strokePath()
                cg.restoreGState()
            }
        }
    }

    static func renderOverPhoto(_ photo: UIImage, overlay: UIImage) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let renderer = UIGraphicsImageRenderer(size: size, format: format)

        return renderer.image { ctx in
            let cg = ctx.cgContext
            let srcRatio = photo.size.width / max(photo.size.height, 1)
            let dstRatio = size.width / size.height
            let drawRect: CGRect
            if srcRatio > dstRatio {
                let h = size.height
                let w = h * srcRatio
                drawRect = CGRect(x: (size.width - w) / 2, y: 0, width: w, height: h)
            } else {
                let w = size.width
                let h = w / max(srcRatio, 0.01)
                drawRect = CGRect(x: 0, y: (size.height - h) / 2, width: w, height: h)
            }
            photo.draw(in: drawRect)

            // Gentle top & bottom gradient scrims so white text stays readable on bright photos.
            let space = CGColorSpaceCreateDeviceRGB()
            if let topGrad = CGGradient(colorsSpace: space, colors: [
                UIColor(red: 12 / 255, green: 16 / 255, blue: 20 / 255, alpha: 0.58).cgColor,
                UIColor.clear.cgColor,
            ] as CFArray, locations: [0, 1]) {
                cg.drawLinearGradient(topGrad, start: .zero, end: CGPoint(x: 0, y: 440), options: [])
            }
            if let botGrad = CGGradient(colorsSpace: space, colors: [
                UIColor.clear.cgColor,
                UIColor(red: 12 / 255, green: 16 / 255, blue: 20 / 255, alpha: 0.78).cgColor,
            ] as CFArray, locations: [0, 1]) {
                cg.drawLinearGradient(botGrad, start: CGPoint(x: 0, y: size.height - 760), end: CGPoint(x: 0, y: size.height), options: [])
            }

            overlay.draw(in: CGRect(origin: .zero, size: size))
        }
    }

    static func writeTempPng(_ image: UIImage, name: String) -> URL? {
        guard let data = image.pngData() else { return nil }
        let safe = name.replacingOccurrences(of: "/", with: "-")
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("aravia-story-\(safe).png")
        try? data.write(to: url, options: [.atomic])
        return url
    }

    @MainActor
    static func savePngToPhotos(_ image: UIImage) async -> String {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else {
            return "Sem permissão para salvar nas Fotos. Libere em Ajustes › Aravia › Fotos."
        }
        guard let data = image.pngData() else { return "Não deu para gerar o PNG." }
        do {
            try await PHPhotoLibrary.shared().performChanges {
                let req = PHAssetCreationRequest.forAsset()
                req.addResource(with: .photo, data: data, options: nil)
            }
            return "Salvo no rolo da câmera!"
        } catch {
            return "Não deu para salvar: \(error.localizedDescription)"
        }
    }

    private static func drawRoute(_ cg: CGContext, track: GpsTrack, box: CGRect, accent: UIColor) {
        guard track.points.count >= 2 else { return }
        let minLat = track.points.map(\.latitude).min() ?? 0
        let maxLat = track.points.map(\.latitude).max() ?? 0
        let midLat = (minLat + maxLat) / 2.0
        let cosLat = max(cos(midLat * .pi / 180.0), 0.2)
        let xs = track.points.map { $0.longitude * cosLat }
        let ys = track.points.map { -$0.latitude }
        let minX = xs.min() ?? 0, maxX = xs.max() ?? 0
        let minY = ys.min() ?? 0, maxY = ys.max() ?? 0
        let spanX = max(1e-6, maxX - minX)
        let spanY = max(1e-6, maxY - minY)
        let scale = min(Double(box.width) / spanX, Double(box.height) / spanY)
        let ox = box.minX + (box.width - CGFloat(spanX * scale)) / 2
        let oy = box.minY + (box.height - CGFloat(spanY * scale)) / 2

        let path = CGMutablePath()
        for i in track.points.indices {
            let px = ox + CGFloat((xs[i] - minX) * scale)
            let py = oy + CGFloat((ys[i] - minY) * scale)
            if i == 0 { path.move(to: CGPoint(x: px, y: py)) } else { path.addLine(to: CGPoint(x: px, y: py)) }
        }

        cg.saveGState()
        cg.setLineCap(.round)
        cg.setLineJoin(.round)
        cg.addPath(path)
        cg.setStrokeColor(UIColor(white: 0, alpha: 0.45).cgColor)
        cg.setLineWidth(22)
        cg.strokePath()

        cg.addPath(path)
        cg.setStrokeColor(accent.cgColor)
        cg.setLineWidth(13)
        cg.strokePath()

        let sx = ox + CGFloat((xs.first! - minX) * scale)
        let sy = oy + CGFloat((ys.first! - minY) * scale)
        let ex = ox + CGFloat((xs.last! - minX) * scale)
        let ey = oy + CGFloat((ys.last! - minY) * scale)
        cg.setFillColor(UIColor.white.cgColor)
        cg.fillEllipse(in: CGRect(x: sx - 14, y: sy - 14, width: 28, height: 28))
        cg.fillEllipse(in: CGRect(x: ex - 16, y: ey - 16, width: 32, height: 32))
        cg.setFillColor(accent.cgColor)
        cg.fillEllipse(in: CGRect(x: ex - 10, y: ey - 10, width: 20, height: 20))
        cg.restoreGState()
    }

    private static func drawText(
        _ s: String,
        at point: CGPoint,
        font: UIFont,
        color: UIColor,
        align: NSTextAlignment,
        kern: CGFloat
    ) {
        let shadow = NSShadow()
        shadow.shadowBlurRadius = 12
        shadow.shadowOffset = CGSize(width: 0, height: 3)
        shadow.shadowColor = UIColor(white: 0, alpha: 0.58)
        let attrs: [NSAttributedString.Key: Any] = [
            .font: font,
            .foregroundColor: color,
            .kern: kern,
            .shadow: shadow,
        ]
        let ns = NSString(string: s)
        let sz = ns.size(withAttributes: attrs)
        let x: CGFloat = switch align {
        case .center: point.x - sz.width / 2
        case .right: point.x - sz.width
        default: point.x
        }
        ns.draw(at: CGPoint(x: x, y: point.y), withAttributes: attrs)
    }
}
