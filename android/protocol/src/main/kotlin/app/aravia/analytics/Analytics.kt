package app.aravia.analytics

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure JVM port of ios/Freeband/Analytics/ (MIT): Profile, Baseline, SleepMetrics, SleepScore,
 * HRV, RestingHR, Recovery, Strain and ScoreEngine (algorithm v3). No Android or I/O imports, so
 * it is unit-tested in :protocol:test.
 */

// Profile

data class Profile(
    val age: Int? = null,
    val sex: Sex = Sex.UNKNOWN,
    val heightCm: Double? = null,
    val weightKg: Double? = null,
    val maxHROverride: Int? = null,
    val sleepNeedMinutes: Int? = null,
) {
    enum class Sex(val raw: String, val labelPt: String) {
        MALE("male", "Masculino"),
        FEMALE("female", "Feminino"),
        UNKNOWN("unknown", "Prefiro não dizer");

        companion object {
            fun of(raw: String?) = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
        }
    }

    val maxHR: Double
        get() = if (maxHROverride != null && maxHROverride > 100) maxHROverride.toDouble()
        else 208.0 - 0.7 * (age ?: ASSUMED_AGE)

    val maxHRIsKnown: Boolean get() = (maxHROverride ?: 0) > 100 || age != null

    val trimpK: Double
        get() = when (sex) {
            Sex.MALE -> 1.92
            Sex.FEMALE -> 1.67
            Sex.UNKNOWN -> 1.80
        }

    val sleepNeed: Int get() = sleepNeedMinutes?.takeIf { it >= 240 } ?: DEFAULT_SLEEP_NEED_MINUTES

    companion object {
        const val ASSUMED_AGE = 35
        const val DEFAULT_SLEEP_NEED_MINUTES = 480
        val UNKNOWN = Profile()
    }
}

// Statistics & ranges

object Stats {
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.sum() / values.size

    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val rank = ceil(p * s.size).toInt()
        return s[min(max(rank - 1, 0), s.lastIndex)]
    }

    fun ramp(x: Double, from: Double, to: Double): Double {
        if (to == from) return if (x >= to) 100.0 else 0.0
        return clamp((x - from) / (to - from) * 100.0, 0.0, 100.0)
    }

    fun clamp(x: Double, lo: Double, hi: Double): Double = min(max(x, lo), hi)
}

object ValidRange {
    val heartRate = 30..220
    val spo2 = 70..100
    val stress = 1..100

    fun hr(v: Int?): Int? = v?.takeIf { it in heartRate }
    fun spo2(v: Int?): Int? = v?.takeIf { it in spo2 }
    fun stress(v: Int?): Int? = v?.takeIf { it in stress }
}

// Confidence

enum class ConfidenceLevel(val rank: Int, val labelPt: String) {
    NONE(0, "Sem dados"),
    LOW(1, "Baixa"),
    MEDIUM(2, "Média"),
    HIGH(3, "Alta");

    fun minWith(other: ConfidenceLevel) = if (rank <= other.rank) this else other
}

enum class HrvMetricKind(val raw: String, val labelPt: String) {
    RMSSD("rmssd", "RMSSD"),
    SDNN("sdnn", "SDNN"),
}

data class ConfidenceReason(val code: Code, val a: Double? = null, val b: Double? = null, val detail: String? = null) {
    enum class Code {
        BASELINE_CALIBRATING, BASELINE_SHORT, NO_RR, HRV_TOO_FEW_WINDOWS, SPARSE_HEART_RATE,
        LOW_HEART_RATE_COVERAGE, RESTING_HR_FROM_BAND, RESTING_HR_FROM_DAYTIME, RESTING_HR_ASSUMED,
        AGE_UNKNOWN, NO_SLEEP, NO_STAGES, MISSING_CONTRIBUTOR, ESTIMATED, NO_HEART_RATE, NO_DATA,
        HRV_APPROXIMATE, HRV_EXTERNAL, SOURCE_CHANGED,
    }
}

data class Confidence(var level: ConfidenceLevel = ConfidenceLevel.HIGH, val reasons: MutableList<ConfidenceReason> = mutableListOf()) {
    fun cap(maxLevel: ConfidenceLevel, reason: ConfidenceReason) {
        level = level.minWith(maxLevel)
        if (reason !in reasons) reasons += reason
    }

    fun note(reason: ConfidenceReason) {
        if (reason !in reasons) reasons += reason
    }

    companion object {
        fun none(vararg reasons: ConfidenceReason) = Confidence(ConfidenceLevel.NONE, reasons.toMutableList())
    }
}

// Baseline

data class Baseline(val median: Double, val mad: Double, val sigma: Double, val count: Int) {
    enum class Status { CALIBRATING, LOW, NORMAL }

    data class Floor(val absolute: Double, val relative: Double) {
        fun apply(sigma: Double, median: Double): Double = maxOf(sigma, absolute, relative * abs(median))

        companion object {
            val RESTING_HR = Floor(1.5, 0.03)
            val HRV = Floor(3.0, 0.08)
            val SLEEP_SCORE = Floor(5.0, 0.0)
            val STRAIN = Floor(1.5, 0.0)
        }
    }

    val status: Status get() = when {
        count < 3 -> Status.CALIBRATING
        count < 7 -> Status.LOW
        else -> Status.NORMAL
    }

    fun z(value: Double): Double = (value - median) / sigma

    companion object {
        const val WINDOW = 14
        const val LOOKBACK_DAYS = 60

        fun compute(values: List<Double>, floor: Floor): Baseline? {
            val used = values.filter { it.isFinite() }.take(WINDOW)
            val median = Stats.median(used) ?: return null
            val mad = Stats.median(used.map { abs(it - median) }) ?: 0.0
            val sigma = floor.apply(1.4826 * mad, median)
            return Baseline(median, mad, max(sigma, 1e-9), used.size)
        }
    }
}

// Time series inputs (Unix seconds everywhere)

enum class StageKind {
    AWAKE, LIGHT, DEEP, REM, NOT_SLEEP, UNKNOWN;
    val isSleep: Boolean get() = this == LIGHT || this == DEEP || this == REM
}

data class StageSegment(val startSec: Long, val minutes: Int, val stage: StageKind) {
    val endSec: Long get() = startSec + minutes * 60L
}

data class TimedValue(val tsSec: Long, val value: Int)
data class RRSegment(val tsRaw: Long, val intervalsMs: List<Int>)

class MinuteSeries(val dayIndex: Long, val tzOffsetMinutes: Int) {
    val steps = arrayOfNulls<Int>(SLOTS)
    val heartRate = arrayOfNulls<Int>(SLOTS)
    val spo2 = arrayOfNulls<Int>(SLOTS)
    val stress = arrayOfNulls<Int>(SLOTS)
    val kcal = arrayOfNulls<Int>(SLOTS)
    val distance = arrayOfNulls<Int>(SLOTS)

    /** Local midnight as Unix seconds. */
    val startSec: Long get() = dayIndex * 86_400L - tzOffsetMinutes * 60L
    fun timeSec(minute: Int): Long = startSec + minute * 60L

    fun hasRecord(i: Int): Boolean =
        steps[i] != null || heartRate[i] != null || kcal[i] != null || distance[i] != null || stress[i] != null || spo2[i] != null

    companion object {
        const val SLOTS = 1440
    }
}

data class SleepInput(
    val bedTimeSec: Long,
    val wakeTimeSec: Long,
    val tzOffsetMinutes: Int = 0,
    val deepMinutes: Int? = null,
    val lightMinutes: Int? = null,
    val remMinutes: Int? = null,
    val awakeMinutes: Int? = null,
    val segments: List<StageSegment> = emptyList(),
    val wakeCount: Int? = null,
    val heartRate: List<TimedValue> = emptyList(),
    val hrIntervalSeconds: Int = 60,
    val spo2: List<TimedValue> = emptyList(),
    val spo2IntervalSeconds: Int = 60,
    val rr: List<RRSegment> = emptyList(),
    val isNap: Boolean = false,
) {
    fun contains(sec: Long): Boolean = sec >= bedTimeSec && sec < wakeTimeSec
    val inBedMinutes: Double get() = max(wakeTimeSec - bedTimeSec, 0L) / 60.0
}

// Sleep metrics, stress & SpO2

data class SleepMetrics(
    val bedTimeSec: Long,
    val wakeTimeSec: Long,
    val inBedMinutes: Double,
    val totalSleepMinutes: Double,
    val stagesAvailable: Boolean,
    val efficiency: Double?,
    val deepMinutes: Double?,
    val lightMinutes: Double?,
    val remMinutes: Double?,
    val awakeMinutes: Double?,
    val deepFraction: Double?,
    val remFraction: Double?,
    val wakeCount: Int?,
    val wasoMinutes: Double?,
    val midSleepSec: Long,
    val midSleepMinute: Double,
    val hrAverage: Double?,
    val hrMinimum: Double?,
    val hrLowest5Min: Double?,
    val awakeHRMean: Double?,
    val hrDip: Double?,
    val spo2Average: Double?,
    val spo2Minimum: Double?,
    val spo2Below90Minutes: Double?,
)

object SleepMetricsCalc {
    const val MAX_SAMPLE_SPAN_SEC = 600
    const val AWAKE_WINDOW_HOURS = 14.0
    const val MIN_AWAKE_HR_SAMPLES = 20

    fun compute(s: SleepInput, minutes: List<MinuteSeries>, awakeHRMean: Double?): SleepMetrics {
        val inBed = s.inBedMinutes
        var deep = s.deepMinutes?.toDouble()
        var light = s.lightMinutes?.toDouble()
        var rem = s.remMinutes?.toDouble()
        var awake = s.awakeMinutes?.toDouble()
        if (deep == null && light == null && rem == null && s.segments.any { it.stage.isSleep }) {
            fun total(k: StageKind) = s.segments.filter { it.stage == k }.sumOf { it.minutes }.toDouble()
            deep = total(StageKind.DEEP); light = total(StageKind.LIGHT); rem = total(StageKind.REM); awake = total(StageKind.AWAKE)
        }
        val staged = deep != null || light != null || rem != null
        val total = if (staged) (deep ?: 0.0) + (light ?: 0.0) + (rem ?: 0.0) else max(inBed - (awake ?: 0.0), 0.0)
        val stagesAvailable = staged && deep != null && rem != null && total > 0 && ((deep) + (rem)) > 0

        var waso: Double? = null
        var wakeCount = s.wakeCount
        val sleepSegs = s.segments.filter { it.stage.isSleep }
        val firstSleep = sleepSegs.minOfOrNull { it.startSec }
        val lastSleep = sleepSegs.maxOfOrNull { it.endSec }
        if (firstSleep != null && lastSleep != null) {
            val inside = s.segments.filter { it.stage == StageKind.AWAKE && it.startSec >= firstSleep && it.endSec <= lastSleep }
            waso = inside.sumOf { it.minutes }.toDouble()
            if (wakeCount == null) {
                var runs = 0; var inRun = false
                for (seg in s.segments.sortedBy { it.startSec }) {
                    if (seg.startSec < firstSleep || seg.endSec > lastSleep) continue
                    if (seg.stage == StageKind.AWAKE) { if (!inRun) runs++; inRun = true }
                    else if (seg.stage.isSleep) inRun = false
                }
                wakeCount = runs
            }
        } else if (awake != null) {
            waso = awake
        }

        val midSec = s.bedTimeSec + (s.wakeTimeSec - s.bedTimeSec) / 2L
        val localSec = midSec.toDouble() + s.tzOffsetMinutes * 60.0
        val midMinute = ((localSec % 86_400.0) + 86_400.0) % 86_400.0 / 60.0

        val hr = heartRatePerMinute(s, minutes)
        val hrValues = hr.filterNotNull()
        val hrAvg = Stats.mean(hrValues)
        val dip = if (hrAvg != null && awakeHRMean != null && awakeHRMean > 0) (awakeHRMean - hrAvg) / awakeHRMean else null

        val spo2 = spo2Samples(s, minutes)
        val spo2Weight = spo2.sumOf { it.second }
        val spo2Avg = if (spo2Weight > 0) spo2.sumOf { it.first * it.second } / spo2Weight else null

        return SleepMetrics(
            bedTimeSec = s.bedTimeSec, wakeTimeSec = s.wakeTimeSec, inBedMinutes = inBed, totalSleepMinutes = total,
            stagesAvailable = stagesAvailable,
            efficiency = if (inBed > 0) min(total / inBed, 1.0) else null,
            deepMinutes = deep, lightMinutes = light, remMinutes = rem, awakeMinutes = awake,
            deepFraction = if (stagesAvailable) (deep ?: 0.0) / total else null,
            remFraction = if (stagesAvailable) (rem ?: 0.0) / total else null,
            wakeCount = wakeCount, wasoMinutes = waso, midSleepSec = midSec, midSleepMinute = midMinute,
            hrAverage = hrAvg, hrMinimum = hrValues.minOrNull(), hrLowest5Min = lowestRollingAverage(hr),
            awakeHRMean = awakeHRMean, hrDip = dip,
            spo2Average = spo2Avg, spo2Minimum = spo2.minOfOrNull { it.first.toDouble() },
            spo2Below90Minutes = if (spo2.isEmpty()) null else spo2.filter { it.first < 90 }.sumOf { it.second },
        )
    }

    fun heartRatePerMinute(s: SleepInput, minutes: List<MinuteSeries>): List<Double?> {
        val count = ceil(s.inBedMinutes).toInt()
        if (count <= 0) return emptyList()
        val sum = DoubleArray(count)
        val n = IntArray(count)
        val samples = s.heartRate.filter { it.value in ValidRange.heartRate }
        if (samples.isNotEmpty()) {
            val span = min(max(s.hrIntervalSeconds, 1), MAX_SAMPLE_SPAN_SEC)
            for (sample in samples) {
                val first = floor((sample.tsSec - s.bedTimeSec) / 60.0).toInt()
                val last = floor(((sample.tsSec - s.bedTimeSec) + span - 1) / 60.0).toInt()
                if (last < 0 || first >= count) continue
                for (m in max(first, 0)..min(last, count - 1)) {
                    sum[m] += sample.value.toDouble()
                    n[m]++
                }
            }
        } else {
            for (series in minutes) {
                for (i in 0 until MinuteSeries.SLOTS) {
                    val v = ValidRange.hr(series.heartRate[i]) ?: continue
                    val t = series.timeSec(i)
                    if (!s.contains(t)) continue
                    val m = ((t - s.bedTimeSec) / 60L).toInt()
                    if (m in 0 until count) { sum[m] += v.toDouble(); n[m]++ }
                }
            }
        }
        return List(count) { i -> if (n[i] > 0) sum[i] / n[i] else null }
    }

    fun lowestRollingAverage(perMinute: List<Double?>, window: Int = 5, minValid: Int = 3): Double? {
        if (perMinute.size < window) {
            val v = perMinute.filterNotNull()
            return if (v.size >= minValid) Stats.mean(v) else null
        }
        var best: Double? = null
        for (start in 0..(perMinute.size - window)) {
            val v = perMinute.subList(start, start + window).filterNotNull()
            if (v.size < minValid) continue
            val m = Stats.mean(v) ?: continue
            if (best == null || m < best) best = m
        }
        return best
    }

    fun spo2Samples(s: SleepInput, minutes: List<MinuteSeries>): List<Pair<Int, Double>> {
        val own = s.spo2.filter { it.value in ValidRange.spo2 && s.contains(it.tsSec) }
        if (own.isNotEmpty()) {
            val spanMin = min(max(s.spo2IntervalSeconds, 1), MAX_SAMPLE_SPAN_SEC) / 60.0
            return own.map { it.value to spanMin }
        }
        val out = mutableListOf<Pair<Int, Double>>()
        for (series in minutes) {
            for (i in 0 until MinuteSeries.SLOTS) {
                val v = ValidRange.spo2(series.spo2[i]) ?: continue
                if (s.contains(series.timeSec(i))) out += v to 1.0
            }
        }
        return out
    }

    fun awakeHRMean(beforeSec: Long, minutes: List<MinuteSeries>, sleeps: List<SleepInput>): Double? {
        val fromSec = beforeSec - (AWAKE_WINDOW_HOURS * 3600).toLong()
        val values = mutableListOf<Double>()
        for (series in minutes) {
            for (i in 0 until MinuteSeries.SLOTS) {
                val v = ValidRange.hr(series.heartRate[i]) ?: continue
                val t = series.timeSec(i)
                if (t < fromSec || t >= beforeSec) continue
                if (sleeps.any { it.contains(t) }) continue
                values += v.toDouble()
            }
        }
        return if (values.size >= MIN_AWAKE_HR_SAMPLES) Stats.mean(values) else null
    }
}

data class StressSummary(val average: Double, val minutes: Double, val bandMinutes: List<Double>) {
    companion object {
        private val BOUNDS = listOf(1, 40, 60, 80)
        const val MAX_HOLD_MINUTES = 30

        fun band(value: Int): Int = BOUNDS.indexOfLast { value >= it }.coerceAtLeast(0)

        fun compute(minutes: MinuteSeries): StressSummary? {
            val readings = (0 until MinuteSeries.SLOTS).mapNotNull { i -> ValidRange.stress(minutes.stress[i])?.let { i to it } }
            if (readings.isEmpty()) return null
            var weighted = 0.0; var total = 0.0
            val bands = DoubleArray(4)
            for (n in readings.indices) {
                val (idx, v) = readings[n]
                val next = if (n + 1 < readings.size) readings[n + 1].first else MinuteSeries.SLOTS
                val hold = min(next - idx, MAX_HOLD_MINUTES).toDouble()
                weighted += v * hold
                total += hold
                bands[band(v)] += hold
            }
            return StressSummary(weighted / total, total, bands.toList())
        }
    }
}

data class SpO2Summary(val average: Double, val minimum: Double, val below90Minutes: Double)

// HRV

data class HRVWindow(
    val startSec: Double, val seconds: Double, val beats: Int, val validDifferences: Int,
    val rmssd: Double?, val secondHalf: Boolean, val usable: Boolean,
)

data class HRVResult(val rmssd: Double, val secondHalfWindows: Int, val totalBeats: Int, val approximate: Boolean)

object HRV {
    val rrRange = 300..2000
    const val MAX_JUMP = 0.20
    const val WINDOW_SECONDS = 300.0
    const val MIN_WINDOW_SECONDS = 240.0
    const val MIN_VALID_FRACTION = 0.5
    const val MIN_DIFFERENCES = 20
    const val MIN_SECOND_HALF_WINDOWS = 3

    fun validDifferences(rr: List<Int>): List<Int> {
        if (rr.size < 2) return emptyList()
        val out = mutableListOf<Int>()
        for (i in 1 until rr.size) {
            val a = rr[i - 1]; val b = rr[i]
            if (a !in rrRange || b !in rrRange) continue
            val d = b - a
            if (abs(d).toDouble() <= MAX_JUMP * a) out += d
        }
        return out
    }

    fun rmssdFromDifferences(d: List<Int>): Double? {
        if (d.isEmpty()) return null
        val sum = d.sumOf { it.toDouble() * it.toDouble() }
        return sqrt(sum / d.size)
    }

    fun resolveStartSec(tsRaw: Long, bedTimeSec: Long, wakeTimeSec: Long): Double? {
        val lo = bedTimeSec - 3600.0; val hi = wakeTimeSec + 3600.0
        for (div in doubleArrayOf(1.0, 1000.0, 1_000_000_000.0)) {
            val t = tsRaw / div
            if (t in lo..hi) return t
        }
        return null
    }

    fun windows(segments: List<RRSegment>, bedTimeSec: Long, wakeTimeSec: Long): List<HRVWindow> {
        val middle = bedTimeSec + (wakeTimeSec - bedTimeSec) / 2.0
        val out = mutableListOf<HRVWindow>()
        var cursor = bedTimeSec.toDouble()
        for (seg in segments) {
            val start = resolveStartSec(seg.tsRaw, bedTimeSec, wakeTimeSec) ?: cursor
            var windowStart = 0.0
            var elapsed = 0.0
            val beats = mutableListOf<Int>()
            fun close(final: Boolean) {
                val seconds = elapsed - windowStart
                if (beats.isNotEmpty() && (!final || seconds >= MIN_WINDOW_SECONDS)) {
                    val diffs = validDifferences(beats)
                    val possible = max(beats.size - 1, 1)
                    val usable = seconds >= MIN_WINDOW_SECONDS && diffs.size >= MIN_DIFFERENCES &&
                        diffs.size >= MIN_VALID_FRACTION * possible
                    val begin = start + windowStart
                    out += HRVWindow(begin, seconds, beats.size, diffs.size, rmssdFromDifferences(diffs),
                        begin + seconds / 2.0 >= middle, usable)
                }
                windowStart = elapsed
                beats.clear()
            }
            for (rr in seg.intervalsMs) {
                beats += rr
                elapsed += min(max(rr, 0), rrRange.last) / 1000.0
                if (elapsed - windowStart >= WINDOW_SECONDS) close(false)
            }
            close(true)
            cursor = start + elapsed
        }
        return out
    }

    fun nightly(segments: List<RRSegment>, bedTimeSec: Long, wakeTimeSec: Long): Pair<HRVResult?, Int> {
        val late = windows(segments, bedTimeSec, wakeTimeSec).filter { it.usable && it.secondHalf }.mapNotNull { it.rmssd }
        if (late.size < MIN_SECOND_HALF_WINDOWS) return null to late.size
        val median = Stats.median(late) ?: return null to late.size
        val beats = segments.sumOf { it.intervalsMs.size }
        return HRVResult(median, late.size, beats, true) to late.size
    }
}

// Sleep score (0-100)

data class SleepContributor(
    val name: Name, val weight: Double, val value: Double?, val subscore: Double?, val baseline: Double?,
) {
    enum class Name(val raw: String, val labelPt: String) {
        DURATION("duration", "Duração"),
        EFFICIENCY("efficiency", "Eficiência"),
        DEEP("deep", "Sono profundo"),
        REM("rem", "REM"),
        RESTFULNESS("restfulness", "Descanso"),
        REGULARITY("regularity", "Regularidade"),
        HR_DIP("hr_dip", "Queda de FC"),
    }
}

data class SleepScoreResult(
    val score: Int?,
    val estimated: Boolean,
    val availableWeight: Double,
    val contributors: List<SleepContributor>,
    val metrics: SleepMetrics?,
    val confidence: Confidence,
) {
    companion object {
        val MISSING = SleepScoreResult(null, false, 0.0, emptyList(), null,
            Confidence.none(ConfidenceReason(ConfidenceReason.Code.NO_SLEEP)))
    }
}

object SleepScore {
    val weights = mapOf(
        SleepContributor.Name.DURATION to 35.0,
        SleepContributor.Name.EFFICIENCY to 15.0,
        SleepContributor.Name.DEEP to 10.0,
        SleepContributor.Name.REM to 10.0,
        SleepContributor.Name.RESTFULNESS to 10.0,
        SleepContributor.Name.REGULARITY to 10.0,
        SleepContributor.Name.HR_DIP to 10.0,
    )
    const val ESTIMATE_BELOW_WEIGHT = 60.0
    const val DURATION_LEAD = 10.0
    const val EFFICIENCY_ZERO = 0.60
    const val EFFICIENCY_FULL = 0.90
    const val DEEP_TARGET = 0.18
    const val REM_TARGET = 0.22
    const val PER_WAKING = 4.0
    const val PER_WASO_MINUTE = 1.0
    const val REGULARITY_FULL = 15.0
    const val REGULARITY_ZERO = 120.0
    const val REGULARITY_NIGHTS = 7
    const val REGULARITY_MIN_NIGHTS = 3
    const val DIP_FULL = 0.15

    fun score(
        metrics: SleepMetrics?,
        sleepNeedMinutes: Int,
        previousMidSleeps: List<Double>,
        baselines: Map<SleepContributor.Name, Double> = emptyMap(),
    ): SleepScoreResult {
        val m = metrics ?: return SleepScoreResult.MISSING
        if (m.inBedMinutes <= 0) return SleepScoreResult.MISSING

        fun row(name: SleepContributor.Name, value: Double?, sub: Double?) = SleepContributor(
            name, weights[name] ?: 0.0, value, sub?.let { Stats.clamp(it, 0.0, 100.0) }, baselines[name],
        )

        val rows = mutableListOf<SleepContributor>()
        rows += row(SleepContributor.Name.DURATION, m.totalSleepMinutes, Stats.ramp(m.totalSleepMinutes, 0.0, max(sleepNeedMinutes, 1).toDouble()))
        rows += row(SleepContributor.Name.EFFICIENCY, m.efficiency, m.efficiency?.let { Stats.ramp(it, EFFICIENCY_ZERO, EFFICIENCY_FULL) })
        rows += row(SleepContributor.Name.DEEP, m.deepFraction, m.deepFraction?.let { Stats.ramp(it, 0.0, DEEP_TARGET) })
        rows += row(SleepContributor.Name.REM, m.remFraction, m.remFraction?.let { Stats.ramp(it, 0.0, REM_TARGET) })
        val restful = if (m.wakeCount != null || m.wasoMinutes != null) {
            100.0 - PER_WAKING * (m.wakeCount ?: 0) - PER_WASO_MINUTE * (m.wasoMinutes ?: 0.0)
        } else null
        rows += row(SleepContributor.Name.RESTFULNESS, m.wasoMinutes, restful)
        val dev = regularityDeviation(m.midSleepMinute, previousMidSleeps)
        rows += row(SleepContributor.Name.REGULARITY, dev, dev?.let { Stats.ramp(it, REGULARITY_ZERO, REGULARITY_FULL) })
        rows += row(SleepContributor.Name.HR_DIP, m.hrDip, m.hrDip?.let { Stats.ramp(it, 0.0, DIP_FULL) })

        val available = rows.filter { it.subscore != null }
        val weight = available.sumOf { it.weight }
        var score = if (weight > 0) available.sumOf { it.weight * (it.subscore ?: 0.0) } / weight else null
        val durSub = rows.firstOrNull { it.name == SleepContributor.Name.DURATION }?.subscore
        if (score != null && durSub != null) score = min(score, durSub + DURATION_LEAD)
        val estimated = weight < ESTIMATE_BELOW_WEIGHT

        val conf = Confidence(ConfidenceLevel.HIGH)
        if (!m.stagesAvailable) conf.cap(ConfidenceLevel.MEDIUM, ConfidenceReason(ConfidenceReason.Code.NO_STAGES))
        for (r in rows) if (r.subscore == null) {
            conf.cap(ConfidenceLevel.MEDIUM, ConfidenceReason(ConfidenceReason.Code.MISSING_CONTRIBUTOR, detail = r.name.raw))
        }
        if (estimated) conf.cap(ConfidenceLevel.LOW, ConfidenceReason(ConfidenceReason.Code.ESTIMATED, weight))
        return SleepScoreResult(score?.roundToInt(), estimated, weight, rows, m, conf)
    }

    fun regularityDeviation(mid: Double, previous: List<Double>): Double? {
        val nights = previous.take(REGULARITY_NIGHTS)
        if (nights.size < REGULARITY_MIN_NIGHTS) return null
        val diffs = nights.map { p ->
            var d = (mid - p) % 1440.0
            if (d > 720.0) d -= 1440.0
            if (d <= -720.0) d += 1440.0
            d
        }
        return Stats.median(diffs)?.let { abs(it) }
    }
}

// Resting HR & Recovery (0-100)

data class RestingHR(val value: Double, val source: Source) {
    enum class Source { SLEEP, BAND, DAYTIME }

    companion object {
        const val MIN_DAYTIME_MINUTES = 30
        const val DAYTIME_PERCENTILE = 0.10

        fun compute(sleepLowest5Min: Double?, bandValue: Int?, minutes: MinuteSeries?, sleeps: List<SleepInput>): RestingHR? {
            if (sleepLowest5Min != null && sleepLowest5Min.roundToInt() in ValidRange.heartRate) {
                return RestingHR(sleepLowest5Min, Source.SLEEP)
            }
            ValidRange.hr(bandValue)?.let { return RestingHR(it.toDouble(), Source.BAND) }
            val m = minutes ?: return null
            val still = mutableListOf<Double>()
            for (i in 0 until MinuteSeries.SLOTS) {
                if (m.steps[i] != 0) continue
                val hr = ValidRange.hr(m.heartRate[i]) ?: continue
                val t = m.timeSec(i)
                if (sleeps.any { it.contains(t) }) continue
                still += hr.toDouble()
            }
            if (still.size < MIN_DAYTIME_MINUTES) return null
            return Stats.percentile(still, DAYTIME_PERCENTILE)?.let { RestingHR(it, Source.DAYTIME) }
        }
    }
}

data class RecoveryContributor(
    val name: Name, val weight: Double, val today: Double?, val baseline: Baseline?, val z: Double?, val subscore: Double?,
) {
    enum class Name(val raw: String, val labelPt: String) {
        HRV("hrv", "HRV"),
        RESTING_HR("resting_hr", "FC de repouso"),
        SLEEP("sleep", "Sono"),
        STRAIN("strain", "Esforço anterior"),
    }
}

data class RecoveryResult(
    val score: Int?,
    val usesHRV: Boolean,
    val contributors: List<RecoveryContributor>,
    val confidence: Confidence,
)

object Recovery {
    val withHRV = mapOf(
        RecoveryContributor.Name.HRV to 40.0, RecoveryContributor.Name.RESTING_HR to 25.0,
        RecoveryContributor.Name.SLEEP to 25.0, RecoveryContributor.Name.STRAIN to 10.0,
    )
    val withoutHRV = mapOf(
        RecoveryContributor.Name.RESTING_HR to 45.0, RecoveryContributor.Name.SLEEP to 40.0,
        RecoveryContributor.Name.STRAIN to 15.0,
    )

    fun f(z: Double): Double = Stats.clamp(60.0 + 20.0 * z, 0.0, 100.0)

    data class Inputs(
        val hrv: Double? = null,
        val hrvHistory: List<Double> = emptyList(),
        val hasRR: Boolean = false,
        val hrvWindows: Int = 0,
        val restingHR: RestingHR? = null,
        val restingHRHistory: List<Double> = emptyList(),
        val sleepScore: Double? = null,
        val sleepHistory: List<Double> = emptyList(),
        val strainYesterday: Double? = null,
        val strainHistory: List<Double> = emptyList(),
    )

    private data class Part(val name: RecoveryContributor.Name, val today: Double?, val baseline: Baseline?, val sign: Double) {
        val usable: Boolean get() = today != null && baseline != null && baseline.status != Baseline.Status.CALIBRATING
        val z: Double? get() = if (usable && today != null && baseline != null) sign * baseline.z(today) else null
    }

    fun score(input: Inputs): RecoveryResult {
        val parts = listOf(
            Part(RecoveryContributor.Name.HRV, input.hrv, Baseline.compute(input.hrvHistory, Baseline.Floor.HRV), 1.0),
            Part(RecoveryContributor.Name.RESTING_HR, input.restingHR?.value, Baseline.compute(input.restingHRHistory, Baseline.Floor.RESTING_HR), -1.0),
            Part(RecoveryContributor.Name.SLEEP, input.sleepScore, Baseline.compute(input.sleepHistory, Baseline.Floor.SLEEP_SCORE), 1.0),
            Part(RecoveryContributor.Name.STRAIN, input.strainYesterday, Baseline.compute(input.strainHistory, Baseline.Floor.STRAIN), -1.0),
        )
        val usesHRV = parts[0].usable
        val weights = if (usesHRV) withHRV else withoutHRV
        val rows = parts.map { p ->
            val w = weights[p.name] ?: 0.0
            RecoveryContributor(p.name, w, p.today, p.baseline, p.z, if (w > 0) p.z?.let { f(it) } else null)
        }

        val conf = Confidence(ConfidenceLevel.HIGH)
        if (!usesHRV) {
            when {
                !input.hasRR -> conf.cap(ConfidenceLevel.MEDIUM, ConfidenceReason(ConfidenceReason.Code.NO_RR))
                input.hrv == null -> conf.cap(ConfidenceLevel.MEDIUM, ConfidenceReason(ConfidenceReason.Code.HRV_TOO_FEW_WINDOWS, input.hrvWindows.toDouble(), HRV.MIN_SECOND_HALF_WINDOWS.toDouble()))
                else -> conf.cap(ConfidenceLevel.MEDIUM, ConfidenceReason(ConfidenceReason.Code.BASELINE_CALIBRATING, (parts[0].baseline?.count ?: 0).toDouble(), Baseline.WINDOW.toDouble(), RecoveryContributor.Name.HRV.raw))
            }
        } else {
            conf.note(ConfidenceReason(ConfidenceReason.Code.HRV_APPROXIMATE))
        }

        if (!parts[0].usable && !parts[1].usable) {
            val none = Confidence(ConfidenceLevel.NONE, conf.reasons.toMutableList())
            for (p in parts.take(2)) if (p.today != null && !p.usable) {
                none.note(ConfidenceReason(ConfidenceReason.Code.BASELINE_CALIBRATING, (p.baseline?.count ?: 0).toDouble(), Baseline.WINDOW.toDouble(), p.name.raw))
            }
            if (input.restingHR == null && input.hrv == null) none.note(ConfidenceReason(ConfidenceReason.Code.NO_HEART_RATE))
            return RecoveryResult(null, false, rows, none)
        }

        for ((p, row) in parts.zip(rows)) {
            if (row.weight <= 0) continue
            if (p.usable && p.baseline?.status == Baseline.Status.LOW) {
                conf.cap(ConfidenceLevel.LOW, ConfidenceReason(ConfidenceReason.Code.BASELINE_SHORT, p.baseline.count.toDouble(), Baseline.WINDOW.toDouble(), p.name.raw))
            } else if (!p.usable) {
                if (p.today != null) conf.cap(ConfidenceLevel.MEDIUM, ConfidenceReason(ConfidenceReason.Code.BASELINE_CALIBRATING, (p.baseline?.count ?: 0).toDouble(), Baseline.WINDOW.toDouble(), p.name.raw))
                else conf.cap(ConfidenceLevel.MEDIUM, ConfidenceReason(ConfidenceReason.Code.MISSING_CONTRIBUTOR, detail = p.name.raw))
            }
        }
        if (parts[1].usable && input.restingHR != null) {
            when (input.restingHR.source) {
                RestingHR.Source.SLEEP -> {}
                RestingHR.Source.BAND -> conf.cap(ConfidenceLevel.MEDIUM, ConfidenceReason(ConfidenceReason.Code.RESTING_HR_FROM_BAND))
                RestingHR.Source.DAYTIME -> conf.cap(ConfidenceLevel.LOW, ConfidenceReason(ConfidenceReason.Code.RESTING_HR_FROM_DAYTIME))
            }
        }
        val used = rows.filter { it.weight > 0 && it.subscore != null }
        val totalW = used.sumOf { it.weight }
        val score = used.sumOf { it.weight * (it.subscore ?: 0.0) } / totalW
        return RecoveryResult(score.roundToInt(), usesHRV, rows, conf)
    }
}

// Strain (0-21)

data class StrainResult(
    val strain: Double,
    val trimp: Double,
    val zoneMinutes: List<Int>,
    val zoneTrimp: List<Double>,
    val hourlyTrimp: List<Double>,
    val maxHR: Double,
    val restingHR: Double,
    val k: Double,
    val awakeMinutes: Int,
    val heartRateMinutes: Int,
    val coverage: Double,
    val filledMinutes: Int,
    val stepMinutes: Int,
    val confidence: Confidence,
)

object Strain {
    const val ACTIVITY_FLOOR = 0.3
    val zoneBounds = doubleArrayOf(0.5, 0.6, 0.7, 0.8, 0.9)
    const val STEP_FALLBACK_STEPS = 100
    const val STEP_FALLBACK_R = 0.4
    const val MAX_FILLED_GAP = 10
    const val LOW_COVERAGE = 0.5
    const val ASSUMED_RESTING_HR = 60.0

    fun strain(trimp: Double): Double = min(21.0, 21.0 * ln(1.0 + max(trimp, 0.0) / 25.0) / ln(25.0))

    fun reserve(hr: Double, resting: Double, maxHR: Double): Double =
        if (maxHR <= resting) 0.0 else Stats.clamp((hr - resting) / (maxHR - resting), 0.0, 1.0)

    fun zone(r: Double): Int = zoneBounds.indexOfLast { r >= it }.let { if (it >= 0) it + 1 else 0 }

    fun minuteTrimp(r: Double, k: Double): Double = if (r < ACTIVITY_FLOOR) 0.0 else r * 0.64 * exp(k * r)

    fun compute(minutes: MinuteSeries, sleeps: List<SleepInput>, restingHR: Double?, profile: Profile, nowSec: Long? = null): StrainResult? {
        val maxHR = profile.maxHR
        val rest = restingHR ?: ASSUMED_RESTING_HR
        val k = profile.trimpK

        val awake = mutableListOf<Int>()
        for (i in 0 until MinuteSeries.SLOTS) {
            if (!minutes.hasRecord(i)) continue
            val t = minutes.timeSec(i)
            if (nowSec != null && t >= nowSec) break
            if (sleeps.any { it.contains(t) }) continue
            awake += i
        }
        if (awake.isEmpty()) return null

        val measured = awake.filter { ValidRange.hr(minutes.heartRate[it]) != null }
        val coverage = measured.size.toDouble() / awake.size
        val r = arrayOfNulls<Double>(MinuteSeries.SLOTS)
        for (i in measured) r[i] = reserve((minutes.heartRate[i] ?: 0).toDouble(), rest, maxHR)

        var filled = 0; var stepped = 0
        if (coverage < LOW_COVERAGE) {
            for (idx in 0 until measured.size - 1) {
                val a = measured[idx]; val b = measured[idx + 1]
                if (b - a - 1 !in 1..MAX_FILLED_GAP) continue
                val ra = r[a] ?: continue; val rb = r[b] ?: continue
                for (i in (a + 1) until b) {
                    if (r[i] != null || !minutes.hasRecord(i)) continue
                    val t = minutes.timeSec(i)
                    if (sleeps.any { it.contains(t) }) continue
                    r[i] = ra + (rb - ra) * (i - a).toDouble() / (b - a)
                    filled++
                }
            }
            for (i in awake) {
                if (ValidRange.hr(minutes.heartRate[i]) == null && (minutes.steps[i] ?: 0) >= STEP_FALLBACK_STEPS) {
                    r[i] = max(r[i] ?: 0.0, STEP_FALLBACK_R)
                    stepped++
                }
            }
        }

        val zoneMinutes = IntArray(6)
        val zoneTrimp = DoubleArray(6)
        val hourly = DoubleArray(24)
        var trimp = 0.0
        for (i in awake) {
            val ri = r[i] ?: continue
            if (ri < ACTIVITY_FLOOR) continue
            val t = minuteTrimp(ri, k)
            val z = zone(ri)
            zoneMinutes[z]++
            zoneTrimp[z] += t
            hourly[i / 60] += t
            trimp += t
        }

        val conf = Confidence(ConfidenceLevel.HIGH)
        if (measured.isEmpty()) {
            conf.cap(ConfidenceLevel.LOW, ConfidenceReason(ConfidenceReason.Code.NO_HEART_RATE))
        } else if (coverage < LOW_COVERAGE) {
            conf.cap(ConfidenceLevel.LOW, ConfidenceReason(ConfidenceReason.Code.LOW_HEART_RATE_COVERAGE, coverage))
            val gaps = (0 until measured.size - 1).map { (measured[it + 1] - measured[it]).toDouble() }
            Stats.median(gaps)?.let { conf.note(ConfidenceReason(ConfidenceReason.Code.SPARSE_HEART_RATE, it.roundToInt().toDouble())) }
        }
        if (!profile.maxHRIsKnown) conf.cap(ConfidenceLevel.MEDIUM, ConfidenceReason(ConfidenceReason.Code.AGE_UNKNOWN))
        if (restingHR == null) conf.cap(ConfidenceLevel.MEDIUM, ConfidenceReason(ConfidenceReason.Code.RESTING_HR_ASSUMED))

        return StrainResult(
            strain(trimp), trimp, zoneMinutes.toList(), zoneTrimp.toList(), hourly.toList(),
            maxHR, rest, k, awake.size, measured.size, coverage, filled, stepped, conf,
        )
    }
}

// Sleep Regularity Index (Phillips et al. 2017; Windred et al. 2024, SLEEP)

object SleepRegularity {
    const val EPOCH_MINUTES = 15
    const val EPOCHS_PER_DAY = 1440 / EPOCH_MINUTES // 96 epochs/day

    /**
     * Computes the Sleep Regularity Index (0..100) across consecutive days with sleep data.
     * Each 15-min epoch of day `d` is compared with the same clock epoch 24 h later (`d + 1`):
     * `SRI = clamp(200 * (agreement - 0.5), 0, 100)`. Requires at least 2 consecutive days.
     */
    fun computeSri(sleepsByDay: Map<Long, List<SleepInput>>, upToDay: Long, lookbackDays: Int = 7): Int? {
        val windowDays = ((upToDay - lookbackDays + 1)..upToDay).filter { !sleepsByDay[it].isNullOrEmpty() }
        if (windowDays.size < 2) return null

        val grid = HashMap<Long, BooleanArray>()
        for (d in windowDays) {
            val sleeps = sleepsByDay[d].orEmpty()
            val tz = sleeps.firstOrNull()?.tzOffsetMinutes ?: 0
            // Noon-to-noon sleep day (-12h .. +12h around wake-day midnight) keeps an entire
            // overnight sleep inside day `d` without depending on tomorrow's future sleep.
            val windowStart = d * 86_400L - 12L * 3600L - tz * 60L
            val epochs = BooleanArray(EPOCHS_PER_DAY)
            for (i in 0 until EPOCHS_PER_DAY) {
                val midSec = windowStart + i * EPOCH_MINUTES * 60L + (EPOCH_MINUTES * 30L)
                epochs[i] = sleeps.any { it.contains(midSec) }
            }
            grid[d] = epochs
        }

        var compared = 0
        var matched = 0
        for (d in windowDays) {
            val a = grid[d] ?: continue
            val b = grid[d + 1] ?: continue
            for (i in 0 until EPOCHS_PER_DAY) {
                compared++
                if (a[i] == b[i]) matched++
            }
        }
        if (compared == 0) return null
        val agreement = matched.toDouble() / compared.toDouble()
        return Stats.clamp(200.0 * (agreement - 0.5), 0.0, 100.0).roundToInt()
    }
}

// ScoreEngine (algorithm v4 — multi-watch aware, backwards-compatible with v3)

data class HistoryPoint(
    val dayIndex: Long,
    val restingHR: Double? = null,
    val hrv: Double? = null,
    val sleepScore: Double? = null,
    val strain: Double? = null,
    val midSleepMinute: Double? = null,
    val sleepValues: Map<String, Double> = emptyMap(),
    val hrvMetricKind: HrvMetricKind = HrvMetricKind.RMSSD,
    val dataSourceId: String? = null,
)

data class DayInputs(
    val dayIndex: Long,
    val minutes: MinuteSeries? = null,
    val previousMinutes: MinuteSeries? = null,
    val sleeps: List<SleepInput> = emptyList(),
    val previousSleeps: List<SleepInput> = emptyList(),
    val bandRestingHR: Int? = null,
    val nowSec: Long? = null,
    /** Pre-computed HRV (ms) from Health Connect (RMSSD), HealthKit (SDNN), or a BLE 0x2A37 spot check. */
    val externalHrvMs: Double? = null,
    /** Distinguishes RMSSD vs SDNN so heterogeneous wearables never pollute the same HRV baseline. */
    val hrvMetricKind: HrvMetricKind = HrvMetricKind.RMSSD,
    /** Identifier of the primary watch/source for this day (e.g. "ble:xiaomi", "hc:com.sec.android.app.shealth"). */
    val dataSourceId: String? = null,
)

data class DayScoreResult(
    val dayIndex: Long,
    val algoVersion: Int,
    val sleep: SleepScoreResult,
    val recovery: RecoveryResult,
    val strain: StrainResult?,
    val strainConfidence: Confidence,
    val restingHR: RestingHR?,
    val hrv: HRVResult?,
    val hrvWindows: Int,
    val hasRR: Boolean,
    val stress: StressSummary?,
    val spo2: SpO2Summary?,
    val hrvMetricKind: HrvMetricKind = HrvMetricKind.RMSSD,
    val dataSourceId: String? = null,
    val sleepRegularityIndex: Int? = null,
) {
    val history: HistoryPoint
        get() {
            val values = LinkedHashMap<String, Double>()
            for (c in sleep.contributors) if (c.value != null) values[c.name.raw] = c.value
            return HistoryPoint(
                dayIndex = dayIndex, restingHR = restingHR?.value, hrv = hrv?.rmssd,
                sleepScore = sleep.score?.toDouble(), strain = strain?.strain,
                midSleepMinute = sleep.metrics?.midSleepMinute, sleepValues = values,
                hrvMetricKind = hrvMetricKind, dataSourceId = dataSourceId,
            )
        }
}

object ScoreEngine {
    const val ALGO_VERSION = 4

    fun score(
        input: DayInputs,
        history: List<HistoryPoint>,
        profile: Profile,
        sri: Int? = null,
    ): DayScoreResult {
        val day = input.dayIndex
        val past = history.filter { it.dayIndex < day && it.dayIndex >= day - Baseline.LOOKBACK_DAYS }
            .sortedByDescending { it.dayIndex }

        val mainIndex = input.sleeps.indices.filter { !input.sleeps[it].isNap }
            .maxByOrNull { input.sleeps[it].inBedMinutes }
        val main = mainIndex?.let { input.sleeps[it] }
        val minuteDays = listOfNotNull(input.previousMinutes, input.minutes)
        val allSleeps = input.sleeps + input.previousSleeps

        val metrics = main?.let { m ->
            val awakeMean = SleepMetricsCalc.awakeHRMean(m.bedTimeSec, minuteDays, allSleeps)
            SleepMetricsCalc.compute(m, minuteDays, awakeMean)
        }

        val previousMids = past.filter { it.dayIndex >= day - SleepScore.REGULARITY_NIGHTS }.mapNotNull { it.midSleepMinute }
        val sleepBaselines = LinkedHashMap<SleepContributor.Name, Double>()
        for (name in SleepContributor.Name.entries) {
            val vals = past.mapNotNull { it.sleepValues[name.raw] }.take(Baseline.WINDOW)
            Stats.median(vals)?.let { sleepBaselines[name] = it }
        }
        val sleep = SleepScore.score(metrics, profile.sleepNeed, previousMids, sleepBaselines)
        val restingHR = RestingHR.compute(metrics?.hrLowest5Min, input.bandRestingHR, input.minutes, allSleeps)

        val rawHasRR = main?.rr?.any { it.intervalsMs.isNotEmpty() } == true
        val (rrHrv, rrWindows) = if (rawHasRR && main != null) HRV.nightly(main.rr, main.bedTimeSec, main.wakeTimeSec) else (null to 0)
        val validExtHrv = input.externalHrvMs?.takeIf { it.isFinite() && it in 5.0..300.0 }
        val usedExternalHrv = rrHrv == null && validExtHrv != null
        val hrv = rrHrv ?: validExtHrv?.let { HRVResult(it, secondHalfWindows = 1, totalBeats = 0, approximate = true) }
        val windows = if (rrHrv != null) rrWindows else if (usedExternalHrv) 1 else 0
        val hasRR = rawHasRR || usedExternalHrv
        val metricKind = if (rrHrv != null) HrvMetricKind.RMSSD else input.hrvMetricKind

        // Isolate physiological baselines by metric kind (never mix RMSSD and SDNN) and prefer
        // same-source history when switching watches (Shaffer & Ginsberg 2017; Plews et al. 2014).
        val src = input.dataSourceId
        val recentOtherSource = src != null && past.take(7).any { it.dataSourceId != null && it.dataSourceId != src }
        val sameSourcePast = if (src != null) past.filter { it.dataSourceId == null || it.dataSourceId == src } else past
        val hrvSameKind = sameSourcePast.filter { it.hrvMetricKind == metricKind }.mapNotNull { it.hrv }
        val hrvHistory = if (hrvSameKind.size >= 3 || src == null) hrvSameKind
            else past.filter { it.hrvMetricKind == metricKind }.mapNotNull { it.hrv }
        val rhrSameSource = sameSourcePast.mapNotNull { it.restingHR }
        val rhrHistory = if (rhrSameSource.size >= 3 || src == null) rhrSameSource else past.mapNotNull { it.restingHR }

        val yesterday = day - 1L
        var recovery = Recovery.score(
            Recovery.Inputs(
                hrv = hrv?.rmssd, hrvHistory = hrvHistory, hasRR = hasRR, hrvWindows = windows,
                restingHR = restingHR, restingHRHistory = rhrHistory,
                sleepScore = sleep.score?.toDouble(), sleepHistory = past.mapNotNull { it.sleepScore },
                strainYesterday = past.firstOrNull { it.dayIndex == yesterday }?.strain,
                strainHistory = past.filter { it.dayIndex < yesterday }.mapNotNull { it.strain },
            )
        )
        if (usedExternalHrv) {
            recovery.confidence.note(ConfidenceReason(ConfidenceReason.Code.HRV_EXTERNAL, detail = metricKind.raw))
        }
        if (recentOtherSource) {
            recovery.confidence.cap(ConfidenceLevel.MEDIUM, ConfidenceReason(ConfidenceReason.Code.SOURCE_CHANGED, detail = src))
        }
        if (main == null) {
            recovery = recovery.copy(score = null, confidence = Confidence.none(ConfidenceReason(ConfidenceReason.Code.NO_SLEEP)))
        }

        val strainRest = restingHR?.value ?: Baseline.compute(rhrHistory, Baseline.Floor.RESTING_HR)?.median
        val strain = input.minutes?.let { Strain.compute(it, allSleeps, strainRest, profile, input.nowSec) }
        val spo2 = if (metrics?.spo2Average != null && metrics.spo2Minimum != null) {
            SpO2Summary(metrics.spo2Average, metrics.spo2Minimum, metrics.spo2Below90Minutes ?: 0.0)
        } else null

        return DayScoreResult(
            dayIndex = day, algoVersion = ALGO_VERSION, sleep = sleep, recovery = recovery, strain = strain,
            strainConfidence = strain?.confidence ?: Confidence.none(ConfidenceReason(ConfidenceReason.Code.NO_DATA)),
            restingHR = restingHR, hrv = hrv, hrvWindows = windows, hasRR = hasRR,
            stress = input.minutes?.let { StressSummary.compute(it) }, spo2 = spo2,
            hrvMetricKind = metricKind, dataSourceId = src, sleepRegularityIndex = sri,
        )
    }

    fun scoreRange(days: List<DayInputs>, history: List<HistoryPoint> = emptyList(), profile: Profile = Profile.UNKNOWN): List<DayScoreResult> {
        val sorted = days.sortedBy { it.dayIndex }
        val sleepsByDay = sorted.associate { it.dayIndex to it.sleeps }
        val points = LinkedHashMap<Long, HistoryPoint>()
        for (p in history) points[p.dayIndex] = p
        val out = mutableListOf<DayScoreResult>()
        for (input in sorted) {
            val sri = SleepRegularity.computeSri(sleepsByDay, input.dayIndex)
            val result = score(input, points.values.toList(), profile, sri)
            points[input.dayIndex] = result.history
            out += result
        }
        return out
    }
}
