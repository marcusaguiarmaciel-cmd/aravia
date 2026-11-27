package app.aravia.band

import android.content.Context
import android.content.SharedPreferences
import app.aravia.Demo
import app.aravia.protocol.MiAuth
import app.aravia.protocol.MiMonitoring
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Watch preferences and commands on Android: Stay connected ("Fica conectado"), health monitoring
 * settings cache, Find band, Diagnostics probes and local bind persistence.
 */
object BandSettings {
    private lateinit var prefs: SharedPreferences
    private val _stayConnected = MutableStateFlow(false)
    val stayConnected: StateFlow<Boolean> = _stayConnected.asStateFlow()

    private val _monitoring = MutableStateFlow<Map<MiMonitoring.Setting, ByteArray>>(emptyMap())
    val monitoring: StateFlow<Map<MiMonitoring.Setting, ByteArray>> = _monitoring.asStateFlow()

    fun init(context: Context) {
        prefs = context.getSharedPreferences("aravia.band.settings", Context.MODE_PRIVATE)
        _stayConnected.value = prefs.getBoolean("stayConnected", false)
        loadMonitoring()

        BandLink.onMonitoring = { setting, body -> saveMonitoring(setting, body) }
        BandLink.onDeviceInfo = { info -> BandVault.updateInfo(info.firmware, info.model) }
        BandLink.onMacResolved = { mac -> BandVault.updateMac(mac) }
        BandLink.onKeyMatched = { idx -> BandVault.promoteKey(idx) }
        BandLink.onBound = { bound, bleName, mac ->
            val keyHex = bound.authKey.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            val resolvedMac = bound.info.mac.takeIf { it.isNotBlank() } ?: mac
            val name = bleName.ifBlank { bound.info.model.ifBlank { "Relógio" } }
            BandVault.save(
                BandVault.Band(
                    name = name,
                    mac = resolvedMac,
                    model = bound.info.model,
                    keys = listOf(keyHex),
                    appDeviceID = bound.appDeviceID,
                    hardwareModel = bound.info.model.takeIf { it.isNotBlank() },
                )
            )
            updateHold()
        }
    }

    fun setStayConnected(on: Boolean) {
        _stayConnected.value = on
        if (::prefs.isInitialized) prefs.edit().putBoolean("stayConnected", on).apply()
        updateHold()
    }

    fun updateHold() {
        if (Demo.active || !_stayConnected.value) { BandLink.hold(null); return }
        val band = BandVault.band.value
        if (band == null) { BandLink.hold(null); return }
        BandLink.hold(band)
    }

    fun findBand(band: BandVault.Band, start: Boolean) {
        BandLink.start(band, BandLink.Purpose.Send(listOf(MiAuth.findBandCommand(start))))
    }

    fun readMonitoring(band: BandVault.Band) {
        BandLink.start(band, BandLink.Purpose.Send(MiMonitoring.Setting.entries.map { MiMonitoring.get(it) }))
    }

    fun writeMonitoring(band: BandVault.Band, setting: MiMonitoring.Setting, body: ByteArray?) {
        if (body == null) return
        BandLink.start(band, BandLink.Purpose.Send(listOf(MiMonitoring.set(setting, body), MiMonitoring.get(setting))))
    }

    /** Diagnostics › Probe the band: read-only Health queries logged whole (same list as iOS). */
    fun probeBand(band: BandVault.Band) {
        val gets = listOf(6, 7, 29, 42, 44, 61, 63, 86)
        BandLink.note("probe: asking " + gets.joinToString(", ") { "8/$it" })
        BandLink.start(band, BandLink.Purpose.Send(gets.map { MiAuth.healthGet(it) }))
    }

    /** Diagnostics › Ask the band how it pairs: sends step 1 of local bind and stops (docs/BIND.md). */
    fun probeBind(band: BandVault.Band) {
        BandLink.start(band, BandLink.Purpose.BIND_PROBE)
    }

    fun clearMonitoring() {
        _monitoring.value = emptyMap()
        if (::prefs.isInitialized) {
            val e = prefs.edit()
            for (s in MiMonitoring.Setting.entries) e.remove("mon.${s.getSubtype}")
            e.apply()
        }
    }

    /** In-memory demo monitoring settings for emulator screenshots; never written to disk. */
    fun setDemoMonitoring(map: Map<MiMonitoring.Setting, ByteArray>) {
        _monitoring.value = map
    }

    private fun saveMonitoring(setting: MiMonitoring.Setting, body: ByteArray) {
        val next = _monitoring.value.toMutableMap()
        next[setting] = body
        _monitoring.value = next
        if (::prefs.isInitialized && !Demo.active) {
            val hex = body.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            prefs.edit().putString("mon.${setting.getSubtype}", hex).apply()
        }
    }

    private fun loadMonitoring() {
        val map = LinkedHashMap<MiMonitoring.Setting, ByteArray>()
        for (s in MiMonitoring.Setting.entries) {
            val hex = prefs.getString("mon.${s.getSubtype}", null) ?: continue
            if (hex.length % 2 == 0) {
                runCatching {
                    ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                }.getOrNull()?.let { map[s] = it }
            }
        }
        _monitoring.value = map
    }

    /** Whether Android lets Aravia run in the background without battery throttling. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean =
        runCatching { context.getSystemService(android.os.PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true }
            .getOrDefault(false)

    /** Opens Android's battery-optimization list so the user can exempt Aravia for Stay connected. */
    fun openBatterySettings(context: Context) {
        val intent = android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }
}
