import ActivityKit
import SwiftUI
import WidgetKit

@main
struct AraviaWidgets: WidgetBundle {
    var body: some Widget {
        WorkoutLiveActivity()
    }
}

/// Lock screen and Dynamic Island while a workout runs in Aravia: time, heart rate and its zone,
/// distance or the current exercise, and the rest countdown between sets.
struct WorkoutLiveActivity: Widget {
    private static let urucum = Color(red: 0xC0 / 255, green: 0x41 / 255, blue: 0x2A / 255)
    private static let jenipapo = Color(red: 0x16 / 255, green: 0x20 / 255, blue: 0x2B / 255)
    private static let palha = Color(red: 0xE9 / 255, green: 0xD9 / 255, blue: 0xB8 / 255)
    private static let zones: [Color] = [
        .gray,
        Color(red: 0.91, green: 0.85, blue: 0.72),
        Color(red: 0.88, green: 0.70, blue: 0.45),
        Color(red: 0.84, green: 0.53, blue: 0.30),
        Color(red: 0.79, green: 0.36, blue: 0.22),
        Color(red: 0.75, green: 0.25, blue: 0.16),
    ]

    var body: some WidgetConfiguration {
        ActivityConfiguration(for: WorkoutActivityAttributes.self) { context in
            lockScreen(context)
                .activityBackgroundTint(Self.jenipapo)
                .activitySystemActionForegroundColor(Self.palha)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label { Text(verbatim: context.attributes.title) } icon: { Image(systemName: context.attributes.symbol) }
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(Self.palha)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    heart(context.state)
                        .font(.title3.weight(.bold))
                }
                DynamicIslandExpandedRegion(.center) {
                    timer(context)
                        .font(.title.weight(.heavy).monospacedDigit())
                        .multilineTextAlignment(.center)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    bottomLine(context.state)
                }
            } compactLeading: {
                Image(systemName: context.attributes.symbol).foregroundStyle(Self.urucum)
            } compactTrailing: {
                if let ends = context.state.restEndsAt, ends > .now {
                    Text(timerInterval: Date.now...ends, countsDown: true)
                        .monospacedDigit()
                        .frame(maxWidth: 44)
                        .foregroundStyle(Self.palha)
                } else {
                    heart(context.state).font(.caption.weight(.bold))
                }
            } minimal: {
                Image(systemName: "heart.fill").foregroundStyle(Self.zones[context.state.zone])
            }
            .keylineTint(Self.urucum)
        }
    }

    private func lockScreen(_ context: ActivityViewContext<WorkoutActivityAttributes>) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Label { Text(verbatim: context.attributes.title) } icon: { Image(systemName: context.attributes.symbol) }
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(Self.palha)
                Spacer()
                Text(verbatim: "ARAVIA").font(.caption2.weight(.heavy)).kerning(3).foregroundStyle(Self.urucum)
            }
            HStack(alignment: .firstTextBaseline) {
                timer(context)
                    .font(.system(size: 40, weight: .heavy, design: .rounded).monospacedDigit())
                    .foregroundStyle(.white)
                Spacer()
                heart(context.state)
                    .font(.system(size: 30, weight: .bold, design: .rounded))
            }
            bottomLine(context.state)
        }
        .padding(16)
    }

    private func timer(_ context: ActivityViewContext<WorkoutActivityAttributes>) -> some View {
        Text(timerInterval: context.attributes.startedAt...Date.distantFuture, countsDown: false)
    }

    private func heart(_ state: WorkoutActivityAttributes.ContentState) -> some View {
        HStack(spacing: 3) {
            Image(systemName: "heart.fill").foregroundStyle(Self.zones[max(0, min(5, state.zone))])
            Text(verbatim: state.bpm.map(String.init) ?? "--").monospacedDigit().foregroundStyle(.white)
        }
    }

    @ViewBuilder private func bottomLine(_ state: WorkoutActivityAttributes.ContentState) -> some View {
        if let ends = state.restEndsAt, ends > .now {
            HStack {
                Image(systemName: "timer")
                Text(verbatim: "Descanso")
                Spacer()
                Text(timerInterval: Date.now...ends, countsDown: true).monospacedDigit().bold()
            }
            .font(.subheadline)
            .foregroundStyle(Self.palha)
        } else {
            HStack {
                Text(verbatim: state.detail).lineLimit(1)
                Spacer()
                HStack(spacing: 2) {
                    ForEach(1...5, id: \.self) { z in
                        Capsule().fill(z <= state.zone ? Self.zones[z] : Color.white.opacity(0.2)).frame(width: 12, height: 5)
                    }
                }
            }
            .font(.subheadline)
            .foregroundStyle(Self.palha)
        }
    }
}
