package app.aravia.training

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aravia.MainActivity
import app.aravia.R
import app.aravia.band.BandLink
import app.aravia.band.BandVault
import app.aravia.integrations.Strava
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The workout in progress, as on iOS (WorkoutController.swift):
 * - records the watch's live heart rate and counts steps;
 * - times the rest between sets, using an AlarmManager backup so the alert rings and vibrates
 *   reliably even with the screen off or another app in the foreground;
 * - keeps an ongoing notification up to date: the elapsed time while working, a countdown while
 *   resting, and the heart rate.
 */
object WorkoutController {
    private const val CHANNEL = "workout"
    private const val REST_CHANNEL = "rest_alert_v2"
    private const val NOTIFICATION = 41
    private const val REST_DONE = 42
    private const val REST_ALARM_REQ = 4201

    private val _session = MutableStateFlow<TrainingSession?>(null)
    val session: StateFlow<TrainingSession?> = _session.asStateFlow()
    private val _bpm = MutableStateFlow<Int?>(null)
    val bpm: StateFlow<Int?> = _bpm.asStateFlow()
    private val _restEndsAt = MutableStateFlow<Long?>(null)
    val restEndsAt: StateFlow<Long?> = _restEndsAt.asStateFlow()
    private val _restSeconds = MutableStateFlow(90)
    val restSeconds: StateFlow<Int> = _restSeconds.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var restJob: Job? = null
    private var lastFiredRestEnd = 0L
    private var stepsAtStart: Long? = null
    private var lastBeat = 0L
    private var lastNotified = 0L
    private lateinit var app: Context

    val isRunning: Boolean get() = _session.value != null

    fun init(context: Context) {
        app = context.applicationContext
        _restSeconds.value = app.getSharedPreferences("aravia", Context.MODE_PRIVATE).getInt("restSeconds", 90)
        ensureChannels(app)
    }

    private fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Treino em andamento", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Tempo, batimentos e descanso do treino aberto."
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(REST_CHANNEL, "Fim do descanso", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Toca e vibra quando o descanso entre séries acaba, mesmo com a tela bloqueada."
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 260, 120, 260)
            val sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
        })
    }

    fun start(kind: TrainingSession.Kind) {
        if (_session.value != null) return
        _session.value = TrainingSession(kind = kind, start = System.currentTimeMillis())
        _bpm.value = null
        _restEndsAt.value = null
        stepsAtStart = null
        lastBeat = 0
        notifyNow()
        // The watch's live heart rate for the whole workout, when a watch is set up here.
        BandVault.band.value?.let { band ->
            BandLink.liveWindowMs = 6 * 3600_000L
            BandLink.start(band, BandLink.Purpose.LIVE)
        }
    }

    /** Live numbers from the watch: heart rate and the day's step counter. One beat is kept every 5 s. */
    fun onLive(heartRate: Int, steps: Long) {
        val s = _session.value ?: return
        val first = stepsAtStart ?: steps
        stepsAtStart = first
        var next = s.copy(steps = (steps - minOf(steps, first)).toInt())
        if (heartRate > 30) {
            _bpm.value = heartRate
            val now = System.currentTimeMillis()
            if (now - lastBeat >= 5_000) {
                next = next.copy(heart = next.heart + TrainingSession.Beat(now, heartRate))
                lastBeat = now
            }
        }
        _session.value = next
        notify(force = false)
    }

    // Gym

    fun addEntry(exercise: String) {
        val s = _session.value ?: return
        _session.value = s.copy(entries = s.entries + TrainingSession.Entry(exercise = exercise))
        notifyNow()
    }

    fun removeEntry(id: String) {
        val s = _session.value ?: return
        _session.value = s.copy(entries = s.entries.filterNot { it.id == id })
    }

    fun logSet(entryId: String, reps: Int, kg: Double) {
        val s = _session.value ?: return
        _session.value = s.copy(entries = s.entries.map {
            if (it.id == entryId) it.copy(sets = it.sets + TrainingSession.WorkSet(reps = reps, kg = kg, doneAt = System.currentTimeMillis())) else it
        })
        startRest()
    }

    fun removeSet(entryId: String, setId: String) {
        val s = _session.value ?: return
        _session.value = s.copy(entries = s.entries.map { if (it.id == entryId) it.copy(sets = it.sets.filterNot { x -> x.id == setId }) else it })
    }

    fun setRestSeconds(seconds: Int) {
        _restSeconds.value = seconds
        app.getSharedPreferences("aravia", Context.MODE_PRIVATE).edit().putInt("restSeconds", seconds).apply()
    }

    fun startRest() {
        restJob?.cancel()
        cancelRestAlarm()
        val ends = System.currentTimeMillis() + _restSeconds.value * 1000L
        _restEndsAt.value = ends
        notifyNow()
        scheduleRestAlarm(ends, _session.value?.let { detail(it) })
        restJob = scope.launch {
            delay((ends - System.currentTimeMillis()).coerceAtLeast(0L))
            if (_restEndsAt.value == ends) {
                _restEndsAt.value = null
                cancelRestAlarm()
                fireRestDone(app, ends, _session.value?.let { detail(it) })
                notifyNow()
            }
        }
    }

    fun skipRest() {
        restJob?.cancel()
        cancelRestAlarm()
        _restEndsAt.value = null
        notifyNow()
    }

    /** Called by [RestAlarmReceiver] when the timer finishes while screen is off or backgrounded. */
    fun onRestAlarm(ctx: Context, endsAt: Long, detailText: String?) {
        if (::app.isInitialized) {
            restJob?.cancel()
            if (_restEndsAt.value == endsAt) {
                _restEndsAt.value = null
                notifyNow()
            }
        }
        fireRestDone(ctx, endsAt, detailText)
    }

    private fun scheduleRestAlarm(endsAt: Long, detailText: String?) {
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = restAlarmIntent(app, endsAt, detailText)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endsAt, pi)
            }
        }
    }

    private fun cancelRestAlarm() {
        if (!::app.isInitialized) return
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(restAlarmIntent(app, 0L, null))
    }

    private fun restAlarmIntent(ctx: Context, endsAt: Long, detailText: String?): PendingIntent {
        val intent = Intent(ctx, RestAlarmReceiver::class.java).apply {
            putExtra(RestAlarmReceiver.EXTRA_ENDS_AT, endsAt)
            detailText?.let { putExtra(RestAlarmReceiver.EXTRA_DETAIL, it) }
        }
        return PendingIntent.getBroadcast(
            ctx,
            REST_ALARM_REQ,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    fun setTypedDistance(meters: Double?) {
        _session.value = _session.value?.copy(typedDistanceMeters = meters)
    }

    // End

    /** Keeps the workout (unless it was started by mistake and ended at once) and sends it to
     *  Strava when that is on. */
    fun finish() {
        val s = _session.value ?: return
        val done = s.copy(end = System.currentTimeMillis())
        if (done.durationMs >= 60_000 || done.heart.isNotEmpty() || done.entries.isNotEmpty()) {
            TrainingStore.add(done)
            scope.launch { Strava.sendNew() }
        }
        close()
    }

    fun discard() = close()

    private fun close() {
        restJob?.cancel()
        cancelRestAlarm()
        if (BandLink.purpose == BandLink.Purpose.LIVE) BandLink.stop()
        _session.value = null
        _bpm.value = null
        _restEndsAt.value = null
        NotificationManagerCompat.from(app).cancel(NOTIFICATION)
    }

    // Heart zones

    /** 0 below 50% of the maximum heart rate, then zones 1 to 5 in steps of 10%. Without a
     *  profile on Android yet, the maximum is 190 bpm. */
    fun zone(bpm: Int?, maxHr: Double = 190.0): Int {
        bpm ?: return 0
        return ((bpm / maxHr - 0.4) * 10).toInt().coerceIn(0, 5)
    }

    val zoneNames = listOf("Repouso", "Muito leve", "Leve", "Moderado", "Forte", "Máximo")

    // Notification

    fun detail(s: TrainingSession): String = when (s.kind) {
        TrainingSession.Kind.GYM -> s.entries.lastOrNull()?.let { "${it.exercise} · série ${it.sets.size + 1}" } ?: "Escolha o primeiro exercício"
        else -> String.format(Locale("pt", "BR"), "%.2f km · %d passos", s.distanceMeters() / 1000, s.steps)
    }

    private fun notifyNow() = notify(force = true)

    /** Heart rate refreshes it at most every 5 s; sets and rest at once. */
    private fun notify(force: Boolean) {
        val s = _session.value ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastNotified < 5_000) return
        lastNotified = now
        val nm = NotificationManagerCompat.from(app)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val rest = _restEndsAt.value
        val heart = _bpm.value?.let { "$it bpm · ${zoneNames[zone(it)]}" }
        val b = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentTitle(if (rest != null) "Descanso · ${s.kind.title}" else s.kind.title)
            .setContentText(listOfNotNull(heart, detail(s)).joinToString(" · "))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setContentIntent(open)
            .setUsesChronometer(true)
        if (rest != null) {
            b.setWhen(rest)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) b.setChronometerCountDown(true)
        } else {
            b.setWhen(s.start)
        }
        try { nm.notify(NOTIFICATION, b.build()) } catch (_: SecurityException) {}
    }

    @Synchronized
    private fun fireRestDone(ctx: Context, endsAt: Long, detailText: String?) {
        if (endsAt != 0L && endsAt == lastFiredRestEnd) return
        if (endsAt != 0L) lastFiredRestEnd = endsAt
        ensureChannels(ctx)
        val vibrator = ctx.getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 260, 120, 260), -1))
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            ctx,
            1,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, REST_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentTitle("Descanso acabou")
            .setContentText(detailText ?: "Hora da próxima série")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_VIBRATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(25_000)
            .build()
        try { nm.notify(REST_DONE, n) } catch (_: SecurityException) {}
    }
}
