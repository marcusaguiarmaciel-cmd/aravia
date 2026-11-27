package app.aravia.band

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import app.aravia.MainActivity
import app.aravia.R
import app.aravia.ui.Aravia
import app.aravia.ui.Barlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Handles the watch's Find Phone request (Command{2,17}, docs/STAY-CONNECTED.md): rings on the
 * alarm stream, vibrates, posts a high-priority notification with a stop action, and shows a
 * full-screen overlay until the user stops it or 120 s pass.
 */
object FindPhone {
    const val EXTRA_STOP = "aravia.stop_find_phone"
    private const val CHANNEL = "find_phone"
    private const val NOTIF_ID = 44
    private const val RING_LIMIT_MS = 120_000L

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var serial = 0

    private val _ringingFor = MutableStateFlow<String?>(null)
    val ringingFor: StateFlow<String?> = _ringingFor.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        BandLink.onFindPhone = { bandName, ring -> onRequest(bandName, ring) }
    }

    fun onRequest(bandName: String, ring: Boolean) {
        if (!ring) { endRinging(tellBand = false); return }
        if (_ringingFor.value != null) return
        _ringingFor.value = bandName
        startSound()
        startVibration()
        postNotification(bandName)
        val token = ++serial
        main.postDelayed({
            if (serial == token && _ringingFor.value != null) {
                BandLink.note("ringing: stopped after ${RING_LIMIT_MS / 1000} s")
                endRinging(tellBand = true)
            }
        }, RING_LIMIT_MS)
    }

    /** Stops ringing from the UI or the notification, and tells the watch to dismiss its screen. */
    fun stop() = endRinging(tellBand = true)

    /** Visual-only trigger for emulator screenshots (Demo.kt); makes no sound. */
    fun demoShow(bandName: String) { _ringingFor.value = bandName }

    private fun endRinging(tellBand: Boolean) {
        if (_ringingFor.value == null) return
        _ringingFor.value = null
        serial++
        runCatching { ringtone?.stop() }
        ringtone = null
        runCatching { vibrator()?.cancel() }
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
        if (tellBand) BandLink.stopFindPhone()
    }

    private fun startSound() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val r = RingtoneManager.getRingtone(app, uri) ?: return
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            r.isLooping = true
            r.play()
            ringtone = r
            BandLink.note("ringing")
        }.onFailure { BandLink.note("ringing: audio failed (${it.javaClass.simpleName})") }
    }

    private fun startVibration() {
        runCatching {
            val v = vibrator() ?: return
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 300, 500, 700), 0))
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrator(): Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        app.getSystemService(Vibrator::class.java)
    }

    private fun postNotification(bandName: String) {
        runCatching {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Encontrar celular", NotificationManager.IMPORTANCE_HIGH))
            val open = PendingIntent.getActivity(app, 1, Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getActivity(app, 2, Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                putExtra(EXTRA_STOP, true)
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_workout)
                .setContentTitle("Procurando este celular")
                .setContentText("$bandName está fazendo este celular tocar")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setContentIntent(open)
                .addAction(0, "Parar de tocar", stop)
                .build()
            nm.notify(NOTIF_ID, n)
        }
    }
}

@Composable
fun FindPhoneOverlay(bandName: String, onStop: () -> Unit) {
    val c = Aravia.colors
    Column(
        Modifier.fillMaxSize().background(c.canvas).padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Icon(Icons.Filled.NotificationsActive, null, tint = c.action, modifier = Modifier.size(84.dp))
            Text(
                "$bandName está procurando este celular",
                fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 28.sp,
                color = c.textPrimary, textAlign = TextAlign.Center,
            )
            Text(
                "Toque abaixo para silenciar e avisar o relógio.",
                fontFamily = Barlow, fontSize = 15.sp, color = c.textSecondary, textAlign = TextAlign.Center,
            )
        }
        Button(
            onClick = onStop,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            colors = ButtonDefaults.buttonColors(containerColor = c.action, contentColor = c.onAction),
        ) {
            Text("Parar de tocar", fontFamily = Barlow, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}
