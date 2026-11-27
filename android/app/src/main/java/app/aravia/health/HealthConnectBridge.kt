package app.aravia.health

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.aravia.analytics.StageKind
import app.aravia.analytics.Stats
import app.aravia.protocol.GpsPoint
import app.aravia.routes.RouteStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Android Health Connect integration (read-only, 100% on-device):
 * Reads steps, heart rate, resting HR, RMSSD HRV, sleep stages, SpO2, calories, distance and
 * exercise GPS routes from any watch synced with the phone (Samsung Galaxy Watch, Garmin,
 * Amazfit/Zepp, Xiaomi/Mi Fitness, Polar, Fitbit, Pixel Watch, Wear OS, Huawei, Oura, etc.).
 *
 * Uses Health Connect's period aggregation to prevent watch+phone double counting and prioritizes
 * dedicated watch DataOrigins over phone sensors.
 */
object HealthConnectBridge {
    enum class Availability { AVAILABLE, NEEDS_INSTALL, UNAVAILABLE }

    data class SyncState(
        val availability: Availability = Availability.UNAVAILABLE,
        val connected: Boolean = false,
        val grantedCount: Int = 0,
        val syncing: Boolean = false,
        val lastMessage: String? = null,
        val error: String? = null,
    )

    val READ_PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    fun checkAvailability(context: Context): Availability {
        val status = runCatching {
            HealthConnectClient.getSdkStatus(context, "com.google.android.apps.healthdata")
        }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)
        return when (status) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.AVAILABLE
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.NEEDS_INSTALL
            else -> Availability.UNAVAILABLE
        }
    }

    suspend fun refreshStatus(context: Context): SyncState {
        val avail = checkAvailability(context)
        if (avail != Availability.AVAILABLE) {
            val s = _state.value.copy(availability = avail, connected = false, grantedCount = 0)
            _state.value = s
            return s
        }
        val granted = runCatching {
            HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()
        }.getOrDefault(emptySet())
        val count = READ_PERMISSIONS.count { it in granted }
        val s = _state.value.copy(
            availability = avail,
            connected = count > 0,
            grantedCount = count,
        )
        _state.value = s
        return s
    }

    /** Opens Google Play to install/update Health Connect on Android 10-13, or opens HC settings. */
    fun openHealthConnect(context: Context) {
        val avail = checkAvailability(context)
        val intent = if (avail == Availability.NEEDS_INSTALL) {
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("market://details?id=com.google.android.apps.healthdata"),
            ).apply {
                setPackage("com.android.vending")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /** Silently syncs on app launch if the user has already granted Health Connect permissions. */
    suspend fun syncIfPermitted(context: Context) {
        val st = refreshStatus(context)
        if (st.connected) sync(context, daysBack = 30)
    }

    /**
     * Reads up to `daysBack` days from Health Connect, deduplicates across origins, imports any
     * available GPS exercise routes into RouteStore, and updates HealthConnectStore.
     */
    suspend fun sync(context: Context, daysBack: Int = 30) {
        val avail = checkAvailability(context)
        if (avail != Availability.AVAILABLE) {
            _state.value = _state.value.copy(availability = avail, error = "Health Connect não disponível neste aparelho.")
            return
        }
        if (_state.value.syncing) return
        _state.value = _state.value.copy(availability = avail, syncing = true, error = null)

        try {
            val client = HealthConnectClient.getOrCreate(context)
            val granted = client.permissionController.getGrantedPermissions()
            val grantedCount = READ_PERMISSIONS.count { it in granted }
            if (grantedCount == 0) {
                _state.value = _state.value.copy(
                    connected = false,
                    grantedCount = 0,
                    syncing = false,
                    lastMessage = "Nenhuma permissão concedida no Health Connect.",
                )
                return
            }

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val startDay = today.minusDays((daysBack - 1).toLong())
            val startInstant = startDay.atStartOfDay(zone).toInstant()
            val endInstant = Instant.now()
            val range = TimeRangeFilter.between(startInstant, endInstant)

            val result = withContext(Dispatchers.IO) {
                val dailySteps = HashMap<LocalDate, Int>()
                val dailyDistance = HashMap<LocalDate, Int>()
                val dailyCalories = HashMap<LocalDate, Int>()
                val minuteStepsByDay = HashMap<LocalDate, MutableMap<Int, Int>>()
                val hrByDay = HashMap<LocalDate, MutableMap<Long, Pair<Int, Int>>>() // ts -> (priority, bpm)
                val rhrByDay = HashMap<LocalDate, MutableList<Pair<Int, Int>>>() // (priority, bpm)
                val hrvByDay = HashMap<LocalDate, MutableList<Pair<Int, Double>>>() // (priority, rmssd)
                val spo2ByDay = HashMap<LocalDate, MutableList<Pair<Long, Int>>>()
                val sleepsByDay = HashMap<LocalDate, MutableList<HealthConnectStore.SleepSnapshot>>()
                val sourceVotes = HashMap<LocalDate, MutableMap<String, Int>>()

                fun voteSource(day: LocalDate, pkg: String) {
                    if (pkg.isBlank()) return
                    val m = sourceVotes.getOrPut(day) { HashMap() }
                    m[pkg] = (m[pkg] ?: 0) + watchSourcePriority(pkg)
                }

                // 1. Deduplicated daily aggregates (steps, distance, active/total calories)
                val aggMetrics = buildSet {
                    if (HealthPermission.getReadPermission(StepsRecord::class) in granted) add(StepsRecord.COUNT_TOTAL)
                    if (HealthPermission.getReadPermission(DistanceRecord::class) in granted) add(DistanceRecord.DISTANCE_TOTAL)
                    if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in granted) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
                    if (HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class) in granted) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
                }
                if (aggMetrics.isNotEmpty()) {
                    runCatching {
                        val startLdt = startDay.atStartOfDay()
                        val endLdt = LocalDateTime.now(zone).plusMinutes(1)
                        val buckets = client.aggregateGroupByPeriod(
                            AggregateGroupByPeriodRequest(
                                metrics = aggMetrics,
                                timeRangeFilter = TimeRangeFilter.between(startLdt, endLdt),
                                timeRangeSlicer = Period.ofDays(1),
                            )
                        )
                        for (b in buckets) {
                            val d = b.startTime.toLocalDate()
                            b.result[StepsRecord.COUNT_TOTAL]?.let { if (it > 0) dailySteps[d] = it.toInt() }
                            b.result[DistanceRecord.DISTANCE_TOTAL]?.inMeters?.let { if (it > 0) dailyDistance[d] = it.roundToInt() }
                            val activeKcal = b.result[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
                            val totalKcal = b.result[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories
                            (activeKcal ?: totalKcal)?.let { if (it > 0) dailyCalories[d] = it.roundToInt() }
                            for (origin in b.result.dataOrigins) voteSource(d, origin.packageName)
                        }
                    }
                }

                // 2. Step buckets for minute-level activity fallback
                if (HealthPermission.getReadPermission(StepsRecord::class) in granted) {
                    readAllPages(client, StepsRecord::class, range) { rec ->
                        val pkg = rec.metadata.dataOrigin.packageName
                        val zdt = rec.startTime.atZone(zone)
                        val d = zdt.toLocalDate()
                        voteSource(d, pkg)
                        val slot = zdt.hour * 60 + zdt.minute
                        val map = minuteStepsByDay.getOrPut(d) { HashMap() }
                        val durMin = ((rec.endTime.epochSecond - rec.startTime.epochSecond) / 60L).toInt().coerceAtLeast(1)
                        val perMin = (rec.count.toDouble() / durMin).roundToInt().coerceAtLeast(1)
                        for (m in 0 until minOf(durMin, 60)) {
                            val s = slot + m
                            if (s in 0 until 1440) {
                                map[s] = maxOf(map[s] ?: 0, perMin)
                            }
                        }
                    }
                }

                // 3. Heart rate samples (prioritize dedicated watch over phone sensors)
                if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted) {
                    readAllPages(client, HeartRateRecord::class, range) { rec ->
                        val pkg = rec.metadata.dataOrigin.packageName
                        val prio = watchSourcePriority(pkg)
                        for (sample in rec.samples) {
                            val bpm = sample.beatsPerMinute.toInt()
                            if (bpm !in 30..220) continue
                            val ts = sample.time.epochSecond
                            val d = sample.time.atZone(zone).toLocalDate()
                            voteSource(d, pkg)
                            val bucketTs = (ts / 60L) * 60L
                            val map = hrByDay.getOrPut(d) { HashMap() }
                            val prev = map[bucketTs]
                            if (prev == null || prio >= prev.first) {
                                map[bucketTs] = prio to bpm
                            }
                        }
                    }
                }

                // 4. Resting heart rate
                if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) in granted) {
                    readAllPages(client, RestingHeartRateRecord::class, range) { rec ->
                        val bpm = rec.beatsPerMinute.toInt()
                        if (bpm !in 30..140) return@readAllPages
                        val pkg = rec.metadata.dataOrigin.packageName
                        val d = rec.time.atZone(zone).toLocalDate()
                        voteSource(d, pkg)
                        rhrByDay.getOrPut(d) { mutableListOf() } += watchSourcePriority(pkg) to bpm
                    }
                }

                // 5. HRV (RMSSD — Health Connect standard)
                if (HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class) in granted) {
                    readAllPages(client, HeartRateVariabilityRmssdRecord::class, range) { rec ->
                        val ms = rec.heartRateVariabilityMillis
                        if (!ms.isFinite() || ms !in 5.0..300.0) return@readAllPages
                        val pkg = rec.metadata.dataOrigin.packageName
                        val d = rec.time.atZone(zone).toLocalDate()
                        voteSource(d, pkg)
                        hrvByDay.getOrPut(d) { mutableListOf() } += watchSourcePriority(pkg) to ms
                    }
                }

                // 6. Blood oxygen (SpO2)
                if (HealthPermission.getReadPermission(OxygenSaturationRecord::class) in granted) {
                    readAllPages(client, OxygenSaturationRecord::class, range) { rec ->
                        val pct = rec.percentage.value.roundToInt()
                        if (pct !in 70..100) return@readAllPages
                        val d = rec.time.atZone(zone).toLocalDate()
                        voteSource(d, rec.metadata.dataOrigin.packageName)
                        spo2ByDay.getOrPut(d) { mutableListOf() } += rec.time.epochSecond to pct
                    }
                }

                // 7. Sleep sessions (supports 2-stage, 3-stage, and 4-stage wearables)
                if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
                    readAllPages(client, SleepSessionRecord::class, range) { rec ->
                        val bed = rec.startTime.epochSecond
                        val wake = rec.endTime.epochSecond
                        if (wake <= bed) return@readAllPages
                        val pkg = rec.metadata.dataOrigin.packageName
                        val wakeZdt = rec.endTime.atZone(zone)
                        val wakeDay = wakeZdt.toLocalDate()
                        val tzMin = wakeZdt.offset.totalSeconds / 60
                        voteSource(wakeDay, pkg)

                        var detailed = false
                        val stages = rec.stages.mapNotNull { st ->
                            val dur = ((st.endTime.epochSecond - st.startTime.epochSecond) / 60L).toInt()
                            if (dur <= 0) return@mapNotNull null
                            val kind = when (st.stage) {
                                SleepSessionRecord.STAGE_TYPE_AWAKE,
                                SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> StageKind.AWAKE
                                SleepSessionRecord.STAGE_TYPE_LIGHT -> { detailed = true; StageKind.LIGHT }
                                SleepSessionRecord.STAGE_TYPE_DEEP -> { detailed = true; StageKind.DEEP }
                                SleepSessionRecord.STAGE_TYPE_REM -> { detailed = true; StageKind.REM }
                                SleepSessionRecord.STAGE_TYPE_SLEEPING -> StageKind.LIGHT
                                SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> StageKind.NOT_SLEEP
                                else -> StageKind.UNKNOWN
                            }
                            HealthConnectStore.SleepStageEntry(st.startTime.epochSecond, dur, kind)
                        }
                        sleepsByDay.getOrPut(wakeDay) { mutableListOf() } += HealthConnectStore.SleepSnapshot(
                            bedTimeSec = bed,
                            wakeTimeSec = wake,
                            tzOffsetMinutes = tzMin,
                            stages = stages,
                            hasDetailedStages = detailed,
                            sourcePackage = pkg,
                        )
                    }
                }

                // 8. Exercise sessions & GPS routes -> RouteStore (3D maps + Privacy Zone + Stories!)
                var routesImported = 0
                if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
                    readAllPages(client, ExerciseSessionRecord::class, range) { rec ->
                        val pkg = rec.metadata.dataOrigin.packageName
                        voteSource(rec.startTime.atZone(zone).toLocalDate(), pkg)
                        val routeRes = rec.exerciseRouteResult
                        if (routeRes is ExerciseRouteResult.Data) {
                            val pts = routeRes.exerciseRoute.route.map { loc ->
                                GpsPoint(
                                    time = loc.time.epochSecond,
                                    latitude = loc.latitude,
                                    longitude = loc.longitude,
                                    speedKmh = null,
                                )
                            }
                            if (pts.size >= 2) {
                                val subtype = mapExerciseSubtype(rec.exerciseType)
                                val title = rec.title?.takeIf { it.isNotBlank() } ?: RouteStore.sportTitle(subtype)
                                val imported = RouteStore.importHealthConnectRoute(
                                    recordId = rec.metadata.id,
                                    title = title,
                                    subtype = subtype,
                                    points = pts,
                                )
                                if (imported != null) routesImported++
                            }
                        }
                    }
                }

                val allDays = (dailySteps.keys + dailyDistance.keys + dailyCalories.keys +
                    hrByDay.keys + rhrByDay.keys + hrvByDay.keys + spo2ByDay.keys + sleepsByDay.keys).sorted()

                val snapshots = allDays.map { d ->
                    val tzMin = d.atStartOfDay(zone).offset.totalSeconds / 60
                    val primaryPkg = sourceVotes[d]?.maxByOrNull { it.value }?.key
                    val rhrCandidates = rhrByDay[d].orEmpty()
                    val bestRhrPrio = rhrCandidates.maxOfOrNull { it.first }
                    val rhr = rhrCandidates.filter { it.first == bestRhrPrio }
                        .map { it.second.toDouble() }
                        .let { Stats.median(it)?.roundToInt() }

                    val hrvCandidates = hrvByDay[d].orEmpty()
                    val bestHrvPrio = hrvCandidates.maxOfOrNull { it.first }
                    val hrv = hrvCandidates.filter { it.first == bestHrvPrio }
                        .map { it.second }
                        .let { Stats.median(it) }

                    // Keep the highest-priority watch's sleep sessions if multiple apps wrote overlapping nights.
                    val rawSleeps = sleepsByDay[d].orEmpty()
                    val dedupSleeps = deduplicateSleeps(rawSleeps)

                    HealthConnectStore.DaySnapshot(
                        day = d,
                        tzOffsetMinutes = tzMin,
                        steps = dailySteps[d],
                        distanceMeters = dailyDistance[d],
                        activeCalories = dailyCalories[d],
                        restingHr = rhr,
                        hrvRmssdMs = hrv,
                        spo2Samples = spo2ByDay[d]?.sortedBy { it.first } ?: emptyList(),
                        heartRateSamples = hrByDay[d]?.entries?.sortedBy { it.key }?.map { it.key to it.value.second } ?: emptyList(),
                        minuteSteps = minuteStepsByDay[d] ?: emptyMap(),
                        sleeps = dedupSleeps,
                        primarySource = primaryPkg,
                    )
                }

                HealthConnectStore.saveAll(snapshots)
                snapshots.size to routesImported
            }

            val (dayCount, routeCount) = result
            val msg = buildString {
                append("$dayCount ${if (dayCount == 1) "dia sincronizado" else "dias sincronizados"}")
                if (routeCount > 0) append(" · $routeCount ${if (routeCount == 1) "rota GPS" else "rotas GPS"}")
            }
            _state.value = _state.value.copy(
                connected = true,
                grantedCount = grantedCount,
                syncing = false,
                lastMessage = msg,
                error = null,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                syncing = false,
                error = e.localizedMessage ?: "Não deu para ler o Health Connect agora.",
            )
        }
    }

    private suspend fun <T : androidx.health.connect.client.records.Record> readAllPages(
        client: HealthConnectClient,
        recordType: kotlin.reflect.KClass<T>,
        range: TimeRangeFilter,
        onRecord: (T) -> Unit,
    ) {
        var pageToken: String? = null
        var guard = 0
        do {
            val resp = runCatching {
                client.readRecords(ReadRecordsRequest(recordType, range, pageToken = pageToken))
            }.getOrNull() ?: return
            for (r in resp.records) onRecord(r)
            pageToken = resp.pageToken
            guard++
        } while (pageToken != null && guard < 20)
    }

    /** Dedicated wearable companion apps outrank phone step counters / aggregator apps. */
    internal fun watchSourcePriority(pkg: String): Int {
        val p = pkg.lowercase()
        return when {
            p.contains("shealth") || p.contains("garmin") || p.contains("polar") ||
                p.contains("coros") || p.contains("suunto") || p.contains("oura") ||
                p.contains("whoop") || p.contains("fitbit") || p.contains("huami") ||
                p.contains("zepp") || p.contains("xiaomi.wearable") || p.contains("huawei.health") -> 30
            p.contains("strava") || p.contains("gadgetbridge") -> 20
            else -> 10
        }
    }

    private fun deduplicateSleeps(list: List<HealthConnectStore.SleepSnapshot>): List<HealthConnectStore.SleepSnapshot> {
        if (list.size <= 1) return list
        val sorted = list.sortedWith(
            compareByDescending<HealthConnectStore.SleepSnapshot> { watchSourcePriority(it.sourcePackage) }
                .thenByDescending { it.hasDetailedStages }
                .thenByDescending { it.wakeTimeSec - it.bedTimeSec }
        )
        val kept = mutableListOf<HealthConnectStore.SleepSnapshot>()
        for (cand in sorted) {
            val overlaps = kept.any { existing ->
                val ovStart = maxOf(existing.bedTimeSec, cand.bedTimeSec)
                val ovEnd = minOf(existing.wakeTimeSec, cand.wakeTimeSec)
                val ov = (ovEnd - ovStart).coerceAtLeast(0L)
                val minDur = minOf(existing.wakeTimeSec - existing.bedTimeSec, cand.wakeTimeSec - cand.bedTimeSec).coerceAtLeast(1L)
                ov.toDouble() / minDur.toDouble() > 0.5
            }
            if (!overlaps) kept += cand
        }
        return kept
    }

    private fun mapExerciseSubtype(exerciseType: Int): Int = when (exerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> 0x01
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> 0x03
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> 0x02
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> 0x06
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> 0x07
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> 0x09
        else -> 0x08
    }
}
