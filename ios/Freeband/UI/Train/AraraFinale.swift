import SwiftUI
import UIKit

/// The ending of every route video. The arara flies in facing the camera, small and far at
/// first, growing as it comes closer with both wings beating; it lands on a leafy branch, folds
/// its left wing and keeps the right one open, and the camera closes in until that wing fills the
/// frame with the person's numbers on its flight feathers, the bird's right half at the left
/// edge, the Aravia mark below. No loop and no turn: a flat 2D drawing seen from the front the
/// whole time (prototyped as SVG and simulated frame by frame), drawn with Core Graphics on the
/// 1080 × 1920 frame.
enum AraraFinale {
    static let seconds: Double = 5.8

    struct Stat {
        let value: String
        let label: String
    }

    // MARK: timeline (seconds from the start of the ending)

    private static let flyIn = 0.0...2.0
    private static let settle = 2.0...2.6
    private static let closeIn = 2.6...3.5

    static func draw(in ctx: CGContext, size: CGSize, t: Double, map: UIImage, ink: UIColor,
                     stats: [Stat], showBrand: Bool) {
        // Map: a slow push in while the bird approaches, then it darkens for the numbers.
        let zoom = 1 + 0.12 * smooth(progress(t, flyIn))
        ctx.saveGState()
        ctx.translateBy(x: size.width / 2, y: size.height / 2)
        ctx.scaleBy(x: zoom, y: zoom)
        ctx.translateBy(x: -size.width / 2, y: -size.height / 2)
        map.draw(in: CGRect(origin: .zero, size: size))
        ctx.restoreGState()
        let dim = 0.12 + 0.6 * smooth(progress(t, 1.6...3.4))
        ctx.setFillColor(ink.withAlphaComponent(dim).cgColor)
        ctx.fill(CGRect(origin: .zero, size: size))

        // The branch waits where the bird will land and follows the closing camera.
        let perch = frontCamera(smooth(progress(t, closeIn)))
        let branchAlpha = smooth(progress(t, 1.2...1.9))
        if branchAlpha > 0 {
            ctx.saveGState()
            perch.apply(ctx)
            for layer in frontLayers where layer.role == .branch { paint(ctx, layer, alpha: branchAlpha) }
            ctx.restoreGState()
        }

        // The bird: its own camera while flying in, the branch's once it has landed.
        let bird = t < flyIn.upperBound ? flyingCamera(smooth(progress(t, flyIn))) : perch
        let fold = smooth(progress(t, settle))
        let flap = 0.5 * sin(t * 2 * .pi * 4) * (1 - fold)
        ctx.saveGState()
        bird.apply(ctx)
        drawFront(ctx, flap: flap, fold: fold)
        let statsAlpha = smooth(progress(t, 3.3...3.9))
        if statsAlpha > 0 { drawStats(ctx, stats, alpha: statsAlpha) }
        ctx.restoreGState()

        if showBrand {
            let brand = smooth(progress(t, 3.7...4.3))
            if brand > 0 { drawBrand(ctx, size: size, alpha: brand) }
        }
    }

    /// Far and small at the upper right, growing and coming down onto the perch (grid point
    /// 110, 150 is the bird's middle), with a slight rise and fall on the way.
    private static func flyingCamera(_ p: Double) -> Camera {
        let k = CGFloat(p)
        let scale = 0.7 + (wide.scale - 0.7) * k
        let end = wide.point(110, 150)
        let centre = CGPoint(x: 760 + (end.x - 760) * k,
                             y: 420 + (end.y - 420) * k - 40 * CGFloat(sin(p * .pi)))
        return Camera(scale: scale, origin: CGPoint(x: centre.x - 110 * scale, y: centre.y - 150 * scale))
    }

    // MARK: cameras on the front-view grid

    private struct Camera {
        var scale: CGFloat
        var origin: CGPoint
        func apply(_ ctx: CGContext) {
            ctx.translateBy(x: origin.x, y: origin.y)
            ctx.scaleBy(x: scale, y: scale)
        }
        func point(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: origin.x + x * scale, y: origin.y + y * scale) }
    }

    /// Wide: the whole bird on the branch. Close: the wing fills the frame, the bird's centre line
    /// (grid x 110) at the left edge so only its right half shows.
    private static let wide = Camera(scale: 3, origin: CGPoint(x: 60, y: 444))
    private static let close = Camera(scale: 5, origin: CGPoint(x: -550, y: 170))

    private static func frontCamera(_ p: Double) -> Camera {
        let k = CGFloat(p)
        return Camera(scale: wide.scale + (close.scale - wide.scale) * k,
                      origin: CGPoint(x: wide.origin.x + (close.origin.x - wide.origin.x) * k,
                                      y: wide.origin.y + (close.origin.y - wide.origin.y) * k))
    }

    // MARK: front view

    /// `flap` swings both wings (radians) while flying; `fold` (0 to 1) brings the left wing down
    /// against the body on landing, where the folded-wing drawing takes over.
    private static func drawFront(_ ctx: CGContext, flap: Double, fold: Double) {
        let shoulder = CGPoint(x: 142, y: 136)
        func around(_ angle: CGFloat, scale: CGFloat = 1) -> CGAffineTransform {
            CGAffineTransform(translationX: -shoulder.x, y: -shoulder.y)
                .concatenating(CGAffineTransform(scaleX: scale, y: scale))
                .concatenating(CGAffineTransform(rotationAngle: angle))
                .concatenating(CGAffineTransform(translationX: shoulder.x, y: shoulder.y))
        }
        // The left wing is the right one mirrored about the body's centre line (x 110).
        let mirror = CGAffineTransform(a: -1, b: 0, c: 0, d: 1, tx: 220, ty: 0)
        let right = around(CGFloat(-flap))
        let left = around(CGFloat(-flap + fold * 100 * .pi / 180), scale: CGFloat(1 - 0.65 * fold)).concatenating(mirror)
        for layer in frontLayers where layer.role != .branch {
            switch layer.role {
            case .wing:
                if fold < 1 { paint(ctx, path: layer.path.applying(left), rgb: layer.rgb, alpha: layer.alpha * (1 - fold * 0.6)) }
                paint(ctx, path: layer.path.applying(right), rgb: layer.rgb, alpha: layer.alpha)
            case .foldwing:
                if fold > 0.6 { paint(ctx, path: layer.path, rgb: layer.rgb, alpha: layer.alpha * min(1, (fold - 0.6) / 0.4)) }
            default:
                paint(ctx, path: layer.path, rgb: layer.rgb, alpha: layer.alpha)
            }
        }
    }

    /// The numbers on the open wing's flight feathers, in grid units so they scale with the
    /// camera. Each line is centred in the stretch of feathers at its height (between the coloured
    /// bands near the shoulder and the feather tips, with a margin) and its font shrinks until it
    /// fits there whole, so no number ever runs off the wing.
    private static func drawStats(_ ctx: CGContext, _ stats: [Stat], alpha: Double) {
        let shown = Array(stats.prefix(3))
        guard !shown.isEmpty else { return }
        let shoulder = CGPoint(x: 142, y: 136)
        let inner: CGFloat = 74      // past the red, yellow and teal bands
        let outer: CGFloat = 132     // short of the feather tips and the gaps between them
        let top: CGFloat = 92, bottom: CGFloat = 206
        let row = (bottom - top) / CGFloat(shown.count)
        let wanted: [CGFloat] = shown.count == 1 ? [34] : shown.count == 2 ? [30, 24] : [27, 22, 19]
        for (i, stat) in shown.enumerated() {
            let lineTop = top + CGFloat(i) * row + CGFloat(1 - alpha) * 6
            let middle = lineTop + row * 0.45
            let dy = middle - shoulder.y
            let left = shoulder.x + (inner * inner - min(dy * dy, inner * inner)).squareRoot()
            let right = shoulder.x + (outer * outer - min(dy * dy, outer * outer)).squareRoot()
            let width = max(20, right - left)
            let value = fitted(stat.value, name: "Barlow-SemiBold", size: wanted[i], width: width, kern: 0)
            let label = fitted(stat.label.uppercased(), name: "Barlow-SemiBold", size: 8, width: width, kern: 1.2)
            text(stat.value, font: value, color: UIColor.white.withAlphaComponent(alpha),
                 rect: CGRect(x: left, y: lineTop, width: width, height: value.pointSize * 1.2))
            text(stat.label.uppercased(), font: label, color: UIColor.white.withAlphaComponent(0.85 * alpha),
                 rect: CGRect(x: left, y: lineTop + value.pointSize * 1.08, width: width, height: label.pointSize * 1.4),
                 kern: 1.2)
        }
    }

    /// The largest font up to `size` at which `s` fits in `width`.
    private static func fitted(_ s: String, name: String, size: CGFloat, width: CGFloat, kern: CGFloat) -> UIFont {
        var points = size
        while points > 6 {
            let f = font(name, points)
            if (s as NSString).size(withAttributes: [.font: f, .kern: kern]).width <= width { return f }
            points -= 0.5
        }
        return font(name, 6)
    }

    // MARK: brand

    private static func drawBrand(_ ctx: CGContext, size: CGSize, alpha: Double) {
        let y = size.height - 300
        text("ARAVIA", font: font("Barlow-SemiBold", 80), color: UIColor(hex: 0xE9D9B8).withAlphaComponent(alpha),
             rect: CGRect(x: 0, y: y, width: size.width, height: 96), kern: 22)
        ctx.setStrokeColor(UIColor(hex: 0xE05A3A).withAlphaComponent(alpha).cgColor)
        ctx.setLineWidth(4)
        let count = 9
        let step: CGFloat = 40
        let startX = size.width / 2 - step * CGFloat(count - 1) / 2
        for i in 0..<count {
            let cx = startX + CGFloat(i) * step, cy = y + 124
            ctx.move(to: CGPoint(x: cx, y: cy - 10))
            ctx.addLine(to: CGPoint(x: cx + 10, y: cy))
            ctx.addLine(to: CGPoint(x: cx, y: cy + 10))
            ctx.addLine(to: CGPoint(x: cx - 10, y: cy))
            ctx.closePath()
        }
        ctx.strokePath()
    }

    // MARK: helpers

    private static func paint(_ ctx: CGContext, _ layer: Layer, alpha: Double = 1) {
        paint(ctx, path: layer.path, rgb: layer.rgb, alpha: layer.alpha * alpha)
    }

    private static func paint(_ ctx: CGContext, path: Path, rgb: UInt32, alpha: Double) {
        ctx.addPath(path.cgPath)
        ctx.setFillColor(UIColor(hex: rgb).withAlphaComponent(alpha).cgColor)
        ctx.fillPath()
    }

    private static func font(_ name: String, _ size: CGFloat) -> UIFont {
        UIFont(name: name, size: size) ?? .systemFont(ofSize: size, weight: .bold)
    }

    private static func text(_ s: String, font: UIFont, color: UIColor, rect: CGRect, kern: CGFloat = 0) {
        let style = NSMutableParagraphStyle()
        style.alignment = .center
        style.lineBreakMode = .byClipping
        let shadow = NSShadow()
        shadow.shadowColor = UIColor.black.withAlphaComponent(0.3)
        shadow.shadowBlurRadius = 2
        shadow.shadowOffset = CGSize(width: 0, height: 0.6)
        (s as NSString).draw(in: rect, withAttributes: [.font: font, .foregroundColor: color, .paragraphStyle: style,
                                                        .kern: kern, .shadow: shadow])
    }

    private static func progress(_ t: Double, _ r: ClosedRange<Double>) -> Double {
        min(1, max(0, (t - r.lowerBound) / (r.upperBound - r.lowerBound)))
    }

    private static func smooth(_ x: Double) -> Double { x * x * (3 - 2 * x) }

    /// Overshoots a little before settling: the wing snaps open.
    private static func backOut(_ x: Double) -> Double {
        let c1 = 1.70158, c3 = c1 + 1
        return 1 + c3 * pow(x - 1, 3) + c1 * pow(x - 1, 2)
    }

    private static func bezier(_ a: CGPoint, _ b: CGPoint, _ c: CGPoint, _ d: CGPoint, _ t: Double) -> CGPoint {
        let u = 1 - t
        let w0 = u * u * u, w1 = 3 * u * u * t, w2 = 3 * u * t * t, w3 = t * t * t
        return CGPoint(x: CGFloat(w0) * a.x + CGFloat(w1) * b.x + CGFloat(w2) * c.x + CGFloat(w3) * d.x,
                       y: CGFloat(w0) * a.y + CGFloat(w1) * b.y + CGFloat(w2) * c.y + CGFloat(w3) * d.y)
    }

    // MARK: shapes

    enum Role { case fixed, branch, tail, body, foldwing, farwing, feet, head, eye, wing }

    struct Layer {
        let role: Role
        let rgb: UInt32
        let alpha: Double
        let path: Path
    }

    private static func F(_ role: Role, _ rgb: UInt32, _ alpha: Double, _ d: String) -> Layer {
        Layer(role: role, rgb: rgb, alpha: alpha, path: AraraView.parse(d))
    }

    /// From the front on a 320 × 300 grid, the right wing drawn spread (the animation folds it),
    /// with a leafy branch.
    static let frontLayers: [Layer] = [
        F(.branch, 0x7A452B, 1.0, "M-60 242 C80 234 230 234 380 240 L380 262 C230 258 80 258 -60 266 Z"),
        F(.branch, 0x5E3420, 1.0, "M-20 248 C20 246 60 246 100 247 L100 249 C60 248 20 248 -20 250 Z"),
        F(.branch, 0x5E3420, 1.0, "M180 245 C220 244 260 245 320 246 L320 248 C260 247 220 246 180 247 Z"),
        F(.branch, 0x7A452B, 1.0, "M250 240 C262 226 274 216 290 208 L293 212 C278 220 266 230 258 242 Z"),
        F(.branch, 0x3F7D3A, 1.0, "M290 208 C300 192 318 188 330 192 C322 204 306 212 290 208 Z"),
        F(.branch, 0x2F6B32, 1.0, "M278 216 C280 198 292 186 304 182 C304 198 294 210 278 216 Z"),
        F(.branch, 0x3F7D3A, 1.0, "M30 244 C24 228 30 214 42 206 C46 222 42 236 30 244 Z"),
        F(.tail, 0xB92D25, 1.0, "M99 206 C96 246 99 278 104 300 L116 300 C118 276 118 246 116 206 Z"),
        F(.tail, 0xD2362B, 1.0, "M104 206 C103 246 106 278 110 300 L126 300 C128 278 127 246 122 206 Z"),
        F(.tail, 0x2F6DB5, 1.0, "M106 282 L110 300 L126 300 L127 282 Z"),
        F(.wing, 0x2F6DB5, 1.0, "M174.6 160.9 L213.7 198.1 L240.6 232.5 L240.5 241.7 L236.7 248.8 L229.0 251.4 L219.9 249.9 L190.7 217.4 L160.8 172.4 Z"),
        F(.wing, 0x2A5FA0, 1.0, "M176.3 158.5 L218.5 193.6 L248.3 226.7 L248.9 235.9 L245.6 243.3 L238.1 246.3 L228.9 245.4 L196.9 214.4 L163.3 171.0 Z"),
        F(.wing, 0x2F6DB5, 1.0, "M180.2 150.9 L231.0 177.9 L269.3 205.6 L271.8 214.5 L270.1 222.4 L263.4 226.9 L254.2 228.0 L214.2 202.8 L170.1 165.8 Z"),
        F(.wing, 0x3A7CC8, 1.0, "M182.5 142.6 L240.3 159.5 L286.0 179.6 L290.3 187.8 L290.3 195.9 L284.7 201.7 L275.9 204.7 L229.1 187.3 L175.7 159.3 Z"),
        F(.wing, 0x2A5FA0, 1.0, "M183.0 134.1 L245.7 139.1 L297.3 149.7 L303.1 156.8 L304.8 164.7 L300.5 171.6 L292.6 176.3 L240.5 168.6 L179.8 151.8 Z"),
        F(.wing, 0x2F6DB5, 1.0, "M181.7 125.6 L244.1 117.4 L296.7 117.1 L303.9 122.8 L307.2 130.2 L304.5 137.8 L297.7 144.1 L245.1 147.4 L182.3 143.6 Z"),
        F(.wing, 0x3A7CC8, 1.0, "M178.6 117.6 L235.4 97.3 L284.2 86.6 L292.4 90.8 L297.2 97.3 L296.1 105.3 L290.7 112.8 L242.6 126.4 L183.0 135.1 Z"),
        F(.wing, 0x2A5FA0, 1.0, "M174.0 110.4 L222.8 79.9 L266.0 60.5 L274.9 62.8 L280.9 68.3 L281.5 76.3 L277.8 84.8 L236.0 106.9 L181.9 126.6 Z"),
        F(.wing, 0x2F6DB5, 1.0, "M168.0 104.3 L207.3 66.0 L243.3 39.7 L252.5 40.1 L259.5 44.2 L261.7 52.0 L259.9 61.0 L225.7 89.6 L179.1 118.5 Z"),
        F(.wing, 0x3A7CC8, 1.0, "M160.8 99.6 L189.5 56.0 L217.6 24.9 L226.7 23.4 L234.4 25.9 L238.2 33.0 L238.3 42.3 L212.5 75.3 L174.6 111.1 Z"),
        F(.wing, 0x1E3F78, 0.6, "M187.8 80.5 L230.2 30.3 L230.8 30.8 L188.7 81.2 Z"),
        F(.wing, 0x1E3F78, 0.6, "M198.4 91.2 L254.5 47.6 L255.0 48.2 L199.1 92.1 Z"),
        F(.wing, 0x1E3F78, 0.6, "M206.5 103.9 L275.3 70.5 L275.6 71.3 L207.0 105.0 Z"),
        F(.wing, 0x1E3F78, 0.6, "M211.7 118.0 L291.2 98.4 L291.4 99.2 L212.0 119.2 Z"),
        F(.wing, 0x1E3F78, 0.6, "M213.9 132.9 L301.2 130.0 L301.2 130.8 L214.0 134.1 Z"),
        F(.wing, 0x1E3F78, 0.6, "M213.0 147.9 L298.9 163.3 L298.8 164.1 L212.8 149.1 Z"),
        F(.wing, 0x1E3F78, 0.6, "M209.0 162.4 L284.8 193.3 L284.5 194.0 L208.5 163.5 Z"),
        F(.wing, 0x1E3F78, 0.6, "M202.0 175.8 L265.3 218.7 L264.9 219.4 L201.4 176.8 Z"),
        F(.wing, 0x1E3F78, 0.6, "M192.4 187.4 L241.7 238.7 L241.1 239.2 L191.6 188.2 Z"),
        F(.wing, 0x1E3F78, 0.6, "M182.8 195.4 L219.8 250.7 L219.2 251.1 L181.8 196.0 Z"),
        F(.wing, 0x2F8C9A, 1.0, "M178.9 81.3 L186.6 80.8 L187.7 88.4 L195.4 89.2 L195.2 96.9 L202.6 99.0 L201.1 106.6 L208.0 109.9 L205.2 117.1 L211.5 121.5 L207.5 128.1 L213.0 133.6 L207.9 139.5 L212.3 145.8 L206.4 150.7 L209.6 157.6 L202.9 161.4 L204.9 168.9 L197.7 171.5 L198.4 179.1 L190.8 180.4 L190.2 188.1 L182.5 188.1 L180.6 195.6 L173.0 194.3 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 Z"),
        F(.wing, 0xE9B43A, 1.0, "M171.1 92.9 L177.2 92.4 L178.0 98.5 L184.1 99.1 L183.9 105.2 L189.8 106.8 L188.5 112.8 L194.1 115.4 L191.8 121.1 L196.8 124.6 L193.6 129.8 L198.0 134.1 L193.9 138.7 L197.5 143.7 L192.7 147.6 L195.3 153.1 L190.0 156.0 L191.6 161.9 L185.9 163.9 L186.5 170.0 L180.4 171.0 L180.0 177.1 L173.9 177.1 L172.4 183.0 L166.4 181.9 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 Z"),
        F(.wing, 0x8FA83A, 1.0, "M171.4 96.2 Q175.4 100.2 179.4 96.2 Q175.4 98.2 171.4 96.2 Z"),
        F(.wing, 0x8FA83A, 1.0, "M179.5 104.7 Q183.5 108.7 187.5 104.7 Q183.5 106.7 179.5 104.7 Z"),
        F(.wing, 0x8FA83A, 1.0, "M185.5 114.8 Q189.5 118.8 193.5 114.8 Q189.5 116.8 185.5 114.8 Z"),
        F(.wing, 0x8FA83A, 1.0, "M189.0 126.1 Q193.0 130.1 197.0 126.1 Q193.0 128.1 189.0 126.1 Z"),
        F(.wing, 0x8FA83A, 1.0, "M190.0 137.8 Q194.0 141.8 198.0 137.8 Q194.0 139.8 190.0 137.8 Z"),
        F(.wing, 0x8FA83A, 1.0, "M188.2 149.5 Q192.2 153.5 196.2 149.5 Q192.2 151.5 188.2 149.5 Z"),
        F(.wing, 0x8FA83A, 1.0, "M183.9 160.4 Q187.9 164.4 191.9 160.4 Q187.9 162.4 183.9 160.4 Z"),
        F(.wing, 0x8FA83A, 1.0, "M177.2 170.1 Q181.2 174.1 185.2 170.1 Q181.2 172.1 177.2 170.1 Z"),
        F(.wing, 0x8FA83A, 1.0, "M168.6 178.1 Q172.6 182.1 176.6 178.1 Q172.6 180.1 168.6 178.1 Z"),
        F(.wing, 0xD2362B, 1.0, "M163.2 102.1 L167.9 101.7 L168.9 106.4 L173.6 106.8 L173.7 111.6 L178.2 112.8 L177.5 117.5 L181.8 119.6 L180.2 124.1 L184.0 126.9 L181.7 131.0 L185.0 134.4 L181.9 138.1 L184.6 142.0 L180.9 145.1 L182.8 149.5 L178.7 151.8 L179.8 156.5 L175.4 158.1 L175.6 162.8 L170.9 163.6 L170.4 168.3 L165.6 168.3 L164.2 172.8 L159.5 172.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 L142.0 136.0 Z"),
        F(.wing, 0xB92D25, 1.0, "M150.8 122.6 Q154.0 126.5 157.2 122.6 Q154.0 124.5 150.8 122.6 Z"),
        F(.wing, 0xB92D25, 1.0, "M154.2 126.7 Q157.4 130.6 160.6 126.7 Q157.4 128.6 154.2 126.7 Z"),
        F(.wing, 0xB92D25, 1.0, "M156.3 131.6 Q159.5 135.5 162.7 131.6 Q159.5 133.6 156.3 131.6 Z"),
        F(.wing, 0xB92D25, 1.0, "M156.8 136.9 Q160.0 140.8 163.2 136.9 Q160.0 138.9 156.8 136.9 Z"),
        F(.wing, 0xB92D25, 1.0, "M155.7 142.2 Q158.9 146.0 162.1 142.2 Q158.9 144.1 155.7 142.2 Z"),
        F(.wing, 0xB92D25, 1.0, "M153.2 146.8 Q156.4 150.7 159.6 146.8 Q156.4 148.8 153.2 146.8 Z"),
        F(.wing, 0xB92D25, 1.0, "M149.4 150.6 Q152.6 154.4 155.8 150.6 Q152.6 152.5 149.4 150.6 Z"),
        F(.wing, 0xB92D25, 1.0, "M158.9 113.7 Q162.1 117.5 165.3 113.7 Q162.1 115.6 158.9 113.7 Z"),
        F(.wing, 0xB92D25, 1.0, "M164.5 120.5 Q167.7 124.4 170.9 120.5 Q167.7 122.5 164.5 120.5 Z"),
        F(.wing, 0xB92D25, 1.0, "M167.9 128.7 Q171.1 132.6 174.3 128.7 Q171.1 130.7 167.9 128.7 Z"),
        F(.wing, 0xB92D25, 1.0, "M168.8 137.6 Q172.0 141.4 175.2 137.6 Q172.0 139.5 168.8 137.6 Z"),
        F(.wing, 0xB92D25, 1.0, "M167.0 146.3 Q170.2 150.1 173.4 146.3 Q170.2 148.2 167.0 146.3 Z"),
        F(.wing, 0xB92D25, 1.0, "M162.8 154.1 Q166.0 157.9 169.2 154.1 Q166.0 156.0 162.8 154.1 Z"),
        F(.wing, 0xB92D25, 1.0, "M156.4 160.3 Q159.6 164.1 162.8 160.3 Q159.6 162.2 156.4 160.3 Z"),
        F(.body, 0xD2362B, 1.0, "M110 108 C138 108 150 140 150 172 C150 206 132 228 110 228 C88 228 70 206 70 172 C70 140 82 108 110 108 Z"),
        F(.body, 0xB92D25, 1.0, "M92.5 132.0 Q96.5 137.0 100.5 132.0 Q96.5 134.5 92.5 132.0 Z"),
        F(.body, 0xB92D25, 1.0, "M101.5 132.0 Q105.5 137.0 109.5 132.0 Q105.5 134.5 101.5 132.0 Z"),
        F(.body, 0xB92D25, 1.0, "M110.5 132.0 Q114.5 137.0 118.5 132.0 Q114.5 134.5 110.5 132.0 Z"),
        F(.body, 0xB92D25, 1.0, "M119.5 132.0 Q123.5 137.0 127.5 132.0 Q123.5 134.5 119.5 132.0 Z"),
        F(.body, 0xB92D25, 1.0, "M88.0 146.0 Q92.0 151.0 96.0 146.0 Q92.0 148.5 88.0 146.0 Z"),
        F(.body, 0xB92D25, 1.0, "M100.0 146.0 Q104.0 151.0 108.0 146.0 Q104.0 148.5 100.0 146.0 Z"),
        F(.body, 0xB92D25, 1.0, "M112.0 146.0 Q116.0 151.0 120.0 146.0 Q116.0 148.5 112.0 146.0 Z"),
        F(.body, 0xB92D25, 1.0, "M124.0 146.0 Q128.0 151.0 132.0 146.0 Q128.0 148.5 124.0 146.0 Z"),
        F(.body, 0xB92D25, 1.0, "M86.5 160.0 Q90.5 165.0 94.5 160.0 Q90.5 162.5 86.5 160.0 Z"),
        F(.body, 0xB92D25, 1.0, "M99.5 160.0 Q103.5 165.0 107.5 160.0 Q103.5 162.5 99.5 160.0 Z"),
        F(.body, 0xB92D25, 1.0, "M112.5 160.0 Q116.5 165.0 120.5 160.0 Q116.5 162.5 112.5 160.0 Z"),
        F(.body, 0xB92D25, 1.0, "M125.5 160.0 Q129.5 165.0 133.5 160.0 Q129.5 162.5 125.5 160.0 Z"),
        F(.body, 0xB92D25, 1.0, "M86.5 174.0 Q90.5 179.0 94.5 174.0 Q90.5 176.5 86.5 174.0 Z"),
        F(.body, 0xB92D25, 1.0, "M99.5 174.0 Q103.5 179.0 107.5 174.0 Q103.5 176.5 99.5 174.0 Z"),
        F(.body, 0xB92D25, 1.0, "M112.5 174.0 Q116.5 179.0 120.5 174.0 Q116.5 176.5 112.5 174.0 Z"),
        F(.body, 0xB92D25, 1.0, "M125.5 174.0 Q129.5 179.0 133.5 174.0 Q129.5 176.5 125.5 174.0 Z"),
        F(.body, 0xB92D25, 1.0, "M88.0 188.0 Q92.0 193.0 96.0 188.0 Q92.0 190.5 88.0 188.0 Z"),
        F(.body, 0xB92D25, 1.0, "M100.0 188.0 Q104.0 193.0 108.0 188.0 Q104.0 190.5 100.0 188.0 Z"),
        F(.body, 0xB92D25, 1.0, "M112.0 188.0 Q116.0 193.0 120.0 188.0 Q116.0 190.5 112.0 188.0 Z"),
        F(.body, 0xB92D25, 1.0, "M124.0 188.0 Q128.0 193.0 132.0 188.0 Q128.0 190.5 124.0 188.0 Z"),
        F(.body, 0xB92D25, 1.0, "M92.5 202.0 Q96.5 207.0 100.5 202.0 Q96.5 204.5 92.5 202.0 Z"),
        F(.body, 0xB92D25, 1.0, "M101.5 202.0 Q105.5 207.0 109.5 202.0 Q105.5 204.5 101.5 202.0 Z"),
        F(.body, 0xB92D25, 1.0, "M110.5 202.0 Q114.5 207.0 118.5 202.0 Q114.5 204.5 110.5 202.0 Z"),
        F(.body, 0xB92D25, 1.0, "M119.5 202.0 Q123.5 207.0 127.5 202.0 Q123.5 204.5 119.5 202.0 Z"),
        F(.foldwing, 0x2A5FA0, 1.0, "M78 150 C66 168 64 204 76 234 C84 238 90 234 92 226 C90 198 90 172 92 154 Z"),
        F(.foldwing, 0x2F6DB5, 1.0, "M84 156 C78 176 78 204 84 230 L86 230 C82 204 82 178 88 158 Z"),
        F(.foldwing, 0x2F8C9A, 1.0, "M76 146 C70 154 68 162 68 170 C76 168 86 168 92 168 L92 150 Z"),
        F(.foldwing, 0xE9B43A, 1.0, "M78 136 C72 146 70 154 70 160 C78 156 86 154 92 154 L92 138 Z"),
        F(.foldwing, 0xD2362B, 1.0, "M82 122 C76 130 74 138 74 146 C80 142 86 140 92 140 L92 124 Z"),
        F(.head, 0xD2362B, 1.0, "E 110 88 32 31"),
        F(.head, 0xF3ECE1, 1.0, "E 95 90 11.5 13"),
        F(.head, 0xF3ECE1, 1.0, "E 125 90 11.5 13"),
        F(.head, 0xB8473C, 1.0, "M88 94 Q95 96 102 94 L102 95 Q95 97 88 95 Z"),
        F(.head, 0xB8473C, 1.0, "M88 98 Q95 100 102 98 L102 99 Q95 101 88 99 Z"),
        F(.head, 0xB8473C, 1.0, "M118 94 Q125 96 132 94 L132 95 Q125 97 118 95 Z"),
        F(.head, 0xB8473C, 1.0, "M118 98 Q125 100 132 98 L132 99 Q125 101 118 99 Z"),
        F(.eye, 0xF2D98A, 1.0, "E 96 86 4.6 4.6"),
        F(.eye, 0xF2D98A, 1.0, "E 124 86 4.6 4.6"),
        F(.eye, 0x111111, 1.0, "E 96 86 2.7 2.7"),
        F(.eye, 0x111111, 1.0, "E 124 86 2.7 2.7"),
        F(.eye, 0xFFFFFF, 1.0, "E 97 85 1 1"),
        F(.eye, 0xFFFFFF, 1.0, "E 125 85 1 1"),
        F(.head, 0xEDE3D1, 1.0, "M100 96 C100 86 120 86 120 96 C120 110 114 120 110 126 C106 120 100 110 100 96 Z"),
        F(.head, 0x3A302B, 1.0, "M107 120 C108 123 109 125 110 126 C111 125 112 123 113 120 C111 121 109 121 107 120 Z"),
        F(.head, 0x2B2522, 1.0, "M104 108 C106 116 108 120 110 122 C112 120 114 116 116 108 C112 112 108 112 104 108 Z"),
        F(.fixed, 0x6F6E74, 1.0, "M92 246 C95 242 104 242 106 246 L106 251 C103 249 95 249 92 251 Z"),
        F(.fixed, 0x6F6E74, 1.0, "M114 246 C117 242 126 242 128 246 L128 251 C125 249 117 249 114 251 Z"),
        F(.fixed, 0x3A393E, 1.0, "M93 251 L92 255 L95 253 Z"),
        F(.fixed, 0x3A393E, 1.0, "M105 251 L104 255 L107 253 Z"),
        F(.fixed, 0x3A393E, 1.0, "M115 251 L114 255 L117 253 Z"),
        F(.fixed, 0x3A393E, 1.0, "M127 251 L126 255 L129 253 Z"),
    ]
}
