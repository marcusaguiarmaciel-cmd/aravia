package app.aravia.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsTest {
    @Test
    fun profileDefaultsAndOverrides() {
        val p = Profile.UNKNOWN
        assertEquals(208.0 - 0.7 * 35, p.maxHR, 1e-6)
        assertEquals(1.80, p.trimpK, 1e-6)
        assertEquals(480, p.sleepNeed)

        val custom = Profile(age = 30, sex = Profile.Sex.MALE, maxHROverride = 195, sleepNeedMinutes = 450)
        assertEquals(195.0, custom.maxHR, 1e-6)
        assertEquals(1.92, custom.trimpK, 1e-6)
        assertEquals(450, custom.sleepNeed)
    }

    @Test
    fun baselineCalibrationAndFloor() {
        assertEquals(Baseline.Status.CALIBRATING, Baseline.compute(listOf(60.0, 61.0), Baseline.Floor.RESTING_HR)!!.status)
        val b = Baseline.compute(List(7) { 60.0 }, Baseline.Floor.RESTING_HR)!!
        assertEquals(Baseline.Status.NORMAL, b.status)
        // 3% of 60 = 1.8 > 1.5 absolute floor
        assertEquals(1.8, b.sigma, 1e-6)
    }

    @Test
    fun sleepScoreDurationCapAndMidnightWrap() {
        // Short 4h 52m night with great efficiency and stages cannot exceed duration + 10
        val bed = 1_760_000_000L
        val wake = bed + 300 * 60L
        val sleep = SleepInput(bed, wake, tzOffsetMinutes = -180, deepMinutes = 70, lightMinutes = 142, remMinutes = 80, awakeMinutes = 8)
        val m = SleepMetricsCalc.compute(sleep, emptyList(), awakeHRMean = 72.0)
        val res = SleepScore.score(m, sleepNeedMinutes = 480, previousMidSleeps = emptyList())
        val durSub = res.contributors.first { it.name == SleepContributor.Name.DURATION }.subscore!!
        assertNotNull(res.score)
        assertTrue(res.score!! <= (durSub + SleepScore.DURATION_LEAD + 0.5).toInt())

        // Regularity across midnight: 00:10 (10) vs 23:50 (1430) -> 20 min deviation
        val dev = SleepScore.regularityDeviation(10.0, listOf(1430.0, 1430.0, 1430.0))!!
        assertEquals(20.0, dev, 1e-6)
    }

    @Test
    fun strainCurveAndStepFallback() {
        assertEquals(10.5, Strain.strain(100.0), 0.1)
        assertEquals(14.3, Strain.strain(200.0), 0.1)
        assertEquals(21.0, Strain.strain(10_000.0), 1e-6)

        val ms = MinuteSeries(dayIndex = 20_000L, tzOffsetMinutes = 0)
        for (i in 600 until 660) ms.steps[i] = 120 // 60 min brisk walk without HR
        val res = Strain.compute(ms, emptyList(), restingHR = 60.0, profile = Profile(age = 30, sex = Profile.Sex.MALE))!!
        assertEquals(60, res.stepMinutes)
        assertTrue(res.strain > 0.0)
    }

    @Test
    fun recoveryWithoutHrvAndNoSleepV3Rule() {
        val days = (0L until 8L).map { d ->
            val dayStart = d * 86_400L
            val sleep = SleepInput(bedTimeSec = dayStart - 30 * 60L, wakeTimeSec = dayStart + 420 * 60L,
                deepMinutes = 80, lightMinutes = 250, remMinutes = 100, awakeMinutes = 20)
            DayInputs(dayIndex = d, sleeps = listOf(sleep), bandRestingHR = 56)
        } + DayInputs(dayIndex = 8L, sleeps = emptyList(), bandRestingHR = 72)

        val scored = ScoreEngine.scoreRange(days, profile = Profile(age = 30))
        // Day 7 has 7 previous nights and resting HR -> recovery score around 60
        assertNotNull(scored[7].recovery.score)
        assertEquals(60, scored[7].recovery.score!!)
        // Day 8 has no sleep -> v3 rule suppresses recovery score
        assertNull(scored[8].recovery.score)
    }

    @Test
    fun hrvFiltersArtifactsAndUsesSecondHalf() {
        val bed = 1_760_000_000L
        val wake = bed + 7 * 3600L
        val intervals = mutableListOf<Int>()
        // 6 hours of alternating 800/840 ms beats (~27 000 beats -> ~72 five-min windows)
        repeat(25_000) { i -> intervals += if (i % 2 == 0) 800 else 840 }
        intervals[100] = 2500 // artifact > 2000 ms
        val (res, count) = HRV.nightly(listOf(RRSegment(bed, intervals)), bed, wake)
        assertNotNull(res)
        assertTrue(count >= 3)
        assertEquals(40.0, res!!.rmssd, 1.0)
    }

    @Test
    fun externalHrvAndMetricKindIsolationAcrossWatches() {
        // 7 days of Health Connect RMSSD (~60 ms) followed by day 7 with 72 ms RMSSD
        val days = (0L until 8L).map { d ->
            val dayStart = d * 86_400L
            val sleep = SleepInput(bedTimeSec = dayStart - 30 * 60L, wakeTimeSec = dayStart + 450 * 60L,
                deepMinutes = 85, lightMinutes = 260, remMinutes = 110, awakeMinutes = 15)
            DayInputs(
                dayIndex = d,
                sleeps = listOf(sleep),
                bandRestingHR = 56,
                externalHrvMs = if (d < 7L) 60.0 else 72.0,
                hrvMetricKind = HrvMetricKind.RMSSD,
                dataSourceId = "hc:com.sec.android.app.shealth",
            )
        }
        val scored = ScoreEngine.scoreRange(days, profile = Profile(age = 30))
        val day7 = scored[7]
        assertTrue(day7.recovery.usesHRV)
        assertNotNull(day7.recovery.score)
        assertTrue(day7.recovery.score!! > 65) // Elevated RMSSD -> above-baseline recovery
        assertTrue(day7.recovery.confidence.reasons.any { it.code == ConfidenceReason.Code.HRV_EXTERNAL })

        // Switching metric kind to SDNN must NOT reuse the RMSSD baseline (needs its own calibration)
        val sdnnDay = DayInputs(
            dayIndex = 8L,
            sleeps = days[7].sleeps.map { it.copy(bedTimeSec = it.bedTimeSec + 86_400L, wakeTimeSec = it.wakeTimeSec + 86_400L) },
            bandRestingHR = 56,
            externalHrvMs = 95.0,
            hrvMetricKind = HrvMetricKind.SDNN,
            dataSourceId = "hk:apple.watch",
        )
        val sdnnScored = ScoreEngine.score(sdnnDay, scored.map { it.history }, Profile(age = 30))
        assertEquals(false, sdnnScored.recovery.usesHRV)
        assertTrue(sdnnScored.recovery.confidence.reasons.any { it.code == ConfidenceReason.Code.SOURCE_CHANGED })
    }

    @Test
    fun sleepRegularityIndexReflectsScheduleConsistency() {
        // Perfectly consistent 23:00–07:00 sleeper over 5 days -> SRI = 100
        val regularDays = (100L until 105L).associateWith { d ->
            val midnight = d * 86_400L
            listOf(SleepInput(bedTimeSec = midnight - 3600L, wakeTimeSec = midnight + 7 * 3600L))
        }
        assertEquals(100, SleepRegularity.computeSri(regularDays, 104L))

        // Erratic sleeper alternating 22:00–06:00 and 03:00–11:00 -> much lower SRI
        val erraticDays = (100L until 105L).associateWith { d ->
            val midnight = d * 86_400L
            if (d % 2L == 0L) listOf(SleepInput(bedTimeSec = midnight - 2 * 3600L, wakeTimeSec = midnight + 6 * 3600L))
            else listOf(SleepInput(bedTimeSec = midnight + 3 * 3600L, wakeTimeSec = midnight + 11 * 3600L))
        }
        val erraticSri = SleepRegularity.computeSri(erraticDays, 104L)!!
        assertTrue("Expected erratic SRI < 40, got $erraticSri", erraticSri < 40)
    }
}
