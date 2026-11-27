package app.aravia.band

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * The watch this phone talks to, with its keys. The keys are a device secret: they stay on this
 * phone, sealed with a key that lives in the Android Keystore, never in the account or Firebase.
 */
object BandVault {
    data class Band(
        val name: String,
        val mac: String?,
        val model: String,
        val keys: List<String>,
        /** Sent in MiAuth.nonceCommand when the key came from a local bind (docs/BIND.md §6). */
        val appDeviceID: String? = null,
        val firmware: String? = null,
        val hardwareModel: String? = null,
    ) {
        /** The watch's short name without the four characters at the end of its Bluetooth name. */
        val shortName: String get() = name.replace(Regex("\\s+[0-9A-Fa-f]{4}$"), "")
    }

    private const val ALIAS = "aravia.bands"
    private val _band = MutableStateFlow<Band?>(null)
    val band: StateFlow<Band?> = _band.asStateFlow()
    private lateinit var prefs: android.content.SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences("aravia.band", Context.MODE_PRIVATE)
        _band.value = prefs.getString("sealed", null)?.let { runCatching { parse(String(open(it), Charsets.UTF_8)) }.getOrNull() }
    }

    /** The JSON from KeyTransfer or local storage: {"v":1, "name", "mac", "model", "keys":[hex…]}. */
    fun parse(json: String): Band? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val keys = o.optJSONArray("keys") ?: return null
        val list = (0 until keys.length()).map { keys.getString(it).lowercase() }.filter { it.matches(Regex("[0-9a-f]{32}")) }
        if (list.isEmpty()) return null
        fun optStr(k: String) = o.optString(k).takeIf { it.isNotBlank() && it != "null" }
        return Band(
            name = o.optString("name", "Relógio"),
            mac = optStr("mac"),
            model = o.optString("model", ""),
            keys = list,
            appDeviceID = optStr("appDeviceID"),
            firmware = optStr("firmware"),
            hardwareModel = optStr("hardwareModel"),
        )
    }

    fun save(b: Band) {
        val json = JSONObject().put("v", 1).put("name", b.name).put("mac", b.mac ?: JSONObject.NULL).put("model", b.model)
            .put("keys", JSONArray(b.keys))
            .put("appDeviceID", b.appDeviceID ?: JSONObject.NULL)
            .put("firmware", b.firmware ?: JSONObject.NULL)
            .put("hardwareModel", b.hardwareModel ?: JSONObject.NULL)
            .toString()
        prefs.edit().putString("sealed", seal(json.toByteArray())).apply()
        _band.value = b
    }

    /** Updates firmware and hardware model reported by the watch in Command{2,2}. */
    fun updateInfo(firmware: String, hardwareModel: String) {
        val cur = _band.value ?: return
        val nextFw = firmware.ifBlank { cur.firmware }
        val nextModel = hardwareModel.ifBlank { cur.hardwareModel }
        if (nextFw == cur.firmware && nextModel == cur.hardwareModel) return
        save(cur.copy(firmware = nextFw, hardwareModel = nextModel))
    }

    /** Saves the Bluetooth MAC learned from a connected/bonded watch when it was originally omitted. */
    fun updateMac(mac: String) {
        val cur = _band.value ?: return
        if (!cur.mac.isNullOrBlank() || mac.isBlank()) return
        save(cur.copy(mac = mac))
    }

    /** Moves the key that matched to the front so the next login tries it first. */
    fun promoteKey(index: Int) {
        val cur = _band.value ?: return
        if (index <= 0 || index >= cur.keys.size) return
        val list = cur.keys.toMutableList()
        list.add(0, list.removeAt(index))
        save(cur.copy(keys = list))
    }

    /** In-memory band for emulator screenshots (Demo.kt); never written to disk. */
    fun setDemo(b: Band?) { _band.value = b }

    fun forget() {
        prefs.edit().remove("sealed").apply()
        _band.value = null
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    private fun seal(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(plain), Base64.NO_WRAP)
    }

    private fun open(sealed: String): ByteArray {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        }
        return c.doFinal(raw, 12, raw.size - 12)
    }
}
