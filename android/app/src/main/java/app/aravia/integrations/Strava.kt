package app.aravia.integrations

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.aravia.BuildConfig
import app.aravia.band.KeystoreBox
import app.aravia.routes.RouteStore
import app.aravia.training.TrainingSession
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Strava sign-in and upload with no SDK, the same flow as on iOS (docs/STRAVA.md):
 * - Sign-in is OAuth 2 in the browser, back through aravia://localhost/strava.
 * - A GPS route goes as a GPX upload, with its map.
 * - A workout logged in Aravia (treadmill, gym, other) goes as a manual activity, with its sets
 *   and heart rate in the description.
 *
 * The client ID and secret come from the build (the repository's secrets); without them Strava is
 * hidden. The refresh token is sealed with a Keystore key and stays on this phone.
 */
object Strava {
    data class Sent(val start: Long, val activityId: Long)

    data class State(
        val athlete: String? = null,
        val busy: Boolean = false,
        val status: String? = null,
        val error: String? = null,
        val automatic: Boolean = true,
        val sendRoutes: Boolean = true,
        val sendWorkouts: Boolean = true,
        val sent: List<Sent> = emptyList(),
    ) {
        val connected: Boolean get() = athlete != null
    }

    class StravaException(message: String) : Exception(message)

    private const val REDIRECT = "aravia://localhost/strava"
    private val box = KeystoreBox("aravia.strava")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private lateinit var prefs: android.content.SharedPreferences
    private var accessToken: String? = null
    private var accessExpiry = 0L

    val isConfigured: Boolean get() = BuildConfig.STRAVA_CLIENT_ID.isNotBlank() && BuildConfig.STRAVA_CLIENT_SECRET.isNotBlank()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("aravia.strava", Context.MODE_PRIVATE)
        val sent = runCatching {
            val a = JSONArray(prefs.getString("sent", "[]"))
            (0 until a.length()).map { a.getJSONArray(it).let { x -> Sent(x.getLong(0), x.getLong(1)) } }
        }.getOrDefault(emptyList())
        val athlete = if (prefs.getString("refresh", null) != null) prefs.getString("athlete", null) else null
        _state.value = State(
            athlete = athlete,
            automatic = prefs.getBoolean("auto", true),
            sendRoutes = prefs.getBoolean("sendRoutes", true),
            sendWorkouts = prefs.getBoolean("sendWorkouts", true),
            sent = sent,
        )
        app.aravia.band.BandLink.onFinish = { outcome ->
            if (app.aravia.band.BandLink.purpose == app.aravia.band.BandLink.Purpose.SYNC &&
                outcome !is app.aravia.band.BandLink.Outcome.Failed
            ) {
                scope.launch { sendNew() }
            }
        }
    }

    // Sign-in

    /** Opens Strava's authorization page; the answer comes back to MainActivity as a link. */
    fun signIn(context: Context) {
        if (!isConfigured) { fail("Este build não tem as chaves do Strava."); return }
        val nonce = UUID.randomUUID().toString()
        prefs.edit().putString("oauthState", nonce).apply()
        val url = Uri.parse("https://www.strava.com/oauth/mobile/authorize").buildUpon()
            .appendQueryParameter("client_id", BuildConfig.STRAVA_CLIENT_ID)
            .appendQueryParameter("redirect_uri", REDIRECT)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("approval_prompt", "auto")
            .appendQueryParameter("scope", "read,activity:write")
            .appendQueryParameter("state", nonce)
            .build()
        _state.value = _state.value.copy(error = null)
        context.startActivity(Intent(Intent.ACTION_VIEW, url))
    }

    fun isCallback(uri: Uri?): Boolean = uri?.scheme == "aravia" && uri.host == "localhost" && uri.path == "/strava"

    /** The link Strava sends back: checks it and trades the code for tokens. */
    suspend fun finishSignIn(uri: Uri) {
        val expected = prefs.getString("oauthState", null)
        prefs.edit().remove("oauthState").apply()
        if (uri.getQueryParameter("error") == "access_denied") return
        val code = uri.getQueryParameter("code")
        if (expected == null || uri.getQueryParameter("state") != expected || code == null) {
            fail("O Strava não devolveu a autorização."); return
        }
        if ("activity:write" !in (uri.getQueryParameter("scope") ?: "").split(",")) {
            fail("Deixe marcada a permissão de enviar atividades e tente de novo."); return
        }
        _state.value = _state.value.copy(busy = true, error = null)
        try {
            val json = token(mapOf(
                "client_id" to BuildConfig.STRAVA_CLIENT_ID, "client_secret" to BuildConfig.STRAVA_CLIENT_SECRET,
                "code" to code, "grant_type" to "authorization_code",
            ))
            val a = json.optJSONObject("athlete")
            val name = listOfNotNull(a?.optString("firstname"), a?.optString("lastname"))
                .map { it.trim() }.filter { it.isNotEmpty() && it != "null" }.joinToString(" ").ifEmpty { "Conta Strava" }
            prefs.edit().putString("athlete", name).apply()
            // Only workouts from now on go by themselves; older ones can be sent one by one.
            if (!prefs.contains("connectedAt")) prefs.edit().putLong("connectedAt", System.currentTimeMillis() / 1000).apply()
            _state.value = _state.value.copy(athlete = name, busy = false, status = null)
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, error = e.message ?: "O Strava recusou o login.")
        }
    }

    fun signOut() {
        val token = accessToken
        if (token != null) scope.launch { runCatching { call("POST", "https://www.strava.com/oauth/deauthorize", token, null, null) } }
        prefs.edit().remove("refresh").remove("athlete").remove("connectedAt").apply()
        accessToken = null
        _state.value = _state.value.copy(athlete = null, status = null, error = null)
    }

    fun setAutomatic(on: Boolean) {
        prefs.edit().putBoolean("auto", on).apply()
        _state.value = _state.value.copy(automatic = on)
    }

    fun setSendRoutes(on: Boolean) {
        prefs.edit().putBoolean("sendRoutes", on).apply()
        _state.value = _state.value.copy(sendRoutes = on)
    }

    fun setSendWorkouts(on: Boolean) {
        prefs.edit().putBoolean("sendWorkouts", on).apply()
        _state.value = _state.value.copy(sendWorkouts = on)
    }

    // What was sent

    fun activityId(startSeconds: Long): Long? = _state.value.sent.firstOrNull { abs(it.start - startSeconds) < 120 }?.activityId

    private fun remember(s: Sent) {
        val list = (_state.value.sent + s).takeLast(2000)
        prefs.edit().putString("sent", JSONArray(list.map { JSONArray().put(it.start).put(it.activityId) }).toString()).apply()
        _state.value = _state.value.copy(sent = list)
    }

    // Sending

    /** Sends what was done since Strava was connected and is not there yet: Aravia workouts and
     *  the watch's GPS routes. Runs after a workout ends and after each BLE sync. */
    suspend fun sendNew() {
        val s = _state.value
        if (!s.connected || !s.automatic || s.busy) return
        val since = prefs.getLong("connectedAt", Long.MAX_VALUE)
        val sessions = if (s.sendWorkouts) app.aravia.training.TrainingStore.sessions.value
            .filter { it.end != null && it.start / 1000 >= since && activityId(it.start / 1000) == null } else emptyList()
        val routes = if (s.sendRoutes) RouteStore.routes.value
            .filter { it.ts >= since && activityId(it.track?.start ?: it.ts) == null } else emptyList()
        if (sessions.isEmpty() && routes.isEmpty()) return
        _state.value = s.copy(busy = true, error = null)
        var done = 0
        try {
            for (r in routes) { sendRoute(r); done++ }
            for (x in sessions) { sendSession(x); done++ }
            _state.value = _state.value.copy(busy = false, status = if (done == 1) "1 treino enviado ao Strava." else "$done treinos enviados ao Strava.")
        } catch (e: Exception) {
            _state.value = _state.value.copy(busy = false, error = "Não deu para enviar: ${e.message}")
        }
    }

    /** One workout now, from its screen. Returns the activity id. */
    suspend fun sendSessionNow(x: TrainingSession): Long = busy { sendSession(x) }

    suspend fun sendRouteNow(r: RouteStore.Route): Long = busy { sendRoute(r) }

    private suspend fun busy(work: suspend () -> Long): Long {
        _state.value = _state.value.copy(busy = true, error = null)
        try { return work() } finally { _state.value = _state.value.copy(busy = false) }
    }

    private suspend fun sendSession(x: TrainingSession): Long {
        val start = x.start / 1000
        activityId(start)?.let { return it }
        val (type, trainer) = when (x.kind) {
            TrainingSession.Kind.TREADMILL -> "Run" to true
            TrainingSession.Kind.GYM -> "WeightTraining" to true
            TrainingSession.Kind.OTHER -> "Workout" to true
        }
        val fields = mutableMapOf(
            "name" to x.kind.title,
            "sport_type" to type,
            "start_date_local" to localTime(x.start),
            "elapsed_time" to maxOf(1L, x.durationMs / 1000).toString(),
            "description" to description(x),
            "trainer" to if (trainer) "1" else "0",
        )
        if (x.kind == TrainingSession.Kind.TREADMILL) {
            val d = x.distanceMeters()
            if (d > 0) fields["distance"] = String.format(Locale.US, "%.0f", d)
        }
        val json = call("POST", "https://www.strava.com/api/v3/activities", validToken(), form(fields), "application/x-www-form-urlencoded")
        val id = json.optLong("id", 0).takeIf { it > 0 } ?: throw StravaException("O Strava não criou a atividade.")
        remember(Sent(start, id))
        return id
    }

    private suspend fun sendRoute(r: RouteStore.Route): Long {
        val rawTrack = withContext(Dispatchers.IO) { r.track } ?: throw StravaException("Esta rota não tem pontos legíveis.")
        val track = when (val p = app.aravia.routes.RoutePrivacy.apply(rawTrack)) {
            is app.aravia.routes.RoutePrivacy.Outcome.Ready -> p.track
            is app.aravia.routes.RoutePrivacy.Outcome.TooShort ->
                throw StravaException("Esta rota é menor que o raio de privacidade (${app.aravia.routes.RoutePrivacy.label(p.radiusMeters)}). Reduza o raio na tela da rota para enviar.")
        }
        val start = rawTrack.start ?: r.ts
        activityId(start)?.let { return it }
        val token = validToken()
        val key = "aravia-$start"
        val boundary = "aravia-${UUID.randomUUID()}"
        val body = StringBuilder()
        fun field(name: String, value: String) = body.append("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n")
        field("data_type", "gpx")
        field("name", r.title)
        field("description", "Enviado pelo Aravia")
        field("external_id", key)
        body.append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$key.gpx\"\r\nContent-Type: application/gpx+xml\r\n\r\n")
        body.append(track.gpx(r.title)).append("\r\n--$boundary--\r\n")
        var json = call("POST", "https://www.strava.com/api/v3/uploads", token, body.toString().toByteArray(), "multipart/form-data; boundary=$boundary")
        var id: Long? = null
        for (i in 0 until 20) {
            json.optLong("activity_id", 0).takeIf { it > 0 }?.let { id = it }
            if (id != null) break
            val err = json.optString("error", "").takeIf { it.isNotBlank() && it != "null" }
            if (err != null) {
                id = duplicateId(err) ?: throw StravaException("O Strava recusou o arquivo: $err")
                break
            }
            val upload = json.optLong("id", 0).takeIf { it > 0 } ?: break
            delay(2000)
            json = call("GET", "https://www.strava.com/api/v3/uploads/$upload", token, null, null)
        }
        val activity = id ?: throw StravaException("O Strava ainda está processando. Tente de novo daqui a pouco.")
        // A GPX carries no sport, so it is set after the upload.
        val (type, trainer) = sport(RouteStore.sportName(r.subtype))
        runCatching {
            call("PUT", "https://www.strava.com/api/v3/activities/$activity", token,
                JSONObject().put("sport_type", type).put("trainer", trainer).toString().toByteArray(), "application/json")
        }
        remember(Sent(start, activity))
        return activity
    }

    /** "… duplicate of <a href='/activities/123'>…" → 123. */
    fun duplicateId(error: String): Long? {
        if ("duplicate" !in error) return null
        val i = error.indexOf("activities/").takeIf { it >= 0 } ?: return null
        return error.substring(i + "activities/".length).takeWhile { it.isDigit() }.toLongOrNull()
    }

    /** The band's sport names as Strava sport types, the same table as on iOS (StravaSport). */
    fun sport(name: String): Pair<String, Boolean> = when (name) {
        "outdoor_running" -> "Run" to false
        "trail_running" -> "TrailRun" to false
        "treadmill" -> "Run" to true
        "walking", "outdoor_walking", "outdoor_walking_v1", "outdoor_walking_v2" -> "Walk" to false
        "indoor_walking" -> "Walk" to true
        "hiking", "trekking" -> "Hike" to false
        "outdoor_cycling", "outdoor_cycling_v2" -> "Ride" to false
        "indoor_cycling" -> "Ride" to true
        "pool_swimming" -> "Swim" to false
        "elliptical" -> "Elliptical" to true
        "rowing" -> "Rowing" to true
        "hiit" -> "HighIntensityIntervalTraining" to true
        else -> "Workout" to true
    }

    fun description(x: TrainingSession): String {
        val lines = mutableListOf<String>()
        x.avgBpm?.let { avg -> lines += "FC média $avg bpm" + (x.maxBpm?.let { " (máx $it)" } ?: "") }
        for (e in x.entries.filter { it.sets.isNotEmpty() }) {
            lines += "${e.exercise}: " + e.sets.joinToString(", ") { s -> "${s.reps}×${kg(s.kg)} kg" }
        }
        if (x.volumeKg > 0) lines += "${x.setCount} séries · ${kg(x.volumeKg)} kg no total"
        if (x.steps > 0) lines += "${x.steps} passos"
        lines += "Enviado pelo Aravia"
        return lines.joinToString("\n")
    }

    private fun kg(v: Double) = if (v % 1.0 == 0.0) v.toLong().toString() else String.format(Locale("pt", "BR"), "%.1f", v)

    /** Wall-clock time on this phone, as Strava's start_date_local wants it. */
    fun localTime(epochMs: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        f.timeZone = TimeZone.getDefault()
        return f.format(Date(epochMs)) + "Z"
    }

    // HTTP and tokens

    private suspend fun validToken(): String {
        accessToken?.let { if (accessExpiry > System.currentTimeMillis() / 1000 + 60) return it }
        val refresh = prefs.getString("refresh", null)?.let { box.open(it) } ?: throw StravaException("Conecte o Strava de novo.")
        try {
            token(mapOf(
                "client_id" to BuildConfig.STRAVA_CLIENT_ID, "client_secret" to BuildConfig.STRAVA_CLIENT_SECRET,
                "refresh_token" to refresh, "grant_type" to "refresh_token",
            ))
        } catch (e: java.io.IOException) {
            throw e  // offline: keep the connection and try again later
        } catch (e: Exception) {
            signOut()
            throw StravaException("O acesso ao Strava venceu. Conecte de novo.")
        }
        return accessToken ?: throw StravaException("Sem acesso ao Strava.")
    }

    /** Strava hands a new refresh token with every access token; the newest is kept. */
    private suspend fun token(fields: Map<String, String>): JSONObject {
        val json = call("POST", "https://www.strava.com/oauth/token", null, form(fields), "application/x-www-form-urlencoded")
        val access = json.optString("access_token").takeIf { it.isNotBlank() }
        val refresh = json.optString("refresh_token").takeIf { it.isNotBlank() }
        if (access == null || refresh == null) throw StravaException(json.optString("message", "O Strava recusou o login."))
        prefs.edit().putString("refresh", box.seal(refresh)).apply()
        accessToken = access
        accessExpiry = json.optLong("expires_at", System.currentTimeMillis() / 1000 + 3600)
        return json
    }

    private suspend fun call(method: String, url: String, token: String?, body: ByteArray?, type: String?): JSONObject =
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                token?.let { c.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    c.doOutput = true
                    type?.let { c.setRequestProperty("Content-Type", it) }
                    c.outputStream.use { it.write(body) }
                }
                val code = c.responseCode
                val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
                if (code == 401) accessToken = null
                if (code !in 200..299) {
                    if (code == 429) throw StravaException("Muitos envios seguidos. O Strava libera de novo em 15 minutos.")
                    throw StravaException("O Strava recusou (${json.optString("message", "erro $code")}).")
                }
                json
            } finally {
                c.disconnect()
            }
        }

    private fun form(fields: Map<String, String>): ByteArray =
        fields.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }.toByteArray()

    private fun fail(message: String) { _state.value = _state.value.copy(busy = false, error = message) }
}
