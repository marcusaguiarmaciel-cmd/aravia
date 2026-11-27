import SwiftUI

/// The arara (scarlet macaw) that grows as the person moves: egg, chick, young, adult, royal and
/// legendary. An original drawing in the app's colours, made of a few flat shapes.
enum AraraStage: Int, CaseIterable, Comparable {
    case egg, chick, young, adult, royal, legend

    static func < (a: AraraStage, b: AraraStage) -> Bool { a.rawValue < b.rawValue }

    var name: String {
        switch self {
        case .egg: "Ovo"
        case .chick: "Filhote"
        case .young: "Jovem"
        case .adult: "Adulta"
        case .royal: "Arara-real"
        case .legend: "Lendária"
        }
    }

    /// Points needed to reach the stage.
    var threshold: Int {
        switch self {
        case .egg: 0
        case .chick: 30
        case .young: 150
        case .adult: 500
        case .royal: 1200
        case .legend: 3000
        }
    }

    var line: String {
        switch self {
        case .egg: "Está chocando. Cada passo seu esquenta o ovo."
        case .chick: "Nasceu! Ainda sem penas de voo, mas já com fome de caminhada."
        case .young: "As cores estão chegando e a cauda começou a crescer."
        case .adult: "Plumagem completa: vermelho de urucum, ouro e azul de rio."
        case .royal: "Cauda longa e grafismos no galho. Poucas chegam aqui."
        case .legend: "Lendária. A mata inteira conhece o seu voo."
        }
    }

    static func stage(for xp: Int) -> AraraStage {
        allCases.last { xp >= $0.threshold } ?? .egg
    }
}

/// How the points are earned: 1 per 1,000 steps the watch counted, 10 per workout logged in
/// Aravia and 10 per GPS route.
struct AraraProgress {
    let xp: Int
    var stage: AraraStage { AraraStage.stage(for: xp) }
    var next: AraraStage? { AraraStage(rawValue: stage.rawValue + 1) }
    /// 0 to 1 towards the next stage.
    var fraction: Double {
        guard let next else { return 1 }
        return Double(xp - stage.threshold) / Double(next.threshold - stage.threshold)
    }

    init(totalSteps: Int, workouts: Int, routes: Int) {
        xp = totalSteps / 1000 + workouts * 10 + routes * 10
    }

    init(xp: Int) { self.xp = xp }
}

struct AraraView: View {
    let stage: AraraStage
    /// When the arara last reached a new stage: feathers burst for a moment.
    var celebratedAt: Date?
    /// Tapping makes it flap (or the egg rock, or the chick hop) and chirp.
    var interactive = true
    /// Optional explicit override; nil follows `AraraCare.shared.resting`.
    var resting: Bool? = nil

    @StateObject private var care = AraraCare.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var pokedAt: Date?

    private var isResting: Bool { resting ?? care.resting }

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 60, paused: reduceMotion && celebratedAt == nil)) { timeline in
            Canvas { context, size in
                let side = min(size.width, size.height)
                context.translateBy(x: (size.width - side) / 2, y: (size.height - side) / 2)
                context.scaleBy(x: side / 200, y: side / 200)
                let t = timeline.date.timeIntervalSinceReferenceDate
                let poke = pokedAt.map { timeline.date.timeIntervalSince($0) } ?? 99
                let pose = reduceMotion ? Pose.still : Pose(stage: stage, time: timeline.date, pokedAt: pokedAt, resting: isResting)

                Self.drawSunAura(in: &context, stage: stage, t: t, resting: isResting)

                if let celebratedAt {
                    let dt = timeline.date.timeIntervalSince(celebratedAt)
                    if dt < 1.0 {
                        let s = 1 + 0.08 * sin(dt * .pi * 3) * (1 - dt)
                        context.translateBy(x: 100, y: 110)
                        context.scaleBy(x: s, y: s)
                        context.translateBy(x: -100, y: -110)
                    }
                }
                // The card colour as it is really drawn decides the egg's colour (see eggOnLight).
                let card = Color.surface.resolve(in: context.environment)
                let lightCard = 0.2126 * card.red + 0.7152 * card.green + 0.0722 * card.blue > 0.5
                for layer in Self.layers(stage) {
                    let path = layer.path.applying(pose.transform(for: layer))
                    let rgb = color(layer, lightCard: lightCard)
                    if layer.shaded {
                        let (top, bottom) = Self.shades(rgb)
                        let box = path.boundingRect
                        context.fill(path, with: .linearGradient(
                            Gradient(colors: [Color(uiColor: UIColor(hex: top)).opacity(layer.alpha),
                                              Color(uiColor: UIColor(hex: bottom)).opacity(layer.alpha)]),
                            startPoint: CGPoint(x: box.minX, y: box.minY),
                            endPoint: CGPoint(x: box.minX + box.width * 0.25, y: box.maxY)))
                    } else {
                        context.fill(path, with: .color(Color(uiColor: UIColor(hex: rgb)).opacity(layer.alpha)))
                    }
                }

                if isResting && stage != .egg {
                    Self.drawSleepZ(in: &context, t: t)
                }
                if poke < 0.75 && stage != .egg {
                    Self.drawChirpArcs(in: &context, poke: poke)
                    Self.drawFeathers(in: &context, since: poke, count: 6)
                }
                if let celebratedAt {
                    Self.drawFeathers(in: &context, since: timeline.date.timeIntervalSince(celebratedAt), count: 16)
                }
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipped()
        .contentShape(Rectangle())
        .onTapGesture {
            guard interactive else { return }
            pokedAt = Date()
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        }
        .accessibilityLabel(Text(verbatim: isResting ? "Arara descansando: \(stage.name)" : "Arara: \(stage.name)"))
    }

    /// Warm Aravia sun-disc halo & drifting ambient forest motes tailored to each stage (shifts to twilight when resting).
    private static func drawSunAura(in context: inout GraphicsContext, stage: AraraStage, t: Double, resting: Bool) {
        let baseRadius: CGFloat = 54 + CGFloat(stage.rawValue) * 3.8
        let pulse = CGFloat(sin(t * (resting ? 0.9 : 1.7)) * 2.2)
        let r = baseRadius + pulse
        let center = CGPoint(x: 100, y: 98)
        let auraColor = resting
            ? Color(red: 0x5B / 255, green: 0x7A / 255, blue: 0xB8 / 255)
            : Color(red: 0xE5 / 255, green: 0xA0 / 255, blue: 0x24 / 255)
        let circle = Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: r * 2, height: r * 2))
        context.fill(circle, with: .radialGradient(
            Gradient(colors: [auraColor.opacity(resting ? 0.22 : 0.28), auraColor.opacity(0.0)]),
            center: center,
            startRadius: 6,
            endRadius: r
        ))
        // Subtle ambient motes orbiting gently around the perch.
        let moteColors: [UInt32] = resting ? [0x8FA8D8, 0xC9D6F0] : [0xE9B43A, 0xE05A3A, 0x4EA3E8]
        let count = 3 + stage.rawValue
        for i in 0 ..< count {
            let speed = resting ? 0.22 : 0.45
            let angle = t * speed + Double(i) * (2.0 * .pi / Double(count))
            let rad = Double(baseRadius) * (0.78 + 0.16 * sin(t * 0.7 + Double(i)))
            let mx = 100 + cos(angle) * rad
            let my = 96 + sin(angle) * rad * 0.72
            let alpha = 0.28 + 0.22 * sin(t * 1.9 + Double(i) * 1.7)
            let dot = Path(ellipseIn: CGRect(x: mx - 2, y: my - 2, width: 4, height: 4))
            context.fill(dot, with: .color(Color(uiColor: UIColor(hex: moteColors[i % moteColors.count])).opacity(alpha)))
        }
    }

    /// Acoustic chirp arcs radiating from the beak on tap.
    private static func drawChirpArcs(in context: inout GraphicsContext, poke: Double) {
        let p = min(max(poke / 0.75, 0), 1)
        let origin = CGPoint(x: 132, y: 66)
        for i in 0 ..< 2 {
            let local = max(0, min(1, p * 1.3 - Double(i) * 0.22))
            guard local > 0, local < 1 else { continue }
            let r = CGFloat(8 + local * 22)
            var arc = Path()
            arc.addArc(center: origin, radius: r, startAngle: .degrees(-35), endAngle: .degrees(30), clockwise: false)
            context.stroke(arc, with: .color(Color(uiColor: UIColor(hex: 0xE9B43A)).opacity((1 - local) * 0.85)),
                           style: StrokeStyle(lineWidth: 2.2, lineCap: .round))
        }
    }

    /// Floating `z` motes when the arara is in guilt-free resting mode.
    private static func drawSleepZ(in context: inout GraphicsContext, t: Double) {
        for i in 0 ..< 3 {
            let phase = (t * 0.38 + Double(i) * 0.33).truncatingRemainder(dividingBy: 1.0)
            let x = 132 + phase * 18 + sin(phase * .pi * 2) * 3
            let y = 58 - phase * 34
            let alpha = sin(phase * .pi) * 0.85
            let sz = 9 + phase * 5
            let text = Text(verbatim: "z")
                .font(.system(size: sz, weight: .bold, design: .rounded))
                .foregroundColor(Color(red: 0xC9 / 255, green: 0xD6 / 255, blue: 0xF0 / 255).opacity(alpha))
            context.draw(text, at: CGPoint(x: x, y: y))
        }
    }

    /// The egg always takes the opposite of the card it sits on: cream on a dark card, dark with
    /// a light crack on a light card. The card's real colour is measured, so this holds whichever
    /// theme the phone or the app's Appearance setting uses.
    private func color(_ layer: Layer, lightCard: Bool) -> UInt32 {
        guard layer.role == .egg, lightCard else { return layer.rgb }
        switch layer.rgb {
        case 0xC7A86A: return 0x14100C
        case 0xF1E6CF: return 0x3B302A
        case 0x8A6A3A: return 0xE9D9B8
        default: return layer.rgb
        }
    }

    /// Feathers in the arara's three colours fly out and fall for 1.8 s.
    private static func drawFeathers(in context: inout GraphicsContext, since dt: Double, count: Int = 16) {
        guard dt >= 0, dt < 1.8 else { return }
        let colors: [UInt32] = [0xD2362B, 0xE9B43A, 0x2F6DB5]
        for i in 0 ..< count {
            let angle = Double(i) / Double(max(count, 1)) * 2 * .pi + Double(i % 3) * 0.3
            let speed = 55 + Double((i * 37) % 40)
            let x = 100 + cos(angle) * speed * dt
            let y = 100 + sin(angle) * speed * dt + 60 * dt * dt
            let alpha = max(0, 1 - dt / 1.8)
            var feather = Path(ellipseIn: CGRect(x: -2.5, y: -7, width: 5, height: 14))
            feather = feather.applying(CGAffineTransform(rotationAngle: angle + dt * 4).concatenating(CGAffineTransform(translationX: x, y: y)))
            context.fill(feather, with: .color(Color(uiColor: UIColor(hex: colors[i % 3])).opacity(alpha)))
        }
    }

    /// Where each part is at one moment: volume-preserving breathing, damped spring hop & flap on
    /// tap, organic double-blink, and peaceful resting posture when `AraraCare` is resting.
    struct Pose {
        var bob: CGFloat = 0
        var hop: CGFloat = 0
        var squashX: CGFloat = 1
        var squashY: CGFloat = 1
        var head: CGFloat = 0
        var tail: CGFloat = 0
        var wing: CGFloat = 0
        var blink: CGFloat = 1
        var egg: CGFloat = 0
        var headPivot = CGPoint(x: 100, y: 78)

        static let still = Pose()

        init() {}

        init(stage: AraraStage, time: Date, pokedAt: Date?, resting: Bool = false) {
            let t = time.timeIntervalSinceReferenceDate
            let poke = pokedAt.map { time.timeIntervalSince($0) } ?? 99
            func deg(_ d: Double) -> CGFloat { CGFloat(d * .pi / 180) }
            func burst(_ period: Double, _ length: Double, offset: Double = 0) -> Double {
                let phase = (t + offset).truncatingRemainder(dividingBy: period)
                return phase < length ? sin(phase / length * .pi) : 0
            }
            switch stage {
            case .egg:
                let rock = burst(3.2, 0.9) * sin(t * 18) * (resting ? 1.5 : 4)
                let poked = poke < 1 ? sin(poke * 22) * 10 * exp(-poke * 2.5) : 0
                egg = deg(rock + poked)
            case .chick:
                headPivot = CGPoint(x: 102, y: 96)
                if resting {
                    bob = CGFloat(sin(t * 1.1) * 1.2)
                    head = deg(4 + sin(t * 0.5) * 2)
                    blink = 0.14
                } else {
                    hop = -CGFloat(burst(2.6, 0.4) * 6 + (poke < 0.65 ? sin(poke / 0.65 * .pi) * 13 * exp(-poke * 1.2) : 0))
                    head = deg(sin(t * 1.1) * 5 + burst(5, 1.2, offset: 1) * 14)
                    blink = Self.blink(t)
                }
            default:
                if resting {
                    bob = CGFloat(sin(t * 1.05) * 1.4)
                    head = deg(6 + sin(t * 0.45) * 1.8)
                    tail = deg(sin(t * 0.8 + 1) * 1.2)
                    blink = 0.14
                    if poke < 0.9 {
                        wing = deg(-sin(poke * .pi * 2.5) * 12 * exp(-poke * 2.2))
                    }
                } else {
                    bob = CGFloat(sin(t * 2.2) * 1.7)
                    head = deg(sin(t * 0.8) * 3.2 + burst(6.5, 1.1, offset: 2) * 13)
                    tail = deg(sin(t * 1.4 + 1) * 2.8)
                    blink = Self.blink(t)
                    if poke < 1.2 {
                        let decay = exp(-poke * 2.1)
                        wing = deg(-sin(poke * .pi * 4.5) * 28 * decay)
                        let jump = sin(min(poke, 0.55) / 0.55 * .pi)
                        hop = -CGFloat(jump * 7)
                        let s = CGFloat(1 + 0.06 * sin(poke * .pi * 3.5) * decay)
                        squashY = s
                        squashX = 1 / s
                    }
                }
            }
        }

        private static func blink(_ t: Double) -> CGFloat {
            let phase = t.truncatingRemainder(dividingBy: 4.3)
            if phase < 0.15 { return CGFloat(max(0.1, abs(cos(phase / 0.15 * .pi)))) }
            // Occasional natural double-blink
            let dbl = t.truncatingRemainder(dividingBy: 12.9)
            if dbl >= 0.24 && dbl < 0.38 { return CGFloat(max(0.1, abs(cos((dbl - 0.24) / 0.14 * .pi)))) }
            return 1
        }

        private func rotate(_ angle: CGFloat, around p: CGPoint) -> CGAffineTransform {
            CGAffineTransform(translationX: -p.x, y: -p.y)
                .concatenating(CGAffineTransform(rotationAngle: angle))
                .concatenating(CGAffineTransform(translationX: p.x, y: p.y))
        }

        private func bodySquash() -> CGAffineTransform {
            guard squashX != 1 || squashY != 1 else { return .identity }
            let p = CGPoint(x: 100, y: 145)
            return CGAffineTransform(translationX: -p.x, y: -p.y)
                .concatenating(CGAffineTransform(scaleX: squashX, y: squashY))
                .concatenating(CGAffineTransform(translationX: p.x, y: p.y))
        }

        func transform(for layer: Layer) -> CGAffineTransform {
            let lift = bodySquash().concatenating(CGAffineTransform(translationX: 0, y: bob + hop))
            switch layer.role {
            case .fixed: return .identity
            case .egg: return rotate(egg, around: CGPoint(x: 100, y: 150))
            case .body: return lift
            case .tail: return rotate(tail, around: CGPoint(x: 88, y: 128)).concatenating(lift)
            case .wing: return rotate(wing, around: CGPoint(x: 80, y: 86)).concatenating(lift)
            case .head: return rotate(head, around: headPivot).concatenating(lift)
            case .eye:
                let c = CGPoint(x: layer.path.boundingRect.midX, y: layer.eyeCenterY)
                let squash = CGAffineTransform(translationX: -c.x, y: -c.y)
                    .concatenating(CGAffineTransform(scaleX: 1, y: blink))
                    .concatenating(CGAffineTransform(translationX: c.x, y: c.y))
                return squash.concatenating(rotate(head, around: headPivot)).concatenating(lift)
            }
        }
    }

    enum Role { case fixed, egg, body, tail, wing, head, eye }

    struct Layer {
        let role: Role
        let rgb: UInt32
        let alpha: Double
        let path: Path
        /// Big shapes get a light-to-dark gradient, which gives the flat drawing volume.
        var shaded = false
        /// Eyes close towards the pupil's centre line, the highlight with them.
        var eyeCenterY: CGFloat = 0
    }

    private static func L(_ role: Role, _ rgb: UInt32, _ alpha: Double, _ d: String) -> Layer {
        Layer(role: role, rgb: rgb, alpha: alpha, path: parse(d), shaded: Self.isShaded(d, rgb))
    }

    static func isShaded(_ d: String, _ rgb: UInt32) -> Bool {
        (d.count > 60 || d.hasPrefix("E")) && rgb != 0x111111 && rgb != 0xFFFFFF
    }

    /// The two ends of a shape's gradient: lighter at the top, darker at the bottom.
    static func shades(_ rgb: UInt32) -> (UInt32, UInt32) {
        func split(_ v: UInt32) -> [Double] { [Double((v >> 16) & 0xFF), Double((v >> 8) & 0xFF), Double(v & 0xFF)] }
        func join(_ c: [Double]) -> UInt32 { c.reduce(0) { ($0 << 8) | UInt32(max(0, min(255, $1))) } }
        let c = split(rgb)
        return (join(c.map { $0 * 1.14 + 8 }), join(c.map { $0 * 0.78 }))
    }

    private static var cache: [AraraStage: [Layer]] = [:]

    static func layers(_ stage: AraraStage) -> [Layer] {
        if let hit = cache[stage] { return hit }
        var made = build(stage)
        // The pupil is the first eye layer; its centre is where both eye layers close to.
        if let pupil = made.first(where: { $0.role == .eye }) {
            let y = pupil.path.boundingRect.midY
            for i in made.indices where made[i].role == .eye { made[i].eyeCenterY = y }
        }
        cache[stage] = made
        return made
    }

    /// The shapes are drawn on a 200 × 200 grid (prototyped as SVG and checked as images).
    private static func build(_ stage: AraraStage) -> [Layer] {
        switch stage {
        case .egg: [
            L(.fixed, 0xC7A86A, 1.0, "M40 140 C60 176 140 176 160 140 C150 150 50 150 40 140 Z"),
            L(.fixed, 0xE3CC98, 1.0, "M44 142 C70 168 130 168 156 142 C140 150 60 150 44 142 Z"),
            L(.egg, 0xC7A86A, 1.0, "M100 73 C124 73 137 105 135 126 C133 146 117 153 100 153 C83 153 67 146 65 126 C63 105 76 73 100 73 Z"),
            L(.egg, 0xF1E6CF, 1.0, "M100 76 C122 76 134 106 132 126 C130 144 116 150 100 150 C84 150 70 144 68 126 C66 106 78 76 100 76 Z"),
            L(.egg, 0xC98A1F, 1.0, "E 90 104 4 3"),
            L(.egg, 0xC98A1F, 1.0, "E 110 96 3 2.5"),
            L(.egg, 0xC98A1F, 1.0, "E 114 124 4 3"),
            L(.egg, 0xC98A1F, 1.0, "E 92 132 3 2.5"),
            L(.fixed, 0xE3CC98, 1.0, "M40 146 C70 162 130 162 160 146 C140 158 60 158 40 146 Z"),
            L(.egg, 0x8A6A3A, 1.0, "M84 100 L92 108 L88 114 L98 120 L94 126 L100 130 L100 132 L92 128 L96 122 L86 116 L90 110 L82 102 Z"),
        ]
        case .chick: [
            L(.fixed, 0xC7A86A, 1.0, "M40 140 C60 176 140 176 160 140 C150 150 50 150 40 140 Z"),
            L(.fixed, 0xE3CC98, 1.0, "M44 142 C70 168 130 168 156 142 C140 150 60 150 44 142 Z"),
            L(.body, 0xC9A9A2, 1.0, "M100 92 C124 92 134 112 132 128 C130 144 116 150 100 150 C84 150 70 144 68 128 C66 112 76 92 100 92 Z"),
            L(.body, 0xD2362B, 1.0, "M86 118 C96 112 110 114 116 124 C108 132 92 132 84 126 Z"),
            L(.head, 0xC9A9A2, 1.0, "E 102 78 20 19"),
            L(.head, 0xD2362B, 1.0, "E 96 72 7 5"),
            L(.head, 0xF3ECE1, 1.0, "E 110 79 9 8"),
            L(.eye, 0x111, 1.0, "E 111 77 2.8 2.8"),
            L(.eye, 0xFFF, 1.0, "E 112 76 1 1"),
            L(.head, 0xEDE3D1, 1.0, "M117 68 C131 66 139 78 135 92 C132 86 126 82 118 84 Z"),
            L(.head, 0x2B2522, 1.0, "M118 84 C125 84 129 88 127 94 C122 94 118 90 116 86 Z"),
            L(.fixed, 0xE3CC98, 1.0, "M40 146 C70 162 130 162 160 146 C140 158 60 158 40 146 Z"),
        ]
        case .young: [
            L(.fixed, 0x7A452B, 1.0, "M14 152 C60 146 140 146 188 150 L188 160 C140 156 60 156 14 162 Z"),
            L(.fixed, 0x5E3420, 1.0, "M30 154 C50 152 64 152 80 153 L80 154.5 C64 154 50 154 30 156 Z"),
            L(.fixed, 0x5E3420, 1.0, "M120 151 C140 151 156 152 172 153 L172 154.5 C156 154 140 153 120 153 Z"),
            L(.tail, 0xB92D25, 1.0, "M90 128 C84 152 74 174 60 192 L67 195 C80 176 90 154 97 132 Z"),
            L(.tail, 0xD2362B, 1.0, "M94 130 C92 146 86 160 78 172 L85 175 C92 162 97 148 99 134 Z"),
            L(.body, 0xD2362B, 1.0, "M92 66 C114 62 128 84 125 108 C122 130 106 144 90 142 C76 140 72 124 74 106 C76 88 78 70 92 66 Z"),
            L(.body, 0xB92D25, 1.0, "M107.2 86.0 Q110.2 90.0 113.2 86.0 Q110.2 88.0 107.2 86.0 Z"),
            L(.body, 0xB92D25, 1.0, "M111.5 86.0 Q114.5 90.0 117.5 86.0 Q114.5 88.0 111.5 86.0 Z"),
            L(.body, 0xB92D25, 1.0, "M115.8 86.0 Q118.8 90.0 121.8 86.0 Q118.8 88.0 115.8 86.0 Z"),
            L(.body, 0xB92D25, 1.0, "M107.5 96.0 Q110.5 100.0 113.5 96.0 Q110.5 98.0 107.5 96.0 Z"),
            L(.body, 0xB92D25, 1.0, "M112.5 96.0 Q115.5 100.0 118.5 96.0 Q115.5 98.0 112.5 96.0 Z"),
            L(.body, 0xB92D25, 1.0, "M117.5 96.0 Q120.5 100.0 123.5 96.0 Q120.5 98.0 117.5 96.0 Z"),
            L(.body, 0xB92D25, 1.0, "M105.8 106.0 Q108.8 110.0 111.8 106.0 Q108.8 108.0 105.8 106.0 Z"),
            L(.body, 0xB92D25, 1.0, "M111.5 106.0 Q114.5 110.0 117.5 106.0 Q114.5 108.0 111.5 106.0 Z"),
            L(.body, 0xB92D25, 1.0, "M117.2 106.0 Q120.2 110.0 123.2 106.0 Q120.2 108.0 117.2 106.0 Z"),
            L(.body, 0xB92D25, 1.0, "M103.8 116.0 Q106.8 120.0 109.8 116.0 Q106.8 118.0 103.8 116.0 Z"),
            L(.body, 0xB92D25, 1.0, "M109.5 116.0 Q112.5 120.0 115.5 116.0 Q112.5 118.0 109.5 116.0 Z"),
            L(.body, 0xB92D25, 1.0, "M115.2 116.0 Q118.2 120.0 121.2 116.0 Q118.2 118.0 115.2 116.0 Z"),
            L(.body, 0xB92D25, 1.0, "M99.7 126.0 Q102.7 130.0 105.7 126.0 Q102.7 128.0 99.7 126.0 Z"),
            L(.body, 0xB92D25, 1.0, "M105.0 126.0 Q108.0 130.0 111.0 126.0 Q108.0 128.0 105.0 126.0 Z"),
            L(.body, 0xB92D25, 1.0, "M110.3 126.0 Q113.3 130.0 116.3 126.0 Q113.3 128.0 110.3 126.0 Z"),
            L(.wing, 0x2A5FA0, 1.0, "M84 104 C88 124 88 142 82 156 C78 148 76 128 78 108 Z"),
            L(.wing, 0x2F6DB5, 1.0, "M88 104 C92 124 92 142 86 158 C82 150 80 130 82 108 Z"),
            L(.wing, 0x3A7CC8, 1.0, "M92 102 C98 120 98 140 92 156 C88 148 86 126 88 104 Z"),
            L(.wing, 0x2F6DB5, 1.0, "M98 100 C104 118 104 136 98 152 C94 146 92 124 94 102 Z"),
            L(.wing, 0x1E3F78, 1.0, "M86 112 C88 128 88 142 85 154 L84 154 C86 142 86 128 85 112 Z"),
            L(.wing, 0x1E3F78, 1.0, "M92 110 C94 126 94 140 91 153 L90 153 C92 140 92 126 91 110 Z"),
            L(.wing, 0x1E3F78, 1.0, "M97 108 C99 122 99 136 96 149 L95 149 C97 136 97 122 96 108 Z"),
            L(.wing, 0x2F8C9A, 1.0, "M77 98 C92 93 106 98 111 105 C104 113 90 115 76 112 Z"),
            L(.wing, 0xE9B43A, 1.0, "M78 88 C92 83 106 90 110 100 C100 106 88 106 77 102 Z"),
            L(.wing, 0x8FA83A, 1.0, "M78.8 101.2 Q81.4 104.5 84.0 101.2 Q81.4 102.7 78.8 101.2 Z"),
            L(.wing, 0x8FA83A, 1.0, "M83.7 101.5 Q86.2 104.9 88.8 101.5 Q86.2 103.1 83.7 101.5 Z"),
            L(.wing, 0x8FA83A, 1.0, "M88.5 101.8 Q91.1 105.2 93.7 101.8 Q91.1 103.4 88.5 101.8 Z"),
            L(.wing, 0x8FA83A, 1.0, "M93.3 102.2 Q95.9 105.5 98.5 102.2 Q95.9 103.7 93.3 102.2 Z"),
            L(.wing, 0x8FA83A, 1.0, "M98.2 102.5 Q100.8 105.9 103.3 102.5 Q100.8 104.1 98.2 102.5 Z"),
            L(.wing, 0x8FA83A, 1.0, "M103.0 102.8 Q105.6 106.2 108.2 102.8 Q105.6 104.4 103.0 102.8 Z"),
            L(.wing, 0xD2362B, 1.0, "M80 82 C94 77 106 85 107 96 C98 99 88 98 78 94 Z"),
            L(.wing, 0xB92D25, 1.0, "M80.0 89.3 Q82.4 92.4 84.8 89.3 Q82.4 90.7 80.0 89.3 Z"),
            L(.wing, 0xB92D25, 1.0, "M84.8 89.9 Q87.2 93.0 89.6 89.9 Q87.2 91.3 84.8 89.9 Z"),
            L(.wing, 0xB92D25, 1.0, "M89.6 90.5 Q92.0 93.6 94.4 90.5 Q92.0 91.9 89.6 90.5 Z"),
            L(.wing, 0xB92D25, 1.0, "M94.4 91.1 Q96.8 94.2 99.2 91.1 Q96.8 92.5 94.4 91.1 Z"),
            L(.wing, 0xB92D25, 1.0, "M99.2 91.7 Q101.6 94.8 104.0 91.7 Q101.6 93.1 99.2 91.7 Z"),
            L(.wing, 0xB92D25, 1.0, "M82.0 84.1 Q84.2 87.0 86.5 84.1 Q84.2 85.4 82.0 84.1 Z"),
            L(.wing, 0xB92D25, 1.0, "M86.5 84.4 Q88.8 87.2 91.0 84.4 Q88.8 85.7 86.5 84.4 Z"),
            L(.wing, 0xB92D25, 1.0, "M91.0 84.6 Q93.2 87.5 95.5 84.6 Q93.2 85.9 91.0 84.6 Z"),
            L(.wing, 0xB92D25, 1.0, "M95.5 84.9 Q97.8 87.7 100.0 84.9 Q97.8 86.2 95.5 84.9 Z"),
            L(.head, 0xD2362B, 1.0, "E 104 56 21 20"),
            L(.head, 0xB92D25, 1.0, "M92 40 Q100 36 110 38 Q100 38 92 42 Z"),
            L(.head, 0xF3ECE1, 1.0, "E 112 57 10.5 9.5"),
            L(.head, 0xB8473C, 1.0, "M104 59 Q109 61 115 59 L115 60 Q109 62 104 60 Z"),
            L(.head, 0xB8473C, 1.0, "M105 63 Q110 65 116 63 L116 64 Q110 66 105 64 Z"),
            L(.head, 0xB8473C, 1.0, "M106 52 Q108 51 110 51 L110 52 Q108 52 106 53 Z"),
            L(.eye, 0xF2D98A, 1.0, "E 113 55 3.6 3.6"),
            L(.eye, 0x111111, 1.0, "E 113 55 2.1 2.1"),
            L(.eye, 0xFFFFFF, 1.0, "E 113.8 54.2 0.8 0.8"),
            L(.head, 0xEDE3D1, 1.0, "M119 44 C134 42 143 54 139 70 C136 63 130 59 121 61 Z"),
            L(.head, 0x3A302B, 1.0, "M119 44 C120 43.6 121 43.6 122 44 C121 50 120.5 56 120 61 L119 61 Z"),
            L(.head, 0x3A302B, 1.0, "M138 62 C139 65 139 68 139 70 C138 68 137 66 135 65 Z"),
            L(.head, 0x2B2522, 1.0, "M121 61 C128 61 132 65 130 72 C125 72 121 68 119 64 Z"),
            L(.fixed, 0x6F6E74, 1.0, "M86 148 C88 145 94 145 96 148 L96 152 C94 150 88 150 86 152 Z"),
            L(.fixed, 0x6F6E74, 1.0, "M98 148 C100 145 106 145 108 148 L108 152 C106 150 100 150 98 152 Z"),
            L(.fixed, 0x3A393E, 1.0, "M86 152 L85 155 L87 154 Z"),
            L(.fixed, 0x3A393E, 1.0, "M96 152 L95 155 L97 154 Z"),
            L(.fixed, 0x3A393E, 1.0, "M98 152 L97 155 L99 154 Z"),
            L(.fixed, 0x3A393E, 1.0, "M108 152 L107 155 L109 154 Z"),
        ]
        case .adult: [
            L(.fixed, 0x7A452B, 1.0, "M14 152 C60 146 140 146 188 150 L188 160 C140 156 60 156 14 162 Z"),
            L(.fixed, 0x5E3420, 1.0, "M30 154 C50 152 64 152 80 153 L80 154.5 C64 154 50 154 30 156 Z"),
            L(.fixed, 0x5E3420, 1.0, "M120 151 C140 151 156 152 172 153 L172 154.5 C156 154 140 153 120 153 Z"),
            L(.tail, 0xB92D25, 1.0, "M90 128 C84 152 74 174 60 192 L67 195 C80 176 90 154 97 132 Z"),
            L(.tail, 0x2A5FA0, 1.0, "M62 184 C61 187 60 190 60 192 L67 195 C68 192 70 189 72 186 Z"),
            L(.tail, 0xD2362B, 1.0, "M86 126 C78 150 64 176 46 199 L55 201 C70 180 84 156 94 130 Z"),
            L(.tail, 0x2F6DB5, 1.0, "M50 186 C48 191 47 196 46 199 L55 201 C57 197 59 193 61 189 Z"),
            L(.tail, 0xD2362B, 1.0, "M94 130 C92 146 86 160 78 172 L85 175 C92 162 97 148 99 134 Z"),
            L(.body, 0xD2362B, 1.0, "M92 66 C114 62 128 84 125 108 C122 130 106 144 90 142 C76 140 72 124 74 106 C76 88 78 70 92 66 Z"),
            L(.body, 0xB92D25, 1.0, "M107.2 86.0 Q110.2 90.0 113.2 86.0 Q110.2 88.0 107.2 86.0 Z"),
            L(.body, 0xB92D25, 1.0, "M111.5 86.0 Q114.5 90.0 117.5 86.0 Q114.5 88.0 111.5 86.0 Z"),
            L(.body, 0xB92D25, 1.0, "M115.8 86.0 Q118.8 90.0 121.8 86.0 Q118.8 88.0 115.8 86.0 Z"),
            L(.body, 0xB92D25, 1.0, "M107.5 96.0 Q110.5 100.0 113.5 96.0 Q110.5 98.0 107.5 96.0 Z"),
            L(.body, 0xB92D25, 1.0, "M112.5 96.0 Q115.5 100.0 118.5 96.0 Q115.5 98.0 112.5 96.0 Z"),
            L(.body, 0xB92D25, 1.0, "M117.5 96.0 Q120.5 100.0 123.5 96.0 Q120.5 98.0 117.5 96.0 Z"),
            L(.body, 0xB92D25, 1.0, "M105.8 106.0 Q108.8 110.0 111.8 106.0 Q108.8 108.0 105.8 106.0 Z"),
            L(.body, 0xB92D25, 1.0, "M111.5 106.0 Q114.5 110.0 117.5 106.0 Q114.5 108.0 111.5 106.0 Z"),
            L(.body, 0xB92D25, 1.0, "M117.2 106.0 Q120.2 110.0 123.2 106.0 Q120.2 108.0 117.2 106.0 Z"),
            L(.body, 0xB92D25, 1.0, "M103.8 116.0 Q106.8 120.0 109.8 116.0 Q106.8 118.0 103.8 116.0 Z"),
            L(.body, 0xB92D25, 1.0, "M109.5 116.0 Q112.5 120.0 115.5 116.0 Q112.5 118.0 109.5 116.0 Z"),
            L(.body, 0xB92D25, 1.0, "M115.2 116.0 Q118.2 120.0 121.2 116.0 Q118.2 118.0 115.2 116.0 Z"),
            L(.body, 0xB92D25, 1.0, "M99.7 126.0 Q102.7 130.0 105.7 126.0 Q102.7 128.0 99.7 126.0 Z"),
            L(.body, 0xB92D25, 1.0, "M105.0 126.0 Q108.0 130.0 111.0 126.0 Q108.0 128.0 105.0 126.0 Z"),
            L(.body, 0xB92D25, 1.0, "M110.3 126.0 Q113.3 130.0 116.3 126.0 Q113.3 128.0 110.3 126.0 Z"),
            L(.wing, 0x2A5FA0, 1.0, "M84 104 C88 124 88 142 82 156 C78 148 76 128 78 108 Z"),
            L(.wing, 0x2F6DB5, 1.0, "M88 104 C92 124 92 142 86 158 C82 150 80 130 82 108 Z"),
            L(.wing, 0x3A7CC8, 1.0, "M92 102 C98 120 98 140 92 156 C88 148 86 126 88 104 Z"),
            L(.wing, 0x2F6DB5, 1.0, "M98 100 C104 118 104 136 98 152 C94 146 92 124 94 102 Z"),
            L(.wing, 0x1E3F78, 1.0, "M86 112 C88 128 88 142 85 154 L84 154 C86 142 86 128 85 112 Z"),
            L(.wing, 0x1E3F78, 1.0, "M92 110 C94 126 94 140 91 153 L90 153 C92 140 92 126 91 110 Z"),
            L(.wing, 0x1E3F78, 1.0, "M97 108 C99 122 99 136 96 149 L95 149 C97 136 97 122 96 108 Z"),
            L(.wing, 0x2F8C9A, 1.0, "M77 98 C92 93 106 98 111 105 C104 113 90 115 76 112 Z"),
            L(.wing, 0xE9B43A, 1.0, "M78 88 C92 83 106 90 110 100 C100 106 88 106 77 102 Z"),
            L(.wing, 0x8FA83A, 1.0, "M78.8 101.2 Q81.4 104.5 84.0 101.2 Q81.4 102.7 78.8 101.2 Z"),
            L(.wing, 0x8FA83A, 1.0, "M83.7 101.5 Q86.2 104.9 88.8 101.5 Q86.2 103.1 83.7 101.5 Z"),
            L(.wing, 0x8FA83A, 1.0, "M88.5 101.8 Q91.1 105.2 93.7 101.8 Q91.1 103.4 88.5 101.8 Z"),
            L(.wing, 0x8FA83A, 1.0, "M93.3 102.2 Q95.9 105.5 98.5 102.2 Q95.9 103.7 93.3 102.2 Z"),
            L(.wing, 0x8FA83A, 1.0, "M98.2 102.5 Q100.8 105.9 103.3 102.5 Q100.8 104.1 98.2 102.5 Z"),
            L(.wing, 0x8FA83A, 1.0, "M103.0 102.8 Q105.6 106.2 108.2 102.8 Q105.6 104.4 103.0 102.8 Z"),
            L(.wing, 0xD2362B, 1.0, "M80 82 C94 77 106 85 107 96 C98 99 88 98 78 94 Z"),
            L(.wing, 0xB92D25, 1.0, "M80.0 89.3 Q82.4 92.4 84.8 89.3 Q82.4 90.7 80.0 89.3 Z"),
            L(.wing, 0xB92D25, 1.0, "M84.8 89.9 Q87.2 93.0 89.6 89.9 Q87.2 91.3 84.8 89.9 Z"),
            L(.wing, 0xB92D25, 1.0, "M89.6 90.5 Q92.0 93.6 94.4 90.5 Q92.0 91.9 89.6 90.5 Z"),
            L(.wing, 0xB92D25, 1.0, "M94.4 91.1 Q96.8 94.2 99.2 91.1 Q96.8 92.5 94.4 91.1 Z"),
            L(.wing, 0xB92D25, 1.0, "M99.2 91.7 Q101.6 94.8 104.0 91.7 Q101.6 93.1 99.2 91.7 Z"),
            L(.wing, 0xB92D25, 1.0, "M82.0 84.1 Q84.2 87.0 86.5 84.1 Q84.2 85.4 82.0 84.1 Z"),
            L(.wing, 0xB92D25, 1.0, "M86.5 84.4 Q88.8 87.2 91.0 84.4 Q88.8 85.7 86.5 84.4 Z"),
            L(.wing, 0xB92D25, 1.0, "M91.0 84.6 Q93.2 87.5 95.5 84.6 Q93.2 85.9 91.0 84.6 Z"),
            L(.wing, 0xB92D25, 1.0, "M95.5 84.9 Q97.8 87.7 100.0 84.9 Q97.8 86.2 95.5 84.9 Z"),
            L(.head, 0xD2362B, 1.0, "E 104 56 21 20"),
            L(.head, 0xB92D25, 1.0, "M92 40 Q100 36 110 38 Q100 38 92 42 Z"),
            L(.head, 0xF3ECE1, 1.0, "E 112 57 10.5 9.5"),
            L(.head, 0xB8473C, 1.0, "M104 59 Q109 61 115 59 L115 60 Q109 62 104 60 Z"),
            L(.head, 0xB8473C, 1.0, "M105 63 Q110 65 116 63 L116 64 Q110 66 105 64 Z"),
            L(.head, 0xB8473C, 1.0, "M106 52 Q108 51 110 51 L110 52 Q108 52 106 53 Z"),
            L(.eye, 0xF2D98A, 1.0, "E 113 55 3.6 3.6"),
            L(.eye, 0x111111, 1.0, "E 113 55 2.1 2.1"),
            L(.eye, 0xFFFFFF, 1.0, "E 113.8 54.2 0.8 0.8"),
            L(.head, 0xEDE3D1, 1.0, "M119 44 C134 42 143 54 139 70 C136 63 130 59 121 61 Z"),
            L(.head, 0x3A302B, 1.0, "M119 44 C120 43.6 121 43.6 122 44 C121 50 120.5 56 120 61 L119 61 Z"),
            L(.head, 0x3A302B, 1.0, "M138 62 C139 65 139 68 139 70 C138 68 137 66 135 65 Z"),
            L(.head, 0x2B2522, 1.0, "M121 61 C128 61 132 65 130 72 C125 72 121 68 119 64 Z"),
            L(.fixed, 0x6F6E74, 1.0, "M86 148 C88 145 94 145 96 148 L96 152 C94 150 88 150 86 152 Z"),
            L(.fixed, 0x6F6E74, 1.0, "M98 148 C100 145 106 145 108 148 L108 152 C106 150 100 150 98 152 Z"),
            L(.fixed, 0x3A393E, 1.0, "M86 152 L85 155 L87 154 Z"),
            L(.fixed, 0x3A393E, 1.0, "M96 152 L95 155 L97 154 Z"),
            L(.fixed, 0x3A393E, 1.0, "M98 152 L97 155 L99 154 Z"),
            L(.fixed, 0x3A393E, 1.0, "M108 152 L107 155 L109 154 Z"),
        ]
        case .royal: [
            L(.fixed, 0xE9B43A, 0.33, "E 104 104 78 78"),
            L(.fixed, 0x7A452B, 1.0, "M14 152 C60 146 140 146 188 150 L188 160 C140 156 60 156 14 162 Z"),
            L(.fixed, 0x5E3420, 1.0, "M30 154 C50 152 64 152 80 153 L80 154.5 C64 154 50 154 30 156 Z"),
            L(.fixed, 0x5E3420, 1.0, "M120 151 C140 151 156 152 172 153 L172 154.5 C156 154 140 153 120 153 Z"),
            L(.fixed, 0xF3ECE1, 1.0, "M30 155 L36 151 L42 155 L36 159 Z"),
            L(.fixed, 0xF3ECE1, 1.0, "M60 153 L66 149 L72 153 L66 157 Z"),
            L(.fixed, 0xF3ECE1, 1.0, "M130 152 L136 148 L142 152 L136 156 Z"),
            L(.fixed, 0xF3ECE1, 1.0, "M160 153 L166 149 L172 153 L166 157 Z"),
            L(.tail, 0xD2362B, 1.0, "M78 126 C68 150 52 176 34 198 L44 200 C62 180 78 156 90 132 Z"),
            L(.tail, 0x2F6DB5, 1.0, "M44 186 C41 191 37 195 34 198 L44 200 C47 196 50 192 54 187 Z"),
            L(.tail, 0xB92D25, 1.0, "M90 128 C84 152 74 174 60 192 L67 195 C80 176 90 154 97 132 Z"),
            L(.tail, 0x2A5FA0, 1.0, "M62 184 C61 187 60 190 60 192 L67 195 C68 192 70 189 72 186 Z"),
            L(.tail, 0xD2362B, 1.0, "M86 126 C78 150 64 176 46 199 L55 201 C70 180 84 156 94 130 Z"),
            L(.tail, 0x2F6DB5, 1.0, "M50 186 C48 191 47 196 46 199 L55 201 C57 197 59 193 61 189 Z"),
            L(.tail, 0xD2362B, 1.0, "M94 130 C92 146 86 160 78 172 L85 175 C92 162 97 148 99 134 Z"),
            L(.body, 0xD2362B, 1.0, "M92 66 C114 62 128 84 125 108 C122 130 106 144 90 142 C76 140 72 124 74 106 C76 88 78 70 92 66 Z"),
            L(.body, 0xB92D25, 1.0, "M107.2 86.0 Q110.2 90.0 113.2 86.0 Q110.2 88.0 107.2 86.0 Z"),
            L(.body, 0xB92D25, 1.0, "M111.5 86.0 Q114.5 90.0 117.5 86.0 Q114.5 88.0 111.5 86.0 Z"),
            L(.body, 0xB92D25, 1.0, "M115.8 86.0 Q118.8 90.0 121.8 86.0 Q118.8 88.0 115.8 86.0 Z"),
            L(.body, 0xB92D25, 1.0, "M107.5 96.0 Q110.5 100.0 113.5 96.0 Q110.5 98.0 107.5 96.0 Z"),
            L(.body, 0xB92D25, 1.0, "M112.5 96.0 Q115.5 100.0 118.5 96.0 Q115.5 98.0 112.5 96.0 Z"),
            L(.body, 0xB92D25, 1.0, "M117.5 96.0 Q120.5 100.0 123.5 96.0 Q120.5 98.0 117.5 96.0 Z"),
            L(.body, 0xB92D25, 1.0, "M105.8 106.0 Q108.8 110.0 111.8 106.0 Q108.8 108.0 105.8 106.0 Z"),
            L(.body, 0xB92D25, 1.0, "M111.5 106.0 Q114.5 110.0 117.5 106.0 Q114.5 108.0 111.5 106.0 Z"),
            L(.body, 0xB92D25, 1.0, "M117.2 106.0 Q120.2 110.0 123.2 106.0 Q120.2 108.0 117.2 106.0 Z"),
            L(.body, 0xB92D25, 1.0, "M103.8 116.0 Q106.8 120.0 109.8 116.0 Q106.8 118.0 103.8 116.0 Z"),
            L(.body, 0xB92D25, 1.0, "M109.5 116.0 Q112.5 120.0 115.5 116.0 Q112.5 118.0 109.5 116.0 Z"),
            L(.body, 0xB92D25, 1.0, "M115.2 116.0 Q118.2 120.0 121.2 116.0 Q118.2 118.0 115.2 116.0 Z"),
            L(.body, 0xB92D25, 1.0, "M99.7 126.0 Q102.7 130.0 105.7 126.0 Q102.7 128.0 99.7 126.0 Z"),
            L(.body, 0xB92D25, 1.0, "M105.0 126.0 Q108.0 130.0 111.0 126.0 Q108.0 128.0 105.0 126.0 Z"),
            L(.body, 0xB92D25, 1.0, "M110.3 126.0 Q113.3 130.0 116.3 126.0 Q113.3 128.0 110.3 126.0 Z"),
            L(.wing, 0x2A5FA0, 1.0, "M84 104 C88 124 88 142 82 156 C78 148 76 128 78 108 Z"),
            L(.wing, 0x2F6DB5, 1.0, "M88 104 C92 124 92 142 86 158 C82 150 80 130 82 108 Z"),
            L(.wing, 0x3A7CC8, 1.0, "M92 102 C98 120 98 140 92 156 C88 148 86 126 88 104 Z"),
            L(.wing, 0x2F6DB5, 1.0, "M98 100 C104 118 104 136 98 152 C94 146 92 124 94 102 Z"),
            L(.wing, 0x1E3F78, 1.0, "M86 112 C88 128 88 142 85 154 L84 154 C86 142 86 128 85 112 Z"),
            L(.wing, 0x1E3F78, 1.0, "M92 110 C94 126 94 140 91 153 L90 153 C92 140 92 126 91 110 Z"),
            L(.wing, 0x1E3F78, 1.0, "M97 108 C99 122 99 136 96 149 L95 149 C97 136 97 122 96 108 Z"),
            L(.wing, 0x2F8C9A, 1.0, "M77 98 C92 93 106 98 111 105 C104 113 90 115 76 112 Z"),
            L(.wing, 0xE9B43A, 1.0, "M78 88 C92 83 106 90 110 100 C100 106 88 106 77 102 Z"),
            L(.wing, 0x8FA83A, 1.0, "M78.8 101.2 Q81.4 104.5 84.0 101.2 Q81.4 102.7 78.8 101.2 Z"),
            L(.wing, 0x8FA83A, 1.0, "M83.7 101.5 Q86.2 104.9 88.8 101.5 Q86.2 103.1 83.7 101.5 Z"),
            L(.wing, 0x8FA83A, 1.0, "M88.5 101.8 Q91.1 105.2 93.7 101.8 Q91.1 103.4 88.5 101.8 Z"),
            L(.wing, 0x8FA83A, 1.0, "M93.3 102.2 Q95.9 105.5 98.5 102.2 Q95.9 103.7 93.3 102.2 Z"),
            L(.wing, 0x8FA83A, 1.0, "M98.2 102.5 Q100.8 105.9 103.3 102.5 Q100.8 104.1 98.2 102.5 Z"),
            L(.wing, 0x8FA83A, 1.0, "M103.0 102.8 Q105.6 106.2 108.2 102.8 Q105.6 104.4 103.0 102.8 Z"),
            L(.wing, 0xD2362B, 1.0, "M80 82 C94 77 106 85 107 96 C98 99 88 98 78 94 Z"),
            L(.wing, 0xB92D25, 1.0, "M80.0 89.3 Q82.4 92.4 84.8 89.3 Q82.4 90.7 80.0 89.3 Z"),
            L(.wing, 0xB92D25, 1.0, "M84.8 89.9 Q87.2 93.0 89.6 89.9 Q87.2 91.3 84.8 89.9 Z"),
            L(.wing, 0xB92D25, 1.0, "M89.6 90.5 Q92.0 93.6 94.4 90.5 Q92.0 91.9 89.6 90.5 Z"),
            L(.wing, 0xB92D25, 1.0, "M94.4 91.1 Q96.8 94.2 99.2 91.1 Q96.8 92.5 94.4 91.1 Z"),
            L(.wing, 0xB92D25, 1.0, "M99.2 91.7 Q101.6 94.8 104.0 91.7 Q101.6 93.1 99.2 91.7 Z"),
            L(.wing, 0xB92D25, 1.0, "M82.0 84.1 Q84.2 87.0 86.5 84.1 Q84.2 85.4 82.0 84.1 Z"),
            L(.wing, 0xB92D25, 1.0, "M86.5 84.4 Q88.8 87.2 91.0 84.4 Q88.8 85.7 86.5 84.4 Z"),
            L(.wing, 0xB92D25, 1.0, "M91.0 84.6 Q93.2 87.5 95.5 84.6 Q93.2 85.9 91.0 84.6 Z"),
            L(.wing, 0xB92D25, 1.0, "M95.5 84.9 Q97.8 87.7 100.0 84.9 Q97.8 86.2 95.5 84.9 Z"),
            L(.head, 0xD2362B, 1.0, "E 104 56 21 20"),
            L(.head, 0xB92D25, 1.0, "M92 40 Q100 36 110 38 Q100 38 92 42 Z"),
            L(.head, 0xF3ECE1, 1.0, "E 112 57 10.5 9.5"),
            L(.head, 0xB8473C, 1.0, "M104 59 Q109 61 115 59 L115 60 Q109 62 104 60 Z"),
            L(.head, 0xB8473C, 1.0, "M105 63 Q110 65 116 63 L116 64 Q110 66 105 64 Z"),
            L(.head, 0xB8473C, 1.0, "M106 52 Q108 51 110 51 L110 52 Q108 52 106 53 Z"),
            L(.eye, 0xF2D98A, 1.0, "E 113 55 3.6 3.6"),
            L(.eye, 0x111111, 1.0, "E 113 55 2.1 2.1"),
            L(.eye, 0xFFFFFF, 1.0, "E 113.8 54.2 0.8 0.8"),
            L(.head, 0xEDE3D1, 1.0, "M119 44 C134 42 143 54 139 70 C136 63 130 59 121 61 Z"),
            L(.head, 0x3A302B, 1.0, "M119 44 C120 43.6 121 43.6 122 44 C121 50 120.5 56 120 61 L119 61 Z"),
            L(.head, 0x3A302B, 1.0, "M138 62 C139 65 139 68 139 70 C138 68 137 66 135 65 Z"),
            L(.head, 0x2B2522, 1.0, "M121 61 C128 61 132 65 130 72 C125 72 121 68 119 64 Z"),
            L(.fixed, 0x6F6E74, 1.0, "M86 148 C88 145 94 145 96 148 L96 152 C94 150 88 150 86 152 Z"),
            L(.fixed, 0x6F6E74, 1.0, "M98 148 C100 145 106 145 108 148 L108 152 C106 150 100 150 98 152 Z"),
            L(.fixed, 0x3A393E, 1.0, "M86 152 L85 155 L87 154 Z"),
            L(.fixed, 0x3A393E, 1.0, "M96 152 L95 155 L97 154 Z"),
            L(.fixed, 0x3A393E, 1.0, "M98 152 L97 155 L99 154 Z"),
            L(.fixed, 0x3A393E, 1.0, "M108 152 L107 155 L109 154 Z"),
        ]
        case .legend: [
            L(.fixed, 0xE9B43A, 1.0, "E 104 100 92 92"),
            L(.fixed, 0xF4ECDF, 1.0, "E 104 100 84 84"),
            L(.fixed, 0xE9B43A, 0.4, "M104 10 L110 24 L98 24 Z"),
            L(.fixed, 0xE9B43A, 0.4, "M194 100 L180 106 L180 94 Z"),
            L(.fixed, 0xE9B43A, 0.4, "M14 100 L28 94 L28 106 Z"),
            L(.fixed, 0x7A452B, 1.0, "M14 152 C60 146 140 146 188 150 L188 160 C140 156 60 156 14 162 Z"),
            L(.fixed, 0x5E3420, 1.0, "M30 154 C50 152 64 152 80 153 L80 154.5 C64 154 50 154 30 156 Z"),
            L(.fixed, 0x5E3420, 1.0, "M120 151 C140 151 156 152 172 153 L172 154.5 C156 154 140 153 120 153 Z"),
            L(.fixed, 0xF3ECE1, 1.0, "M30 155 L36 151 L42 155 L36 159 Z"),
            L(.fixed, 0xF3ECE1, 1.0, "M60 153 L66 149 L72 153 L66 157 Z"),
            L(.fixed, 0xF3ECE1, 1.0, "M130 152 L136 148 L142 152 L136 156 Z"),
            L(.fixed, 0xF3ECE1, 1.0, "M160 153 L166 149 L172 153 L166 157 Z"),
            L(.tail, 0xD2362B, 1.0, "M78 126 C68 150 52 176 34 198 L44 200 C62 180 78 156 90 132 Z"),
            L(.tail, 0x2F6DB5, 1.0, "M44 186 C41 191 37 195 34 198 L44 200 C47 196 50 192 54 187 Z"),
            L(.tail, 0xB92D25, 1.0, "M90 128 C84 152 74 174 60 192 L67 195 C80 176 90 154 97 132 Z"),
            L(.tail, 0x2A5FA0, 1.0, "M62 184 C61 187 60 190 60 192 L67 195 C68 192 70 189 72 186 Z"),
            L(.tail, 0xD2362B, 1.0, "M86 126 C78 150 64 176 46 199 L55 201 C70 180 84 156 94 130 Z"),
            L(.tail, 0x2F6DB5, 1.0, "M50 186 C48 191 47 196 46 199 L55 201 C57 197 59 193 61 189 Z"),
            L(.tail, 0xD2362B, 1.0, "M94 130 C92 146 86 160 78 172 L85 175 C92 162 97 148 99 134 Z"),
            L(.body, 0xD2362B, 1.0, "M92 66 C114 62 128 84 125 108 C122 130 106 144 90 142 C76 140 72 124 74 106 C76 88 78 70 92 66 Z"),
            L(.body, 0xB92D25, 1.0, "M107.2 86.0 Q110.2 90.0 113.2 86.0 Q110.2 88.0 107.2 86.0 Z"),
            L(.body, 0xB92D25, 1.0, "M111.5 86.0 Q114.5 90.0 117.5 86.0 Q114.5 88.0 111.5 86.0 Z"),
            L(.body, 0xB92D25, 1.0, "M115.8 86.0 Q118.8 90.0 121.8 86.0 Q118.8 88.0 115.8 86.0 Z"),
            L(.body, 0xB92D25, 1.0, "M107.5 96.0 Q110.5 100.0 113.5 96.0 Q110.5 98.0 107.5 96.0 Z"),
            L(.body, 0xB92D25, 1.0, "M112.5 96.0 Q115.5 100.0 118.5 96.0 Q115.5 98.0 112.5 96.0 Z"),
            L(.body, 0xB92D25, 1.0, "M117.5 96.0 Q120.5 100.0 123.5 96.0 Q120.5 98.0 117.5 96.0 Z"),
            L(.body, 0xB92D25, 1.0, "M105.8 106.0 Q108.8 110.0 111.8 106.0 Q108.8 108.0 105.8 106.0 Z"),
            L(.body, 0xB92D25, 1.0, "M111.5 106.0 Q114.5 110.0 117.5 106.0 Q114.5 108.0 111.5 106.0 Z"),
            L(.body, 0xB92D25, 1.0, "M117.2 106.0 Q120.2 110.0 123.2 106.0 Q120.2 108.0 117.2 106.0 Z"),
            L(.body, 0xB92D25, 1.0, "M103.8 116.0 Q106.8 120.0 109.8 116.0 Q106.8 118.0 103.8 116.0 Z"),
            L(.body, 0xB92D25, 1.0, "M109.5 116.0 Q112.5 120.0 115.5 116.0 Q112.5 118.0 109.5 116.0 Z"),
            L(.body, 0xB92D25, 1.0, "M115.2 116.0 Q118.2 120.0 121.2 116.0 Q118.2 118.0 115.2 116.0 Z"),
            L(.body, 0xB92D25, 1.0, "M99.7 126.0 Q102.7 130.0 105.7 126.0 Q102.7 128.0 99.7 126.0 Z"),
            L(.body, 0xB92D25, 1.0, "M105.0 126.0 Q108.0 130.0 111.0 126.0 Q108.0 128.0 105.0 126.0 Z"),
            L(.body, 0xB92D25, 1.0, "M110.3 126.0 Q113.3 130.0 116.3 126.0 Q113.3 128.0 110.3 126.0 Z"),
            L(.wing, 0x2A5FA0, 1.0, "M84 104 C88 124 88 142 82 156 C78 148 76 128 78 108 Z"),
            L(.wing, 0x2F6DB5, 1.0, "M88 104 C92 124 92 142 86 158 C82 150 80 130 82 108 Z"),
            L(.wing, 0x3A7CC8, 1.0, "M92 102 C98 120 98 140 92 156 C88 148 86 126 88 104 Z"),
            L(.wing, 0x2F6DB5, 1.0, "M98 100 C104 118 104 136 98 152 C94 146 92 124 94 102 Z"),
            L(.wing, 0x1E3F78, 1.0, "M86 112 C88 128 88 142 85 154 L84 154 C86 142 86 128 85 112 Z"),
            L(.wing, 0x1E3F78, 1.0, "M92 110 C94 126 94 140 91 153 L90 153 C92 140 92 126 91 110 Z"),
            L(.wing, 0x1E3F78, 1.0, "M97 108 C99 122 99 136 96 149 L95 149 C97 136 97 122 96 108 Z"),
            L(.wing, 0x2F8C9A, 1.0, "M77 98 C92 93 106 98 111 105 C104 113 90 115 76 112 Z"),
            L(.wing, 0xE9B43A, 1.0, "M78 88 C92 83 106 90 110 100 C100 106 88 106 77 102 Z"),
            L(.wing, 0x8FA83A, 1.0, "M78.8 101.2 Q81.4 104.5 84.0 101.2 Q81.4 102.7 78.8 101.2 Z"),
            L(.wing, 0x8FA83A, 1.0, "M83.7 101.5 Q86.2 104.9 88.8 101.5 Q86.2 103.1 83.7 101.5 Z"),
            L(.wing, 0x8FA83A, 1.0, "M88.5 101.8 Q91.1 105.2 93.7 101.8 Q91.1 103.4 88.5 101.8 Z"),
            L(.wing, 0x8FA83A, 1.0, "M93.3 102.2 Q95.9 105.5 98.5 102.2 Q95.9 103.7 93.3 102.2 Z"),
            L(.wing, 0x8FA83A, 1.0, "M98.2 102.5 Q100.8 105.9 103.3 102.5 Q100.8 104.1 98.2 102.5 Z"),
            L(.wing, 0x8FA83A, 1.0, "M103.0 102.8 Q105.6 106.2 108.2 102.8 Q105.6 104.4 103.0 102.8 Z"),
            L(.wing, 0xD2362B, 1.0, "M80 82 C94 77 106 85 107 96 C98 99 88 98 78 94 Z"),
            L(.wing, 0xB92D25, 1.0, "M80.0 89.3 Q82.4 92.4 84.8 89.3 Q82.4 90.7 80.0 89.3 Z"),
            L(.wing, 0xB92D25, 1.0, "M84.8 89.9 Q87.2 93.0 89.6 89.9 Q87.2 91.3 84.8 89.9 Z"),
            L(.wing, 0xB92D25, 1.0, "M89.6 90.5 Q92.0 93.6 94.4 90.5 Q92.0 91.9 89.6 90.5 Z"),
            L(.wing, 0xB92D25, 1.0, "M94.4 91.1 Q96.8 94.2 99.2 91.1 Q96.8 92.5 94.4 91.1 Z"),
            L(.wing, 0xB92D25, 1.0, "M99.2 91.7 Q101.6 94.8 104.0 91.7 Q101.6 93.1 99.2 91.7 Z"),
            L(.wing, 0xB92D25, 1.0, "M82.0 84.1 Q84.2 87.0 86.5 84.1 Q84.2 85.4 82.0 84.1 Z"),
            L(.wing, 0xB92D25, 1.0, "M86.5 84.4 Q88.8 87.2 91.0 84.4 Q88.8 85.7 86.5 84.4 Z"),
            L(.wing, 0xB92D25, 1.0, "M91.0 84.6 Q93.2 87.5 95.5 84.6 Q93.2 85.9 91.0 84.6 Z"),
            L(.wing, 0xB92D25, 1.0, "M95.5 84.9 Q97.8 87.7 100.0 84.9 Q97.8 86.2 95.5 84.9 Z"),
            L(.head, 0xD2362B, 1.0, "E 104 56 21 20"),
            L(.head, 0xB92D25, 1.0, "M92 40 Q100 36 110 38 Q100 38 92 42 Z"),
            L(.head, 0xF3ECE1, 1.0, "E 112 57 10.5 9.5"),
            L(.head, 0xB8473C, 1.0, "M104 59 Q109 61 115 59 L115 60 Q109 62 104 60 Z"),
            L(.head, 0xB8473C, 1.0, "M105 63 Q110 65 116 63 L116 64 Q110 66 105 64 Z"),
            L(.head, 0xB8473C, 1.0, "M106 52 Q108 51 110 51 L110 52 Q108 52 106 53 Z"),
            L(.eye, 0xF2D98A, 1.0, "E 113 55 3.6 3.6"),
            L(.eye, 0x111111, 1.0, "E 113 55 2.1 2.1"),
            L(.eye, 0xFFFFFF, 1.0, "E 113.8 54.2 0.8 0.8"),
            L(.head, 0xEDE3D1, 1.0, "M119 44 C134 42 143 54 139 70 C136 63 130 59 121 61 Z"),
            L(.head, 0x3A302B, 1.0, "M119 44 C120 43.6 121 43.6 122 44 C121 50 120.5 56 120 61 L119 61 Z"),
            L(.head, 0x3A302B, 1.0, "M138 62 C139 65 139 68 139 70 C138 68 137 66 135 65 Z"),
            L(.head, 0x2B2522, 1.0, "M121 61 C128 61 132 65 130 72 C125 72 121 68 119 64 Z"),
            L(.fixed, 0x6F6E74, 1.0, "M86 148 C88 145 94 145 96 148 L96 152 C94 150 88 150 86 152 Z"),
            L(.fixed, 0x6F6E74, 1.0, "M98 148 C100 145 106 145 108 148 L108 152 C106 150 100 150 98 152 Z"),
            L(.fixed, 0x3A393E, 1.0, "M86 152 L85 155 L87 154 Z"),
            L(.fixed, 0x3A393E, 1.0, "M96 152 L95 155 L97 154 Z"),
            L(.fixed, 0x3A393E, 1.0, "M98 152 L97 155 L99 154 Z"),
            L(.fixed, 0x3A393E, 1.0, "M108 152 L107 155 L109 154 Z"),
        ]
        }
    }

    /// Absolute M, L, C, Q and Z commands, plus "E cx cy rx ry" for an ellipse.
    static func parse(_ d: String) -> Path {
        // Split "M92 66 C114 62" into M, 92, 66, C, 114, 62: a command letter may touch its number.
        var tokens: [String] = []
        var current = ""
        func flush() { if !current.isEmpty { tokens.append(current); current = "" } }
        for ch in d {
            if ch.isLetter {
                flush()
                tokens.append(String(ch))
            } else if ch == " " || ch == "," {
                flush()
            } else if ch == "-", !current.isEmpty {
                flush()
                current.append(ch)
            } else {
                current.append(ch)
            }
        }
        flush()

        var path = Path()
        var i = 0
        func n() -> CGFloat {
            defer { i += 1 }
            return i < tokens.count ? CGFloat(Double(tokens[i]) ?? 0) : 0
        }
        func p() -> CGPoint { let x = n(); return CGPoint(x: x, y: n()) }
        while i < tokens.count {
            let token = tokens[i]
            i += 1
            switch token {
            case "M": path.move(to: p())
            case "L": path.addLine(to: p())
            case "C": let c1 = p(), c2 = p(); path.addCurve(to: p(), control1: c1, control2: c2)
            case "Q": let c = p(); path.addQuadCurve(to: p(), control: c)
            case "Z": path.closeSubpath()
            case "E":
                let cx = n(), cy = n(), rx = n(), ry = n()
                path.addEllipse(in: CGRect(x: cx - rx, y: cy - ry, width: rx * 2, height: ry * 2))
            default:
                break
            }
        }
        return path
    }
}
