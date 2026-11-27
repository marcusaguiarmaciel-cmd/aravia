package app.aravia.band

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.aravia.MainActivity
import app.aravia.R

/**
 * Keeps the app alive while a session with the watch runs (a sync, a workout's live heart rate,
 * or Stay connected), so Android doesn't stop it in the background. A foreground service of the
 * "connected device" type, with its own quiet notification; it ends with the session.
 */
class BandService : Service() {
    companion object {
        private const val CHANNEL = "band"
        private const val ID = 43

        fun start(context: Context) {
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, BandService::class.java)) }
                .onFailure { BandLink.note("service not started: ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) { context.stopService(Intent(context, BandService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Conexão com o relógio", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val detail = when (BandLink.purpose) {
            BandLink.Purpose.LIVE -> "Batimentos ao vivo do treino"
            BandLink.Purpose.HOLD -> "Fica conectado ativo (Encontrar celular)"
            BandLink.Purpose.BIND_PROBE, is BandLink.Purpose.Bind -> "Pareando com o relógio"
            is BandLink.Purpose.Send -> "Enviando comando ao relógio"
            BandLink.Purpose.SYNC -> "Sincronizando"
        }
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_workout)
            .setContentTitle("Conectado ao relógio")
            .setContentText(detail)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, ID, n, type)
        } catch (e: Exception) {
            // Android 14 refuses the type without the nearby-devices permission: run without it.
            BandLink.note("foreground refused: ${e.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
