package app.aravia.health

import android.content.Context
import app.aravia.analytics.MinuteSeries
import app.aravia.analytics.SleepInput
import app.aravia.analytics.StageKind
import app.aravia.analytics.StageSegment
import app.aravia.analytics.TimedValue
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * On-device offline cache of normalized daily health snapshots imported from Android Health Connect
 * (Galaxy Watch, Garmin, Amazfit/Zepp, Xiaomi/Mi Fitness, Polar, Fitbit, Wear OS, etc.) and
 * optional 60s BLE 0x2A37 Spot HRV check-ins. Never leaves the phone.
 */
object HealthConnectStore {
    data class SleepStageEntry(val startSec: Long, val minutes: Int, val stage: StageKind)

    data class SleepSnapshot(
        val bedTimeSec: Long,
        val wakeTimeSec: Long,
        val tzOffsetMinutes: Int,
        val stages: List<SleepStageEntry>,
        val hasDetailedStages: Boolean,
        val sourcePackage: String,
    ) {
        fun toSleepInput(hrSamples: List<TimedValue>, spo2Samples: List<TimedValue>): SleepInput {
            var deep = 0; var light = 0; var rem = 0; var awake = 0; var wakes = 0
            val segs = stages.map { st ->
                when (st.stage) {
                    StageKind.DEEP -> deep += st.minutes
                    StageKind.LIGHT -> light += st.minutes
                    StageKind.REM -> rem += st.minutes
                    StageKind.AWAKE -> { awake += st.minutes; wakes++ }
                    else -> {}
                }
                StageSegment(st.startSec, st.minutes, st.stage)
            }
            val inBedMin = ((wakeTimeSec - bedTimeSec) / 60L).toInt().coerceAtLeast(0)
            val isNap = (wakeTimeSec - bedTimeSec) < 3 * 3600L
            return SleepInput(
                bedTimeSec = bedTimeSec,
                wakeTimeSec = wakeTimeSec,
                tzOffsetMinutes = tzOffsetMinutes,
                deepMinutes = if (hasDetailedStages && deep > 0) deep else null,
                lightMinutes = if (hasDetailedStages && light > 0) light else if (!hasDetailedStages && inBedMin > 0) (inBedMin - awake).coerceAtLeast(0) else null,
                remMinutes = if (hasDetailedStages && rem > 0) rem else null,
                awakeMinutes = if (awake > 0 || hasDetailedStages) awake else null,
                segments = segs,
                wakeCount = if (wakes > 0) wakes else null,
                heartRate = hrSamples.filter { it.tsSec in bedTimeSec..wakeTimeSec },
                hrIntervalSeconds = 60,
                spo2 = spo2Samples.filter { it.tsSec in bedTimeSec..wakeTimeSec },
                spo2IntervalSeconds = 60,
                rr = emptyList(),
                isNap = isNap,
            )
        }
    }

    data class DaySnapshot(
        val day: LocalDate,
        val tzOffsetMinutes: Int,
        val steps: Int?,
        val distanceMeters: Int?,
        val activeCalories: Int?,
        val restingHr: Int?,
        val hrvRmssdMs: Double?,
        val spo2Samples: List<Pair<Long, Int>>,
        val heartRateSamples: List<Pair<Long, Int>>,
        val minuteSteps: Map<Int, Int>,
        val sleeps: List<SleepSnapshot>,
        val primarySource: String?,
    ) {
        fun toMinuteSeries(): MinuteSeries {
            val ms = MinuteSeries(day.toEpochDay(), tzOffsetMinutes)
            for ((slot, s) in minuteSteps) {
                if (slot in 0 until MinuteSeries.SLOTS) ms.steps[slot] = s
            }
            for ((ts, hr) in heartRateSamples) {
                val slot = ((ts - ms.startSec) / 60L).toInt()
                if (slot in 0 until MinuteSeries.SLOTS && hr in 30..220) ms.heartRate[slot] = hr
            }
            for ((ts, sp) in spo2Samples) {
                val slot = ((ts - ms.startSec) / 60L).toInt()
                if (slot in 0 until MinuteSeries.SLOTS && sp in 70..100) ms.spo2[slot] = sp
            }
            return ms
        }
    }

    private var file: File? = null
    private val daysMap = LinkedHashMap<LocalDate, DaySnapshot>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private val _lastSyncSec = MutableStateFlow<Long?>(null)
    val lastSyncSec: StateFlow<Long?> = _lastSyncSec.asStateFlow()
    private val _detectedSources = MutableStateFlow<List<String>>(emptyList())
    val detectedSources: StateFlow<List<String>> = _detectedSources.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "health_connect_days.json")
        loadFromDisk()
    }

    fun allDays(): Map<LocalDate, DaySnapshot> = synchronized(daysMap) { LinkedHashMap(daysMap) }

    fun saveAll(snapshots: List<DaySnapshot>, syncSec: Long = System.currentTimeMillis() / 1000L) {
        synchronized(daysMap) {
            for (s in snapshots) {
                val existing = daysMap[s.day]
                // Preserve a BLE 0x2A37 spot HRV reading if Health Connect didn't have HRV for this day.
                val mergedHrv = s.hrvRmssdMs ?: existing?.hrvRmssdMs
                daysMap[s.day] = s.copy(hrvRmssdMs = mergedHrv)
            }
            // Keep up to 90 days locally for fast startup.
            val cutoff = LocalDate.now().minusDays(90)
            daysMap.keys.removeAll { it.isBefore(cutoff) }
            persistLocked(syncSec)
        }
        _lastSyncSec.value = syncSec
        updateSources()
        _version.value++
    }

    /** Saves a 60s Spot HRV (RMSSD) reading measured via standard BLE 0x180D/0x2A37 strap/sensor. */
    fun saveSpotHrv(day: LocalDate, rmssdMs: Double, meanHr: Int?) {
        val tzMin = ZoneId.systemDefault().rules.getOffset(java.time.Instant.now()).totalSeconds / 60
        synchronized(daysMap) {
            val prev = daysMap[day]
            daysMap[day] = prev?.copy(
                hrvRmssdMs = rmssdMs,
                restingHr = prev.restingHr ?: meanHr,
                primarySource = prev.primarySource ?: "ble:0x180d",
            ) ?: DaySnapshot(
                day = day,
                tzOffsetMinutes = tzMin,
                steps = null,
                distanceMeters = null,
                activeCalories = null,
                restingHr = meanHr,
                hrvRmssdMs = rmssdMs,
                spo2Samples = emptyList(),
                heartRateSamples = emptyList(),
                minuteSteps = emptyMap(),
                sleeps = emptyList(),
                primarySource = "ble:0x180d",
            )
            persistLocked(_lastSyncSec.value ?: (System.currentTimeMillis() / 1000L))
        }
        updateSources()
        _version.value++
    }

    fun clear() {
        synchronized(daysMap) {
            daysMap.clear()
            file?.delete()
        }
        _lastSyncSec.value = null
        _detectedSources.value = emptyList()
        _version.value++
    }

    private fun updateSources() {
        val pkgs = synchronized(daysMap) {
            daysMap.values.mapNotNull { it.primarySource }.distinct()
        }
        _detectedSources.value = pkgs.map { friendlySourceName(it) }.distinct()
    }

    fun friendlySourceName(pkg: String): String = when {
        pkg.contains("shealth") -> "Samsung Health (Galaxy Watch)"
        pkg.contains("garmin") -> "Garmin Connect"
        pkg.contains("huami") || pkg.contains("zepp") -> "Zepp (Amazfit)"
        pkg.contains("xiaomi.wearable") || pkg.contains("mi.health") -> "Mi Fitness (Xiaomi/Redmi)"
        pkg.contains("polar") -> "Polar Flow"
        pkg.contains("fitbit") -> "Fitbit / Pixel Watch"
        pkg.contains("huawei") -> "Huawei Health"
        pkg.contains("coros") -> "COROS"
        pkg.contains("suunto") -> "Suunto"
        pkg.contains("oura") -> "Oura"
        pkg.contains("whoop") -> "WHOOP"
        pkg.contains("strava") -> "Strava"
        pkg.contains("fitness") -> "Google Fit"
        pkg == "ble:0x180d" -> "Sensor cardíaco Bluetooth"
        else -> pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private fun persistLocked(syncSec: Long) {
        val f = file ?: return
        runCatching {
            val root = JSONObject().put("v", 1).put("syncSec", syncSec)
            val arr = JSONArray()
            for (d in daysMap.values.sortedBy { it.day }) {
                val o = JSONObject()
                    .put("day", d.day.toString())
                    .put("tz", d.tzOffsetMinutes)
                d.steps?.let { o.put("steps", it) }
                d.distanceMeters?.let { o.put("dist", it) }
                d.activeCalories?.let { o.put("kcal", it) }
                d.restingHr?.let { o.put("rhr", it) }
                d.hrvRmssdMs?.let { o.put("hrv", it) }
                d.primarySource?.let { o.put("src", it) }

                val hrArr = JSONArray()
                for ((ts, v) in d.heartRateSamples) hrArr.put(JSONArray().put(ts).put(v))
                o.put("hr", hrArr)

                val spArr = JSONArray()
                for ((ts, v) in d.spo2Samples) spArr.put(JSONArray().put(ts).put(v))
                o.put("spo2", spArr)

                val msObj = JSONObject()
                for ((slot, st) in d.minuteSteps) msObj.put(slot.toString(), st)
                o.put("msteps", msObj)

                val slArr = JSONArray()
                for (sl in d.sleeps) {
                    val so = JSONObject()
                        .put("bed", sl.bedTimeSec)
                        .put("wake", sl.wakeTimeSec)
                        .put("tz", sl.tzOffsetMinutes)
                        .put("detailed", sl.hasDetailedStages)
                        .put("src", sl.sourcePackage)
                    val stArr = JSONArray()
                    for (seg in sl.stages) {
                        stArr.put(JSONArray().put(seg.startSec).put(seg.minutes).put(seg.stage.name))
                    }
                    so.put("stages", stArr)
                    slArr.put(so)
                }
                o.put("sleeps", slArr)
                arr.put(o)
            }
            root.put("days", arr)
            f.writeText(root.toString(), Charsets.UTF_8)
        }
    }

    private fun loadFromDisk() {
        val f = file ?: return
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText(Charsets.UTF_8))
            _lastSyncSec.value = root.optLong("syncSec").takeIf { it > 0 }
            val arr = root.optJSONArray("days") ?: return
            synchronized(daysMap) {
                daysMap.clear()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val day = LocalDate.parse(o.getString("day"))
                    val hr = mutableListOf<Pair<Long, Int>>()
                    o.optJSONArray("hr")?.let { a ->
                        for (j in 0 until a.length()) {
                            val p = a.getJSONArray(j)
                            hr += p.getLong(0) to p.getInt(1)
                        }
                    }
                    val spo2 = mutableListOf<Pair<Long, Int>>()
                    o.optJSONArray("spo2")?.let { a ->
                        for (j in 0 until a.length()) {
                            val p = a.getJSONArray(j)
                            spo2 += p.getLong(0) to p.getInt(1)
                        }
                    }
                    val msteps = HashMap<Int, Int>()
                    o.optJSONObject("msteps")?.let { mo ->
                        val it = mo.keys()
                        while (it.hasNext()) {
                            val k = it.next()
                            k.toIntOrNull()?.let { slot -> msteps[slot] = mo.getInt(k) }
                        }
                    }
                    val sleeps = mutableListOf<SleepSnapshot>()
                    o.optJSONArray("sleeps")?.let { sa ->
                        for (j in 0 until sa.length()) {
                            val so = sa.getJSONObject(j)
                            val stList = mutableListOf<SleepStageEntry>()
                            so.optJSONArray("stages")?.let { sta ->
                                for (k in 0 until sta.length()) {
                                    val e = sta.getJSONArray(k)
                                    val kind = runCatching { StageKind.valueOf(e.getString(2)) }.getOrDefault(StageKind.UNKNOWN)
                                    stList += SleepStageEntry(e.getLong(0), e.getInt(1), kind)
                                }
                            }
                            sleeps += SleepSnapshot(
                                bedTimeSec = so.getLong("bed"),
                                wakeTimeSec = so.getLong("wake"),
                                tzOffsetMinutes = so.optInt("tz", 0),
                                stages = stList,
                                hasDetailedStages = so.optBoolean("detailed", false),
                                sourcePackage = so.optString("src", ""),
                            )
                        }
                    }
                    daysMap[day] = DaySnapshot(
                        day = day,
                        tzOffsetMinutes = o.optInt("tz", 0),
                        steps = if (o.has("steps")) o.getInt("steps") else null,
                        distanceMeters = if (o.has("dist")) o.getInt("dist") else null,
                        activeCalories = if (o.has("kcal")) o.getInt("kcal") else null,
                        restingHr = if (o.has("rhr")) o.getInt("rhr") else null,
                        hrvRmssdMs = if (o.has("hrv")) o.getDouble("hrv") else null,
                        spo2Samples = spo2,
                        heartRateSamples = hr,
                        minuteSteps = msteps,
                        sleeps = sleeps,
                        primarySource = o.optString("src").takeIf { it.isNotBlank() },
                    )
                }
            }
            updateSources()
            _version.value++
        }
    }
}
