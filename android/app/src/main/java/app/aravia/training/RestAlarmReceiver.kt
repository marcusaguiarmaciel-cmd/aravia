package app.aravia.training

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires when a gym rest timer finishes while the phone screen is locked or another app is in the
 * foreground, so the alert rings and vibrates reliably instead of waiting for the coroutine to wake.
 */
class RestAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val endsAt = intent.getLongExtra(EXTRA_ENDS_AT, 0L)
        val detail = intent.getStringExtra(EXTRA_DETAIL)
        WorkoutController.onRestAlarm(context.applicationContext, endsAt, detail)
    }

    companion object {
        const val EXTRA_ENDS_AT = "rest_ends_at"
        const val EXTRA_DETAIL = "rest_detail"
    }
}
