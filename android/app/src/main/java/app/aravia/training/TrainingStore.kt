package app.aravia.training

import android.content.Context
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A workout logged in Aravia, the same as on iOS (TrainingStore.swift): treadmill, gym or other.
 * It keeps the heart rate the watch sent live and, for the gym, every exercise, set and load.
 * Times are epoch milliseconds.
 */
data class TrainingSession(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
    val start: Long,
    val end: Long? = null,
    val entries: List<Entry> = emptyList(),
    val heart: List<Beat> = emptyList(),
    /** Steps the watch counted during the workout. */
    val steps: Int = 0,
    /** Distance typed in from the treadmill's display, which beats the step estimate. */
    val typedDistanceMeters: Double? = null,
) {
    enum class Kind(val title: String) { TREADMILL("Esteira"), GYM("Musculação"), OTHER("Treino livre") }

    data class WorkSet(val id: String = UUID.randomUUID().toString(), val reps: Int, val kg: Double, val doneAt: Long)
    data class Entry(val id: String = UUID.randomUUID().toString(), val exercise: String, val sets: List<WorkSet> = emptyList())
    data class Beat(val t: Long, val bpm: Int)

    val durationMs: Long get() = (end ?: System.currentTimeMillis()) - start
    val avgBpm: Int? get() = if (heart.isEmpty()) null else heart.sumOf { it.bpm } / heart.size
    val maxBpm: Int? get() = heart.maxOfOrNull { it.bpm }
    val setCount: Int get() = entries.sumOf { it.sets.size }
    val volumeKg: Double get() = entries.sumOf { e -> e.sets.sumOf { it.reps * it.kg } }

    /** Typed distance, else steps times a stride of 0.415 × height (a rough rule, shown as an estimate). */
    fun distanceMeters(heightCm: Double? = null): Double =
        typedDistanceMeters ?: (steps * (heightCm ?: 170.0) * 0.415 / 100)

    fun json(): JSONObject = JSONObject()
        .put("id", id).put("kind", kind.name).put("start", start).put("end", end ?: JSONObject.NULL)
        .put("steps", steps).put("typedDistanceMeters", typedDistanceMeters ?: JSONObject.NULL)
        .put("entries", JSONArray(entries.map { e ->
            JSONObject().put("id", e.id).put("exercise", e.exercise).put("sets", JSONArray(e.sets.map { s ->
                JSONObject().put("id", s.id).put("reps", s.reps).put("kg", s.kg).put("doneAt", s.doneAt)
            }))
        }))
        .put("heart", JSONArray(heart.map { JSONArray().put(it.t).put(it.bpm) }))

    companion object {
        fun from(o: JSONObject): TrainingSession? = runCatching {
            val entries = o.optJSONArray("entries") ?: JSONArray()
            val heart = o.optJSONArray("heart") ?: JSONArray()
            TrainingSession(
                id = o.getString("id"),
                kind = Kind.valueOf(o.getString("kind")),
                start = o.getLong("start"),
                end = if (o.isNull("end")) null else o.getLong("end"),
                steps = o.optInt("steps"),
                typedDistanceMeters = if (o.isNull("typedDistanceMeters")) null else o.getDouble("typedDistanceMeters"),
                entries = (0 until entries.length()).map { i ->
                    val e = entries.getJSONObject(i)
                    val sets = e.getJSONArray("sets")
                    Entry(e.getString("id"), e.getString("exercise"), (0 until sets.length()).map { j ->
                        val s = sets.getJSONObject(j)
                        WorkSet(s.getString("id"), s.getInt("reps"), s.getDouble("kg"), s.getLong("doneAt"))
                    })
                },
                heart = (0 until heart.length()).map { i -> heart.getJSONArray(i).let { Beat(it.getLong(0), it.getInt(1)) } },
            )
        }.getOrNull()
    }
}

/** The exercise list, the same as on iOS. */
object ExerciseCatalog {
    val groups: List<Pair<String, List<String>>> = listOf(
        "Peito" to listOf("Supino reto", "Supino inclinado", "Supino com halteres", "Crucifixo", "Crossover", "Flexão de braço"),
        "Costas" to listOf("Puxada frontal", "Remada curvada", "Remada baixa", "Remada unilateral", "Barra fixa", "Levantamento terra"),
        "Pernas" to listOf("Agachamento livre", "Leg press", "Cadeira extensora", "Mesa flexora", "Afundo", "Stiff", "Elevação pélvica", "Panturrilha"),
        "Ombros" to listOf("Desenvolvimento", "Elevação lateral", "Elevação frontal", "Crucifixo invertido", "Encolhimento"),
        "Braços" to listOf("Rosca direta", "Rosca martelo", "Rosca scott", "Tríceps pulley", "Tríceps testa", "Mergulho"),
        "Core" to listOf("Prancha", "Abdominal", "Abdominal infra", "Russian twist"),
    )
}

/** The workouts logged on this phone, as JSON in the app's files (aravia-training.json). */
object TrainingStore {
    private val _sessions = MutableStateFlow<List<TrainingSession>>(emptyList())
    val sessions: StateFlow<List<TrainingSession>> = _sessions.asStateFlow()
    private val _custom = MutableStateFlow<List<String>>(emptyList())
    val customExercises: StateFlow<List<String>> = _custom.asStateFlow()
    private var file: File? = null

    fun init(context: Context) {
        val f = File(context.filesDir, "aravia-training.json")
        file = f
        if (!f.exists()) return
        runCatching {
            val o = JSONObject(f.readText())
            val list = o.optJSONArray("sessions") ?: JSONArray()
            _sessions.value = (0 until list.length()).mapNotNull { TrainingSession.from(list.getJSONObject(it)) }
            val custom = o.optJSONArray("customExercises") ?: JSONArray()
            _custom.value = (0 until custom.length()).map { custom.getString(it) }
        }
    }

    private fun save() {
        val f = file ?: return
        val o = JSONObject().put("sessions", JSONArray(_sessions.value.map { it.json() })).put("customExercises", JSONArray(_custom.value))
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    fun add(s: TrainingSession) { _sessions.value = listOf(s) + _sessions.value; save() }

    fun delete(id: String) { _sessions.value = _sessions.value.filterNot { it.id == id }; save() }

    fun addExercise(name: String) {
        val clean = name.trim()
        if (clean.isEmpty() || clean in _custom.value || ExerciseCatalog.groups.any { clean in it.second }) return
        _custom.value = _custom.value + clean
        save()
    }

    /** The last set done of an exercise, to fill in the next one. */
    fun lastSet(exercise: String): TrainingSession.WorkSet? {
        for (s in _sessions.value) s.entries.lastOrNull { it.exercise == exercise }?.sets?.lastOrNull()?.let { return it }
        return null
    }
}
