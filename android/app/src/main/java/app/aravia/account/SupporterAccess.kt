package app.aravia.account

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Supporter Early Access entitlements & experimental lab preferences.
 *
 * Verified on the server (`POST https://aravia.app/api/resgatar`) using the user's real Firebase
 * Auth ID token, then persisted into `users/{uid}.supporter` in Firestore so the unlocked Early
 * Access section follows the person's account across phones and reinstalls.
 */
object SupporterAccess {
    private const val ENDPOINT = "https://aravia.app/api/resgatar"
    private const val PREFS = "aravia_early_access"

    data class Entitlement(
        val tier: String,
        val tierName: String,
        val earlyAccess: Boolean,
        val founderBadge: Boolean,
        val lifetime: Boolean,
        val premiumMonths: Int,
        val validUntil: String? = null,
        val codeMasked: String? = null,
        val earlyUrl: String? = null,
    )

    data class LabPrefs(
        val earlyChannel: Boolean = true,
        val hideStoryWatermark: Boolean = true,
        val experimentalMetrics: Boolean = true,
    )

    private var prefs: SharedPreferences? = null
    private val _lab = MutableStateFlow(LabPrefs())
    val lab: StateFlow<LabPrefs> = _lab.asStateFlow()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _lab.value = LabPrefs(
            earlyChannel = p.getBoolean("lab_early_channel", true),
            hideStoryWatermark = p.getBoolean("lab_hide_watermark", true),
            experimentalMetrics = p.getBoolean("lab_exp_metrics", true),
        )
    }

    fun setLab(
        earlyChannel: Boolean = _lab.value.earlyChannel,
        hideStoryWatermark: Boolean = _lab.value.hideStoryWatermark,
        experimentalMetrics: Boolean = _lab.value.experimentalMetrics,
    ) {
        val next = LabPrefs(earlyChannel, hideStoryWatermark, experimentalMetrics)
        _lab.value = next
        prefs?.edit()
            ?.putBoolean("lab_early_channel", next.earlyChannel)
            ?.putBoolean("lab_hide_watermark", next.hideStoryWatermark)
            ?.putBoolean("lab_exp_metrics", next.experimentalMetrics)
            ?.apply()
    }

    fun loadCached(uid: String): Entitlement? {
        val p = prefs ?: return null
        if (p.getString("cached_uid", null) != uid) return null
        val tier = p.getString("tier", null) ?: return null
        return Entitlement(
            tier = tier,
            tierName = p.getString("tier_name", tier) ?: tier,
            earlyAccess = p.getBoolean("early_access", false),
            founderBadge = p.getBoolean("founder_badge", false),
            lifetime = p.getBoolean("lifetime", false),
            premiumMonths = p.getInt("premium_months", 0),
            validUntil = p.getString("valid_until", null),
            codeMasked = p.getString("code_masked", null),
            earlyUrl = p.getString("early_url", null),
        )
    }

    fun cache(uid: String, ent: Entitlement?) {
        val editor = prefs?.edit() ?: return
        if (ent == null) {
            editor.remove("cached_uid").remove("tier").remove("tier_name")
                .remove("early_access").remove("founder_badge").remove("lifetime")
                .remove("premium_months").remove("valid_until").remove("code_masked")
                .remove("early_url").apply()
            return
        }
        editor.putString("cached_uid", uid)
            .putString("tier", ent.tier)
            .putString("tier_name", ent.tierName)
            .putBoolean("early_access", ent.earlyAccess)
            .putBoolean("founder_badge", ent.founderBadge)
            .putBoolean("lifetime", ent.lifetime)
            .putInt("premium_months", ent.premiumMonths)
            .putString("valid_until", ent.validUntil)
            .putString("code_masked", ent.codeMasked)
            .putString("early_url", ent.earlyUrl)
            .apply()
    }

    fun clearCache() {
        cache("", null)
    }

    @Suppress("UNCHECKED_CAST")
    fun parseFromFirestore(profile: DocumentSnapshot?): Entitlement? {
        val map = profile?.get("supporter") as? Map<String, Any?> ?: return null
        val tier = (map["tier"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val early = map["earlyAccess"] as? Boolean ?: false
        if (!early) return null
        return Entitlement(
            tier = tier,
            tierName = (map["tierName"] as? String)?.takeIf { it.isNotBlank() } ?: tier,
            earlyAccess = early,
            founderBadge = map["founderBadge"] as? Boolean ?: false,
            lifetime = map["lifetime"] as? Boolean ?: false,
            premiumMonths = (map["premiumMonths"] as? Number)?.toInt() ?: 0,
            validUntil = (map["validUntil"] as? String)?.takeIf { it.isNotBlank() },
            codeMasked = (map["codeMasked"] as? String)?.takeIf { it.isNotBlank() },
            earlyUrl = (map["earlyUrl"] as? String)?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun saveToFirestore(db: FirebaseFirestore, uid: String, ent: Entitlement) {
        val payload = buildMap<String, Any> {
            put("tier", ent.tier)
            put("tierName", ent.tierName)
            put("earlyAccess", ent.earlyAccess)
            put("founderBadge", ent.founderBadge)
            put("lifetime", ent.lifetime)
            put("premiumMonths", ent.premiumMonths.toLong())
            ent.validUntil?.let { put("validUntil", it) }
            ent.codeMasked?.let { put("codeMasked", it) }
            ent.earlyUrl?.let { put("earlyUrl", it) }
            put("updatedAt", Timestamp.now())
        }
        db.collection("users").document(uid)
            .set(mapOf("supporter" to payload), SetOptions.merge())
            .await()
    }

    /**
     * Calls `POST https://aravia.app/api/resgatar` with the user's verified Firebase ID token.
     * - When `code` is non-blank, redeems that exact code and throws `Account.Problem` on error.
     * - When `code` is null/blank, silently checks if this account already has an approved Early
     *   Access support (by bound `uid` or matching `@usuario`).
     */
    suspend fun fetchFromServer(idToken: String, code: String? = null): Entitlement? = withContext(Dispatchers.IO) {
        val conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $idToken")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val body = JSONObject().apply {
                if (!code.isNullOrBlank()) put("codigo", code.trim().uppercase())
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()

            if (status !in 200..299) {
                val msg = json?.optString("error")?.takeIf { it.isNotBlank() }
                    ?: "Não foi possível validar o código agora (HTTP $status)."
                throw Account.Problem(msg)
            }
            if (json == null || !json.optBoolean("ativo", false)) {
                return@withContext null
            }
            Entitlement(
                tier = json.optString("faixa", "filhote"),
                tierName = json.optString("faixaNome", "Apoiador"),
                earlyAccess = json.optBoolean("acessoAntecipado", false),
                founderBadge = json.optBoolean("seloFundador", false),
                lifetime = json.optBoolean("vitalicio", false),
                premiumMonths = json.optInt("premiumMeses", 0),
                validUntil = json.optString("validoAte", "").takeIf { it.isNotBlank() && it != "null" },
                codeMasked = json.optString("codigoMascarado", "").takeIf { it.isNotBlank() && it != "null" },
                earlyUrl = json.optString("earlyUrl", "").takeIf { it.isNotBlank() && it != "null" },
            )
        } finally {
            conn.disconnect()
        }
    }

    fun tierRank(tier: String?): Int = when (tier) {
        "arara_real" -> 5
        "adulta" -> 4
        "jovem" -> 3
        "filhote" -> 2
        "ovo" -> 1
        else -> 0
    }
}
