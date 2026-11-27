import SwiftUI
import UIKit

/// A look for the Story video: map, camera, route line, colours, which numbers show and where.
/// The Hub ships a few, and every one can be edited, duplicated or deleted; they are kept on the
/// phone as JSON.
struct StoryTemplate: Codable, Identifiable, Hashable {
    enum MapLook: String, Codable, CaseIterable, Identifiable {
        case liberty, bright, positron
        var id: String { rawValue }
        var name: String {
            switch self {
            case .liberty: "Natural"
            case .bright: "Vivo"
            case .positron: "Claro"
            }
        }
        var styleURL: URL { URL(string: "https://tiles.openfreemap.org/styles/\(rawValue)")! }
    }

    enum Camera: String, Codable, CaseIterable, Identifiable {
        case close, street, high, drone
        var id: String { rawValue }
        var name: String {
            switch self {
            case .close: "Bem perto"
            case .street: "Rua"
            case .high: "Do alto"
            case .drone: "Drone girando"
            }
        }
        var zoom: Double {
            switch self {
            case .close: 18.0
            case .street: 17.4
            case .high: 16.3
            case .drone: 16.8
            }
        }
        var pitch: Double {
            switch self {
            case .close: 65
            case .street: 60
            case .high: 40
            case .drone: 55
            }
        }
    }

    enum LineColoring: String, Codable, CaseIterable, Identifiable {
        case speed, single
        var id: String { rawValue }
        var name: String { self == .speed ? "Pela velocidade" : "Uma cor só" }
    }

    enum Layout: String, Codable, CaseIterable, Identifiable {
        case classic, minimal, big
        var id: String { rawValue }
        var name: String {
            switch self {
            case .classic: "Clássico"
            case .minimal: "Discreto"
            case .big: "Número grande"
            }
        }
    }

    var id = UUID()
    var name: String
    var map: MapLook = .liberty
    /// 0 to 0.7: darkens the map, for night looks.
    var mapDim: Double = 0
    var camera: Camera = .street
    /// Seconds of route playback, before the intro and the final still.
    var seconds: Double = 12
    var lineColoring: LineColoring = .speed
    var lineHex: UInt32 = 0xC0412A
    var lineWidth: Double = 9
    var accentHex: UInt32 = 0xC0412A
    /// Colour of the shades behind the text.
    var inkHex: UInt32 = 0x16202B
    var shades = true
    var layout: Layout = .classic
    /// Empty: the route's own name.
    var customTitle = ""
    var showDate = true
    var showDistance = true
    var showTime = true
    var showSpeed = true
    var showProgress = true
    var showBrand = true

    var lineColor: UIColor { UIColor(hex: lineHex) }
    var accent: UIColor { UIColor(hex: accentHex) }
    var ink: UIColor { UIColor(hex: inkHex) }

    static let builtIn: [StoryTemplate] = [
        StoryTemplate(name: "Aravia"),
        StoryTemplate(name: "Noite", mapDim: 0.5, camera: .close, lineColoring: .single, lineHex: 0x4DC0CC,
                      accentHex: 0x4DC0CC, inkHex: 0x0B1016),
        StoryTemplate(name: "Discreto", map: .positron, camera: .high, lineColoring: .single, lineHex: 0x16202B,
                      lineWidth: 7, accentHex: 0x16202B, shades: false, layout: .minimal, showDate: false, showBrand: false),
        StoryTemplate(name: "Drone", map: .bright, camera: .drone, seconds: 15, accentHex: 0x2C7A50, inkHex: 0x10241A),
        StoryTemplate(name: "Ritmo", camera: .close, seconds: 10, lineWidth: 12, accentHex: 0xC98A1F, layout: .big,
                      showTime: false),
        StoryTemplate(name: "Pôr do sol", mapDim: 0.2, lineColoring: .single, lineHex: 0xF07A50, lineWidth: 11,
                      accentHex: 0xF07A50, inkHex: 0x4A1E12),
    ]
}

@MainActor
final class StoryTemplateStore: ObservableObject {
    static let shared = StoryTemplateStore()
    private static let key = "aravia.storyTemplates"

    @Published var templates: [StoryTemplate] {
        didSet { save() }
    }

    private init() {
        if let data = UserDefaults.standard.data(forKey: Self.key),
           let saved = try? JSONDecoder().decode([StoryTemplate].self, from: data), !saved.isEmpty {
            templates = saved
        } else {
            templates = StoryTemplate.builtIn
        }
    }

    private func save() {
        if let data = try? JSONEncoder().encode(templates) { UserDefaults.standard.set(data, forKey: Self.key) }
    }

    func update(_ template: StoryTemplate) {
        if let i = templates.firstIndex(where: { $0.id == template.id }) { templates[i] = template }
    }

    @discardableResult
    func duplicate(_ template: StoryTemplate) -> StoryTemplate {
        var copy = template
        copy.id = UUID()
        copy.name = template.name + " (cópia)"
        let at = (templates.firstIndex(where: { $0.id == template.id }) ?? templates.count - 1) + 1
        templates.insert(copy, at: at)
        return copy
    }

    func delete(_ template: StoryTemplate) {
        templates.removeAll { $0.id == template.id }
        if templates.isEmpty { templates = StoryTemplate.builtIn }
    }

    /// Puts back the built-in looks that were deleted or changed, keeping the person's own.
    func restoreBuiltIns() {
        let builtInNames = Set(StoryTemplate.builtIn.map(\.name))
        templates = StoryTemplate.builtIn + templates.filter { !builtInNames.contains($0.name) }
    }
}

extension UIColor {
    var hex: UInt32 {
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        getRed(&r, green: &g, blue: &b, alpha: &a)
        func c(_ v: CGFloat) -> UInt32 { UInt32((min(max(v, 0), 1) * 255).rounded()) }
        return c(r) << 16 | c(g) << 8 | c(b)
    }
}
