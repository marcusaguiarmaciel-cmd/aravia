import Foundation

/// A workout logged in Aravia: treadmill, gym or other, with the heart rate the watch sent live
/// and, for the gym, every exercise, set and load. Kept as JSON in Application Support, outside
/// the band's SwiftData store, so nothing there needs a migration.
struct TrainingSession: Codable, Identifiable, Hashable {
    enum Kind: String, Codable, CaseIterable, Identifiable {
        case treadmill, gym, other
        var id: String { rawValue }
        var name: String {
            switch self {
            case .treadmill: "Esteira"
            case .gym: "Musculação"
            case .other: "Treino livre"
            }
        }
        var symbol: String {
            switch self {
            case .treadmill: "figure.run.treadmill"
            case .gym: "dumbbell.fill"
            case .other: "figure.mixed.cardio"
            }
        }
    }

    struct WorkSet: Codable, Identifiable, Hashable {
        var id = UUID()
        var reps: Int
        var kg: Double
        var doneAt: Date
    }

    struct Entry: Codable, Identifiable, Hashable {
        var id = UUID()
        var exercise: String
        var sets: [WorkSet] = []
    }

    struct Beat: Codable, Hashable {
        var t: Date
        var bpm: Int
    }

    var id = UUID()
    var kind: Kind
    var start: Date
    var end: Date?
    var entries: [Entry] = []
    var heart: [Beat] = []
    /// Steps counted by the watch during the workout.
    var steps = 0
    /// Distance the person typed in from the treadmill's display, which beats the step estimate.
    var typedDistanceMeters: Double?

    var duration: TimeInterval { (end ?? Date()).timeIntervalSince(start) }
    var avgBPM: Int? { heart.isEmpty ? nil : heart.map(\.bpm).reduce(0, +) / heart.count }
    var maxBPM: Int? { heart.map(\.bpm).max() }
    var setCount: Int { entries.reduce(0) { $0 + $1.sets.count } }
    var volumeKg: Double { entries.reduce(0) { $0 + $1.sets.reduce(0) { $0 + Double($1.reps) * $1.kg } } }

    /// Typed distance, else steps times a stride from the profile's height (0.415 × height when
    /// walking or jogging; a rough rule, so the screen calls it an estimate).
    func distanceMeters(heightCm: Double?) -> Double {
        if let typedDistanceMeters { return typedDistanceMeters }
        return Double(steps) * (heightCm ?? 170) * 0.415 / 100
    }
}

enum ExerciseCatalog {
    static let groups: [(String, [String])] = [
        ("Peito", ["Supino reto", "Supino inclinado", "Supino com halteres", "Crucifixo", "Crossover", "Flexão de braço"]),
        ("Costas", ["Puxada frontal", "Remada curvada", "Remada baixa", "Remada unilateral", "Barra fixa", "Levantamento terra"]),
        ("Pernas", ["Agachamento livre", "Leg press", "Cadeira extensora", "Mesa flexora", "Afundo", "Stiff", "Elevação pélvica", "Panturrilha"]),
        ("Ombros", ["Desenvolvimento", "Elevação lateral", "Elevação frontal", "Crucifixo invertido", "Encolhimento"]),
        ("Braços", ["Rosca direta", "Rosca martelo", "Rosca scott", "Tríceps pulley", "Tríceps testa", "Mergulho"]),
        ("Core", ["Prancha", "Abdominal", "Abdominal infra", "Russian twist"]),
    ]
}

@MainActor
final class TrainingStore: ObservableObject {
    static let shared = TrainingStore()

    @Published private(set) var sessions: [TrainingSession] = []
    @Published private(set) var customExercises: [String] = []

    private struct File: Codable {
        var sessions: [TrainingSession]
        var customExercises: [String]
    }

    private let url: URL = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir.appendingPathComponent("aravia-training.json")
    }()

    private init() {
        if let data = try? Data(contentsOf: url), let file = try? JSONDecoder().decode(File.self, from: data) {
            sessions = file.sessions
            customExercises = file.customExercises
        }
    }

    private func save() {
        let file = File(sessions: sessions, customExercises: customExercises)
        if let data = try? JSONEncoder().encode(file) { try? data.write(to: url, options: .atomic) }
    }

    func add(_ session: TrainingSession) {
        sessions.insert(session, at: 0)
        save()
    }

    func update(_ session: TrainingSession) {
        guard let i = sessions.firstIndex(where: { $0.id == session.id }) else { return }
        sessions[i] = session
        save()
    }

    func delete(_ session: TrainingSession) {
        sessions.removeAll { $0.id == session.id }
        save()
    }

    func addExercise(_ name: String) {
        let clean = name.trimmingCharacters(in: .whitespaces)
        guard !clean.isEmpty, !customExercises.contains(clean),
              !ExerciseCatalog.groups.contains(where: { $0.1.contains(clean) }) else { return }
        customExercises.append(clean)
        save()
    }

    /// The last set done of an exercise, to fill in the next one.
    func lastSet(of exercise: String) -> TrainingSession.WorkSet? {
        for session in sessions {
            if let set = session.entries.last(where: { $0.exercise == exercise })?.sets.last { return set }
        }
        return nil
    }
}
