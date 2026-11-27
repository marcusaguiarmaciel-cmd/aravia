package app.aravia.routes

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.aravia.protocol.GpsTrack
import app.aravia.ui.Aravia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Automatic home-privacy zone for routes shared outside the phone (Story videos, transparent
 * overlays, exported GPX files and Strava uploads). The complete track always remains untouched
 * on the device.
 */
object RoutePrivacy {
    const val DEFAULT_METERS = 500
    val options = listOf(0, 200, 500, 1000)

    sealed class Outcome {
        data class Ready(val track: GpsTrack, val trimmedMeters: Int) : Outcome()
        data class TooShort(val original: GpsTrack, val radiusMeters: Int) : Outcome()
    }

    private lateinit var prefs: SharedPreferences
    private val _meters = MutableStateFlow(DEFAULT_METERS)
    val meters: StateFlow<Int> = _meters.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("aravia", Context.MODE_PRIVATE)
        val saved = prefs.getInt("routePrivacyMeters", DEFAULT_METERS)
        _meters.value = if (saved in options) saved else DEFAULT_METERS
    }

    fun set(radiusMeters: Int) {
        val clean = if (radiusMeters in options) radiusMeters else DEFAULT_METERS
        if (::prefs.isInitialized) prefs.edit().putInt("routePrivacyMeters", clean).apply()
        _meters.value = clean
    }

    fun label(radiusMeters: Int): String = when (radiusMeters) {
        0 -> "Desligado"
        1000 -> "1 km"
        else -> "$radiusMeters m"
    }

    fun apply(track: GpsTrack, radiusMeters: Int = _meters.value): Outcome {
        if (radiusMeters <= 0) return Outcome.Ready(track, 0)
        val trimmed = track.trimmedForPrivacy(radiusMeters.toDouble())
        return if (trimmed.points.size >= 2) Outcome.Ready(trimmed, radiusMeters)
        else Outcome.TooShort(track, radiusMeters)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrivacyZoneRow(modifier: Modifier = Modifier, compact: Boolean = false) {
    val c = Aravia.colors
    val current by RoutePrivacy.meters.collectAsState()
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (current > 0) "Proteção da casa: ocultar ${RoutePrivacy.label(current)} no início e no fim"
            else "Proteção da casa: desligada (rota inteira ao compartilhar)",
            fontWeight = FontWeight.SemiBold,
            fontSize = if (compact) 13.sp else 14.sp,
            color = c.textPrimary,
        )
        if (!compact) {
            Text(
                "A rota completa continua salva aqui; só o trecho compartilhado (Story, GPX e Strava) é recortado.",
                fontSize = 12.sp,
                color = c.textSecondary,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoutePrivacy.options.forEach { m ->
                FilterChip(
                    selected = current == m,
                    onClick = { RoutePrivacy.set(m) },
                    label = { Text(RoutePrivacy.label(m)) },
                )
            }
        }
    }
}
