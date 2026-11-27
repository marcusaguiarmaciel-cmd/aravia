import Charts
import SwiftUI

/// The workout in progress: time, live heart rate with its zone, and either the treadmill's
/// distance and steps or the gym log (exercises, sets, load and the rest timer).
struct ActiveWorkoutView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var controller = WorkoutController.shared
    @State private var pickingExercise = false
    @State private var confirmEnd = false
    @State private var typedKm = ""

    var body: some View {
        NavigationStack {
            Group {
                if let session = controller.session {
                    ScrollView {
                        VStack(spacing: 16) {
                            header(session)
                            switch session.kind {
                            case .gym: gym(session)
                            case .treadmill, .other: cardio(session)
                            }
                        }
                        .padding(16)
                    }
                } else {
                    VStack(spacing: 12) {
                        Image(systemName: "checkmark.circle.fill").font(.system(size: 54)).foregroundStyle(Color.good)
                        Text(verbatim: "Treino salvo").font(.title2.weight(.bold))
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
            }
            .background(Color.canvas.ignoresSafeArea())
            .navigationTitle(Text(verbatim: controller.session?.kind.name ?? "Treino"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button { dismiss() } label: { Text(verbatim: "Minimizar") }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button { confirmEnd = true } label: { Text(verbatim: "Encerrar").bold() }
                        .tint(Color.action)
                        .disabled(controller.session == nil)
                }
            }
            .confirmationDialog(Text(verbatim: "Encerrar o treino?"), isPresented: $confirmEnd, titleVisibility: .visible) {
                Button { controller.finish(); dismiss() } label: { Text(verbatim: "Salvar e encerrar") }
                Button(role: .destructive) { controller.discard(); dismiss() } label: { Text(verbatim: "Descartar treino") }
            }
            .sheet(isPresented: $pickingExercise) {
                ExercisePicker { controller.addEntry($0) }
            }
        }
        .tint(Color.action)
    }

    // MARK: header: time and heart

    private func header(_ s: TrainingSession) -> some View {
        let zone = WorkoutController.zone(controller.bpm)
        return VStack(spacing: 10) {
            Text(timerInterval: s.start...Date.distantFuture, countsDown: false)
                .font(.system(size: 64, weight: .heavy, design: .rounded).monospacedDigit())
                .foregroundStyle(Color.textPrimary)
            HStack(alignment: .firstTextBaseline, spacing: 6) {
                Image(systemName: "heart.fill").foregroundStyle(Color.heart)
                Text(verbatim: controller.bpm.map(String.init) ?? "--")
                    .font(.system(size: 44, weight: .bold, design: .rounded).monospacedDigit())
                    .foregroundStyle(Color.textPrimary)
                Text(verbatim: "bpm").font(.headline).foregroundStyle(Color.textSecondary)
            }
            HStack(spacing: 4) {
                ForEach(1...5, id: \.self) { z in
                    RoundedRectangle(cornerRadius: 3)
                        .fill(z <= zone ? zoneColor(z) : Color.textSecondary.opacity(0.18))
                        .frame(height: 8)
                }
            }
            Text(verbatim: controller.bpm == nil
                 ? "Esperando o batimento do relógio…"
                 : "Zona \(zone): \(WorkoutController.zoneNames[zone])")
                .font(.footnote)
                .foregroundStyle(Color.textSecondary)
            if s.heart.count > 2 {
                Chart(s.heart, id: \.t) { beat in
                    LineMark(x: .value("t", beat.t), y: .value("bpm", beat.bpm))
                        .foregroundStyle(Color.heart)
                        .interpolationMethod(.monotone)
                }
                .chartXAxis(.hidden)
                .frame(height: 80)
            }
        }
        .padding(16)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }

    private func zoneColor(_ z: Int) -> Color {
        [Color.zone1, .zone2, .zone3, .zone4, .zone5][max(0, min(4, z - 1))]
    }

    // MARK: treadmill

    private func cardio(_ s: TrainingSession) -> some View {
        let meters = s.distanceMeters(heightCm: Profile.load().heightCm)
        return VStack(spacing: 14) {
            HStack {
                stat(RouteNames.km(meters), s.typedDistanceMeters == nil ? "distância estimada" : "distância do painel")
                stat(s.steps.formatted(.number.locale(Locale(identifier: "pt_BR"))), "passos")
                stat(pace(meters: meters, seconds: s.duration), "ritmo /km")
            }
            VStack(alignment: .leading, spacing: 6) {
                Text(verbatim: "Distância no painel da esteira").font(.subheadline.weight(.semibold))
                HStack {
                    TextField(text: $typedKm) { Text(verbatim: "ex.: 3,2") }
                        .keyboardType(.decimalPad)
                        .textFieldStyle(.roundedBorder)
                    Text(verbatim: "km")
                    Button {
                        let value = Double(typedKm.replacingOccurrences(of: ",", with: "."))
                        controller.setTypedDistance(value.map { $0 * 1000 })
                    } label: { Text(verbatim: "Usar") }
                    .buttonStyle(.bordered)
                }
                Text(verbatim: "Na esteira o relógio estima a distância pelos passos. Digite o número do painel para o treino ficar certo.")
                    .font(.caption)
                    .foregroundStyle(Color.textSecondary)
            }
            .padding(14)
            .background(Color.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }

    private func pace(meters: Double, seconds: TimeInterval) -> String {
        guard meters > 50 else { return "--" }
        let perKm = seconds / (meters / 1000)
        return String(format: "%d:%02d", Int(perKm) / 60, Int(perKm) % 60)
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(verbatim: value).font(.title3.weight(.bold).monospacedDigit()).foregroundStyle(Color.textPrimary)
            Text(verbatim: label).font(.caption).foregroundStyle(Color.textSecondary)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    // MARK: gym

    private func gym(_ s: TrainingSession) -> some View {
        VStack(spacing: 14) {
            if let ends = controller.restEndsAt {
                RestBanner(endsAt: ends) { controller.skipRest() }
            }
            HStack {
                stat("\(s.setCount)", "séries")
                stat(String(format: "%.0f kg", s.volumeKg), "volume")
                stat("\(s.entries.count)", "exercícios")
            }
            ForEach(s.entries) { entry in
                EntryCard(entry: entry)
            }
            Button { pickingExercise = true } label: {
                Label { Text(verbatim: "Adicionar exercício") } icon: { Image(systemName: "plus") }
                    .font(.headline)
                    .frame(maxWidth: .infinity, minHeight: 50)
            }
            .buttonStyle(.borderedProminent)
            Stepper(value: $controller.restSeconds, in: 30...300, step: 15) {
                Text(verbatim: "Descanso entre séries: \(controller.restSeconds) s")
                    .font(.subheadline)
            }
            .padding(14)
            .background(Color.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
    }
}

private struct RestBanner: View {
    let endsAt: Date
    let skip: () -> Void

    var body: some View {
        HStack {
            Image(systemName: "timer").font(.title2)
            VStack(alignment: .leading) {
                Text(verbatim: "Descanso").font(.caption.weight(.semibold))
                Text(timerInterval: Date()...endsAt, countsDown: true)
                    .font(.title.weight(.bold).monospacedDigit())
            }
            Spacer()
            Button(action: skip) { Text(verbatim: "Pular") }
                .buttonStyle(.bordered)
                .tint(Color.onAction)
        }
        .foregroundStyle(Color.onAction)
        .padding(14)
        .background(Color.action, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

private struct EntryCard: View {
    let entry: TrainingSession.Entry
    @StateObject private var controller = WorkoutController.shared
    @State private var reps = 10
    @State private var kg = 20.0
    @State private var filled = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(verbatim: entry.exercise).font(.headline).foregroundStyle(Color.textPrimary)
                Spacer()
                Menu {
                    Button(role: .destructive) { controller.removeEntry(entry.id) } label: { Text(verbatim: "Remover exercício") }
                } label: { Image(systemName: "ellipsis.circle") }
            }
            ForEach(Array(entry.sets.enumerated()), id: \.element.id) { i, set in
                HStack {
                    Text(verbatim: "\(i + 1)").font(.caption.weight(.bold)).frame(width: 22, height: 22)
                        .background(Color.action.opacity(0.15), in: Circle())
                    Text(verbatim: "\(set.reps) × \(Self.kgText(set.kg))")
                        .font(.body.monospacedDigit())
                    Spacer()
                    Text(set.doneAt, format: .dateTime.hour().minute()).font(.caption).foregroundStyle(Color.textSecondary)
                }
                .swipeActions { Button(role: .destructive) { controller.removeSet(entry: entry.id, set: set.id) } label: { Text(verbatim: "Apagar") } }
                .contextMenu { Button(role: .destructive) { controller.removeSet(entry: entry.id, set: set.id) } label: { Text(verbatim: "Apagar série") } }
            }
            HStack(spacing: 8) {
                Stepper(value: $reps, in: 1...100) { Text(verbatim: "\(reps) rep").monospacedDigit() }
                Stepper(value: $kg, in: 0...500, step: 2.5) { Text(verbatim: Self.kgText(kg)).monospacedDigit() }
            }
            .font(.subheadline)
            Button { controller.logSet(entry: entry.id, reps: reps, kg: kg) } label: {
                Text(verbatim: "Série \(entry.sets.count + 1) feita").frame(maxWidth: .infinity, minHeight: 40)
            }
            .buttonStyle(.bordered)
        }
        .padding(14)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .onAppear {
            guard !filled else { return }
            filled = true
            if let last = entry.sets.last ?? TrainingStore.shared.lastSet(of: entry.exercise) {
                reps = last.reps
                kg = last.kg
            }
        }
    }

    static func kgText(_ kg: Double) -> String {
        (kg.rounded() == kg ? String(format: "%.0f kg", kg) : String(format: "%.1f kg", kg))
            .replacingOccurrences(of: ".", with: ",")
    }
}

struct ExercisePicker: View {
    let pick: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @StateObject private var store = TrainingStore.shared
    @State private var search = ""

    var body: some View {
        NavigationStack {
            List {
                if !search.isEmpty {
                    Button {
                        store.addExercise(search)
                        pick(search.trimmingCharacters(in: .whitespaces))
                        dismiss()
                    } label: { Label { Text(verbatim: "Criar \"\(search)\"") } icon: { Image(systemName: "plus") } }
                }
                if !store.customExercises.isEmpty {
                    section("Meus exercícios", store.customExercises)
                }
                ForEach(ExerciseCatalog.groups, id: \.0) { group in
                    section(group.0, group.1)
                }
            }
            .searchable(text: $search, prompt: Text(verbatim: "Buscar ou criar exercício"))
            .navigationTitle(Text(verbatim: "Exercício"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button { dismiss() } label: { Text(verbatim: "Cancelar") } }
            }
        }
    }

    @ViewBuilder private func section(_ title: String, _ names: [String]) -> some View {
        let shown = search.isEmpty ? names : names.filter { $0.localizedCaseInsensitiveContains(search) }
        if !shown.isEmpty {
            Section { ForEach(shown, id: \.self) { name in
                Button { pick(name); dismiss() } label: { Text(verbatim: name).foregroundStyle(Color.textPrimary) }
            } } header: { Text(verbatim: title) }
        }
    }
}

/// A saved workout: heart-rate curve, numbers and, for the gym, every set.
struct SessionDetailView: View {
    let session: TrainingSession
    @Environment(\.dismiss) private var dismiss
    @State private var confirmDelete = false

    var body: some View {
        List {
            Section {
                LabeledContent { Text(verbatim: RouteNames.clock(session.duration)) } label: { Text(verbatim: "Duração") }
                if let avg = session.avgBPM { LabeledContent { Text(verbatim: "\(avg) bpm") } label: { Text(verbatim: "Batimento médio") } }
                if let max = session.maxBPM { LabeledContent { Text(verbatim: "\(max) bpm") } label: { Text(verbatim: "Batimento máximo") } }
                if session.kind != .gym {
                    LabeledContent { Text(verbatim: RouteNames.km(session.distanceMeters(heightCm: Profile.load().heightCm))) } label: { Text(verbatim: "Distância") }
                    LabeledContent { Text(verbatim: "\(session.steps)") } label: { Text(verbatim: "Passos") }
                } else {
                    LabeledContent { Text(verbatim: "\(session.setCount)") } label: { Text(verbatim: "Séries") }
                    LabeledContent { Text(verbatim: String(format: "%.0f kg", session.volumeKg)) } label: { Text(verbatim: "Volume total") }
                }
            }
            if session.heart.count > 2 {
                Section {
                    Chart(session.heart, id: \.t) { beat in
                        LineMark(x: .value("Hora", beat.t), y: .value("bpm", beat.bpm))
                            .foregroundStyle(Color.heart)
                            .interpolationMethod(.monotone)
                    }
                    .frame(height: 160)
                } header: { Text(verbatim: "Batimentos") }
            }
            ForEach(session.entries) { entry in
                Section {
                    ForEach(Array(entry.sets.enumerated()), id: \.element.id) { i, set in
                        HStack {
                            Text(verbatim: "Série \(i + 1)")
                            Spacer()
                            Text(verbatim: "\(set.reps) × \(String(format: "%.1f", set.kg).replacingOccurrences(of: ".", with: ",")) kg")
                                .monospacedDigit()
                                .foregroundStyle(Color.textSecondary)
                        }
                    }
                } header: { Text(verbatim: entry.exercise) }
            }
            Section {
                Button(role: .destructive) { confirmDelete = true } label: { Text(verbatim: "Apagar treino") }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Color.canvas.ignoresSafeArea())
        .navigationTitle(Text(verbatim: session.kind.name))
        .confirmationDialog(Text(verbatim: "Apagar este treino?"), isPresented: $confirmDelete, titleVisibility: .visible) {
            Button(role: .destructive) { TrainingStore.shared.delete(session); dismiss() } label: { Text(verbatim: "Apagar") }
        }
    }
}
