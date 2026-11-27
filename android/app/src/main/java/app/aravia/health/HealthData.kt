package app.aravia.health

import app.aravia.analytics.DayInputs
import app.aravia.analytics.DayScoreResult
import app.aravia.analytics.HrvMetricKind
import app.aravia.analytics.MinuteSeries
import app.aravia.analytics.Profile
import app.aravia.analytics.RRSegment
import app.aravia.analytics.ScoreEngine
import app.aravia.analytics.SleepInput
import app.aravia.analytics.StageKind
import app.aravia.analytics.StageSegment
import app.aravia.analytics.TimedValue
import app.aravia.band.ActivityStore
import app.aravia.protocol.ActivityFileID
import app.aravia.protocol.ActivityPayload
import app.aravia.protocol.DailySummary
import app.aravia.protocol.SleepDetails
import app.aravia.protocol.SleepSession
import app.aravia.protocol.SleepStage
import app.aravia.protocol.SleepStageSample
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt

/**
 * Unified daily health model merging direct BLE watch files (Xiaomi/Redmi) with Android Health
 * Connect snapshots (Galaxy Watch, Garmin, Amazfit/Zepp, Polar, Fitbit, Wear OS, Mi Fitness, etc.)
 * and BLE 0x2A37 Spot HRV check-ins, scored by ScoreEngine v4.
 */
data class DayHealth(
    val day: LocalDate,
    val summary: DailySummary?,
    /** Minute heart rate (Unix seconds to bpm), readings of 30-220 only. */
    val heart: List<Pair<Long, Int>>,
    /** Steps summed from the minute records, for a day without a summary. */
    val minuteSteps: Int,
    /** Recovery, Sleep and Strain scores for this day. */
    val score: DayScoreResult? = null,
) {
    val steps: Int? get() = summary?.steps ?: minuteSteps.takeIf { it > 0 }
}

/** A night, filed under the day it ends on. */
data class Night(val day: LocalDate, val session: SleepSession) {
    val asleepMinutes: Int get() = listOfNotNull(session.deepMinutes, session.lightMinutes, session.remMinutes).sum()
        .takeIf { it > 0 } ?: (((session.wakeTime - session.bedTime) / 60).toInt() - (session.awakeMinutes ?: 0))
}

class HealthHistory(val days: Map<LocalDate, DayHealth>, val nights: List<Night>) {
    val lastDay: LocalDate? get() = days.keys.maxOrNull()
    val totalSteps: Int get() = days.values.sumOf { it.steps ?: 0 }
    fun day(d: LocalDate): DayHealth? = days[d]
    fun night(d: LocalDate): Night? = nights.lastOrNull { it.day == d }
    fun score(d: LocalDate): DayScoreResult? = days[d]?.score

    companion object {
        /** The local day of a time, at the UTC offset the band wrote with the file. */
        private fun localDay(seconds: Long, offsetMinutes: Int): LocalDate =
            Instant.ofEpochSecond(seconds).atOffset(ZoneOffset.ofTotalSeconds(offsetMinutes.coerceIn(-1080, 1080) * 60)).toLocalDate()

        private fun stageKind(s: SleepStage): StageKind = when (s) {
            SleepStage.AWAKE -> StageKind.AWAKE
            SleepStage.LIGHT -> StageKind.LIGHT
            SleepStage.DEEP -> StageKind.DEEP
            SleepStage.REM -> StageKind.REM
            SleepStage.NOT_SLEEP -> StageKind.NOT_SLEEP
            SleepStage.UNKNOWN -> StageKind.UNKNOWN
        }

        private fun sleepStage(k: StageKind): SleepStage = when (k) {
            StageKind.AWAKE -> SleepStage.AWAKE
            StageKind.LIGHT -> SleepStage.LIGHT
            StageKind.DEEP -> SleepStage.DEEP
            StageKind.REM -> SleepStage.REM
            StageKind.NOT_SLEEP -> SleepStage.NOT_SLEEP
            StageKind.UNKNOWN -> SleepStage.UNKNOWN
        }

        fun load(profile: Profile = ProfileStore.profile.value): HealthHistory =
            build(ActivityStore.all(), profile, HealthConnectStore.allDays())

        fun build(
            files: List<Pair<ActivityFileID, ActivityPayload>>,
            profile: Profile = Profile.UNKNOWN,
            hcDays: Map<LocalDate, HealthConnectStore.DaySnapshot> = emptyMap(),
        ): HealthHistory {
            val summaries = HashMap<LocalDate, DailySummary>()
            val heart = HashMap<LocalDate, MutableList<Pair<Long, Int>>>()
            val steps = HashMap<LocalDate, Int>()
            val minuteSeries = HashMap<LocalDate, MinuteSeries>()
            val sessions = mutableListOf<Pair<SleepSession, Int>>()
            val detailsByBed = HashMap<Long, SleepDetails>()
            val directBleDays = HashSet<LocalDate>()

            for ((id, p) in files) {
                val offset = id.utcOffsetMinutes
                when (p) {
                    is ActivityPayload.Summary -> {
                        val d = localDay(id.timestamp, offset)
                        summaries[d] = p.summary
                        directBleDays += d
                    }
                    is ActivityPayload.Daily -> for (r in p.details.records) {
                        val d = localDay(r.ts, offset)
                        directBleDays += d
                        r.heartRate?.takeIf { it in 30..220 }?.let { heart.getOrPut(d) { mutableListOf() } += r.ts to it }
                        r.steps?.let { steps[d] = (steps[d] ?: 0) + it }
                        val ms = minuteSeries.getOrPut(d) { MinuteSeries(d.toEpochDay(), offset) }
                        val slot = ((r.ts - ms.startSec) / 60L).toInt()
                        if (slot in 0 until MinuteSeries.SLOTS) {
                            r.steps?.let { ms.steps[slot] = it }
                            r.heartRate?.takeIf { it in 30..220 }?.let { ms.heartRate[slot] = it }
                            r.spo2?.let { ms.spo2[slot] = it }
                            r.stress?.let { ms.stress[slot] = it }
                            r.activeCalories?.let { ms.kcal[slot] = it }
                            r.distanceMeters?.let { ms.distance[slot] = it }
                        }
                    }
                    is ActivityPayload.Sleep -> p.details.session?.let { s ->
                        sessions += s to offset
                        detailsByBed[s.bedTime] = p.details
                        directBleDays += localDay(s.wakeTime, offset)
                    }
                    is ActivityPayload.Stages -> p.stages.session?.let { s ->
                        sessions += s to offset
                        directBleDays += localDay(s.wakeTime, offset)
                    }
                    else -> {}
                }
            }

            val offsets = sessions.associate { it.first.bedTime to it.second }
            val merged = SleepSession.merge(sessions.map { it.first })
            val nightsList = merged.map { Night(localDay(it.wakeTime, offsets[it.bedTime] ?: 0), it) }.toMutableList()

            // Convert direct BLE nights to SleepInput per wake day for ScoreEngine.
            val sleepsByDay = HashMap<LocalDate, MutableList<SleepInput>>()
            for (n in nightsList) {
                val s = n.session
                val offset = offsets[s.bedTime] ?: 0
                val det = detailsByBed[s.bedTime]
                val segs = s.stages.mapIndexedNotNull { idx, st ->
                    val dur = st.durationMinutes ?: s.stages.getOrNull(idx + 1)?.let { ((it.ts - st.ts) / 60L).toInt() }
                        ?: ((s.wakeTime - st.ts) / 60L).toInt()
                    if (dur > 0) StageSegment(st.ts, dur, stageKind(st.stage)) else null
                }
                val hrSamples = det?.heartRate?.samples?.map { TimedValue(it.first, it.second) } ?: emptyList()
                val spo2Samples = det?.spo2?.samples?.map { TimedValue(it.first, it.second) } ?: emptyList()
                val rrSegs = det?.rrIntervals?.map { RRSegment(it.tsRaw, it.intervalsMs) } ?: emptyList()
                val wakeCount = det?.summaries?.lastOrNull()?.wakeCount
                val isNap = (s.wakeTime - s.bedTime) < 3 * 3600L
                val input = SleepInput(
                    bedTimeSec = s.bedTime, wakeTimeSec = s.wakeTime, tzOffsetMinutes = offset,
                    deepMinutes = s.deepMinutes, lightMinutes = s.lightMinutes,
                    remMinutes = s.remMinutes, awakeMinutes = s.awakeMinutes,
                    segments = segs, wakeCount = wakeCount,
                    heartRate = hrSamples, hrIntervalSeconds = det?.heartRate?.unit ?: 60,
                    spo2 = spo2Samples, spo2IntervalSeconds = det?.spo2?.unit ?: 60,
                    rr = rrSegs, isNap = isNap,
                )
                sleepsByDay.getOrPut(n.day) { mutableListOf() } += input
            }

            // Merge Health Connect snapshots (deduplicated: direct BLE takes priority on days that have it).
            val externalHrvByDay = HashMap<LocalDate, Double>()
            val sourceByDay = HashMap<LocalDate, String>()
            for (d in directBleDays) sourceByDay[d] = "ble:xiaomi"

            for ((d, hc) in hcDays) {
                hc.hrvRmssdMs?.let { externalHrvByDay[d] = it }
                val hasDirectBle = d in directBleDays
                if (!hasDirectBle && !hc.primarySource.isNullOrBlank()) {
                    sourceByDay[d] = "hc:${hc.primarySource}"
                }

                if (!hasDirectBle) {
                    val hrVals = hc.heartRateSamples.map { it.second }
                    val syntheticSummary = DailySummary(
                        version = 1,
                        day = d.toEpochDay(),
                        tzQuarterHours = hc.tzOffsetMinutes / 15,
                        bitmap = ByteArray(0),
                        slots = listOf(
                            hc.steps,                                        // 0 steps
                            hc.activeCalories,                               // 1 activeCalories
                            null,                                            // 2
                            hc.restingHr,                                    // 3 hrResting
                            hrVals.maxOrNull(),                              // 4 hrMax
                            null,                                            // 5
                            hrVals.minOrNull(),                              // 6 hrMin
                            null,                                            // 7
                            hrVals.takeIf { it.isNotEmpty() }?.average()?.roundToInt(), // 8 hrAvg
                        ),
                        trailingBytes = 0,
                    )
                    if (hc.steps != null || hc.activeCalories != null || hc.restingHr != null || hrVals.isNotEmpty()) {
                        summaries[d] = syntheticSummary
                    }
                    if (hc.heartRateSamples.isNotEmpty()) {
                        heart[d] = hc.heartRateSamples.toMutableList()
                    }
                    if (hc.steps != null && hc.steps > 0) {
                        steps[d] = hc.steps
                    }
                    if (hc.heartRateSamples.isNotEmpty() || hc.minuteSteps.isNotEmpty() || hc.spo2Samples.isNotEmpty()) {
                        minuteSeries[d] = hc.toMinuteSeries()
                    }
                } else if (summaries[d]?.hrResting == null && hc.restingHr != null) {
                    // Fill missing resting HR without duplicating steps or HR samples.
                    val cur = summaries[d]
                    if (cur != null) {
                        val slots = cur.slots.toMutableList()
                        while (slots.size <= 3) slots += null
                        slots[3] = hc.restingHr
                        summaries[d] = cur.copy(slots = slots)
                    }
                }

                // Import Health Connect sleep sessions if direct BLE did not record sleep for this day.
                if (sleepsByDay[d].isNullOrEmpty() && hc.sleeps.isNotEmpty()) {
                    val prevDay = hcDays[d.minusDays(1)]
                    val hrTimed = (prevDay?.heartRateSamples.orEmpty() + hc.heartRateSamples).map { TimedValue(it.first, it.second) }
                    val spo2Timed = (prevDay?.spo2Samples.orEmpty() + hc.spo2Samples).map { TimedValue(it.first, it.second) }
                    for (sl in hc.sleeps) {
                        val inp = sl.toSleepInput(hrTimed, spo2Timed)
                        sleepsByDay.getOrPut(d) { mutableListOf() } += inp
                        val samples = sl.stages.map { st ->
                            SleepStageSample(st.startSec, sleepStage(st.stage), 0, st.minutes)
                        }
                        nightsList += Night(
                            d,
                            SleepSession(
                                bedTime = sl.bedTimeSec,
                                wakeTime = sl.wakeTimeSec,
                                deepMinutes = inp.deepMinutes,
                                lightMinutes = inp.lightMinutes,
                                remMinutes = inp.remMinutes,
                                awakeMinutes = inp.awakeMinutes,
                                stages = samples,
                            )
                        )
                    }
                }
            }

            val allDays = (summaries.keys + heart.keys + steps.keys + sleepsByDay.keys + externalHrvByDay.keys).sorted()
            val todayLocal = LocalDate.now()
            val nowSec = System.currentTimeMillis() / 1000L
            val dayInputs = allDays.map { d ->
                val prev = d.minusDays(1)
                DayInputs(
                    dayIndex = d.toEpochDay(),
                    minutes = minuteSeries[d],
                    previousMinutes = minuteSeries[prev],
                    sleeps = sleepsByDay[d] ?: emptyList(),
                    previousSleeps = sleepsByDay[prev] ?: emptyList(),
                    bandRestingHR = summaries[d]?.hrResting ?: hcDays[d]?.restingHr,
                    nowSec = if (d == todayLocal) nowSec else null,
                    externalHrvMs = externalHrvByDay[d],
                    hrvMetricKind = HrvMetricKind.RMSSD,
                    dataSourceId = sourceByDay[d],
                )
            }
            val scored = ScoreEngine.scoreRange(dayInputs, profile = profile).associateBy { LocalDate.ofEpochDay(it.dayIndex) }

            val days = allDays.associateWith { d ->
                DayHealth(d, summaries[d], heart[d]?.sortedBy { it.first } ?: emptyList(), steps[d] ?: 0, scored[d])
            }
            return HealthHistory(days, nightsList.sortedBy { it.session.bedTime })
        }
    }
}
