package app.aravia.arara

import android.content.Context
import android.content.SharedPreferences
import app.aravia.health.HealthHistory
import app.aravia.routes.RouteStore
import app.aravia.training.TrainingSession
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Guilt-free routine tracking for the Arara (docs/PRODUTO.md §3 Onda 1.3):
 * - Counts active days inside the current week (Mon–Sun) against a gentle weekly goal instead of
 *   punishing missed individual days.
 * - Offers an explicit "Arara descansando" pause mode (recovery, illness/injury, travel) that
 *   suspends goal pressure without ever reducing the Arara's stage or XP.
 */
object AraraCare {
    enum class RestReason(val title: String, val message: String) {
        RECOVERY("Recuperação", "Dia de recuperar as energias. A arara descansa no ninho com você."),
        ILLNESS("Doença ou lesão", "Cuide de você primeiro. A arara guarda seu progresso até você voltar."),
        TRAVEL("Viagem", "Aproveite a viagem. Quando voltar, a rotina retoma de onde parou."),
    }

    data class State(
        val resting: Boolean = false,
        val reason: RestReason = RestReason.RECOVERY,
        val restSince: Long? = null,
        val weeklyGoal: Int = 3,
    )

    private lateinit var prefs: SharedPreferences
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("aravia", Context.MODE_PRIVATE)
        val reason = runCatching { RestReason.valueOf(prefs.getString("araraRestReason", "RECOVERY")!!) }
            .getOrDefault(RestReason.RECOVERY)
        val since = prefs.getLong("araraRestSince", 0L).takeIf { it > 0L }
        _state.value = State(
            resting = prefs.getBoolean("araraResting", false),
            reason = reason,
            restSince = since,
            weeklyGoal = prefs.getInt("araraWeeklyGoal", 3).coerceIn(2, 6),
        )
    }

    fun startRest(reason: RestReason) {
        val now = System.currentTimeMillis()
        if (::prefs.isInitialized) {
            prefs.edit()
                .putBoolean("araraResting", true)
                .putString("araraRestReason", reason.name)
                .putLong("araraRestSince", now)
                .apply()
        }
        _state.value = _state.value.copy(resting = true, reason = reason, restSince = now)
    }

    fun endRest() {
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean("araraResting", false).remove("araraRestSince").apply()
        }
        _state.value = _state.value.copy(resting = false, restSince = null)
    }

    fun setWeeklyGoal(days: Int) {
        val clamped = days.coerceIn(2, 6)
        if (::prefs.isInitialized) prefs.edit().putInt("araraWeeklyGoal", clamped).apply()
        _state.value = _state.value.copy(weeklyGoal = clamped)
    }

    /** Days active in the current Monday–Sunday week: ≥ 5,000 steps OR a logged workout OR a GPS route. */
    fun activeDaysThisWeek(
        history: HealthHistory,
        sessions: List<TrainingSession>,
        routes: List<RouteStore.Route>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val today = LocalDate.now(zone)
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val active = mutableSetOf<LocalDate>()
        for ((day, d) in history.days) {
            if (!day.isBefore(monday) && !day.isAfter(today) && (d.steps ?: 0) >= 5000) {
                active += day
            }
        }
        for (s in sessions) {
            val d = Instant.ofEpochMilli(s.start).atZone(zone).toLocalDate()
            if (!d.isBefore(monday) && !d.isAfter(today)) active += d
        }
        for (r in routes) {
            val d = Instant.ofEpochSecond(r.ts).atZone(zone).toLocalDate()
            if (!d.isBefore(monday) && !d.isAfter(today)) active += d
        }
        return active.size
    }
}
