package app.aravia.routes

import android.content.Context
import app.aravia.protocol.GpsTrack
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The watch's GPS track files (`files/routes/<file id hex>.bin`) and user-imported GPX tracks
 * (`files/routes/gpx_<ts>_<hash>.gpx`).
 */
object RouteStore {
    /** One stored track. `subtype` is the sport byte of the file id; `ts` its time in Unix seconds. */
    data class Route(
        val fileId: String,
        val version: Int,
        val subtype: Int,
        val ts: Long,
        val file: File,
        val customTitle: String? = null,
        val isImported: Boolean = false,
    ) {
        val track: GpsTrack? by lazy {
            if (isImported) GpsTrack.parseGpx(file.readText(Charsets.UTF_8))
            else GpsTrack.parse(file.readBytes(), version)
        }
        val title: String get() = customTitle?.takeIf { it.isNotBlank() } ?: sportTitle(subtype)
    }

    private val _routes = MutableStateFlow<List<Route>>(emptyList())
    val routes: StateFlow<List<Route>> = _routes.asStateFlow()
    private var dir: File? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "routes").apply { mkdirs() }
        reload()
    }

    /** Stores a GPS file by its 7-byte id in hex: ts u32 LE | tz i8 | version u8 |
     *  flags u8 (type << 7 | subtype << 2 | detail type). */
    fun save(fileId: String, data: ByteArray) {
        val d = dir ?: return
        File(d, "${fileId.lowercase()}.bin").writeBytes(data)
        reload()
    }

    /** Imports an external GPX 1.0/1.1 document (Strava, Garmin, Mi Fitness, Apple Watch, etc.). */
    fun importGpx(xml: String, fallbackName: String? = null): Route? {
        val d = dir ?: return null
        val parsed = GpsTrack.parseGpx(xml) ?: return null
        val ts = parsed.start ?: (System.currentTimeMillis() / 1000L)
        val rawName = GpsTrack.parseGpxName(xml) ?: fallbackName?.substringBeforeLast('.')?.trim()
        val cleanName = rawName?.takeIf { it.isNotBlank() }?.take(48) ?: "Rota importada"
        val normalizedXml = parsed.gpx(cleanName)
        val hash = (normalizedXml.hashCode().toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
        val id = "gpx_${ts}_$hash"
        File(d, "$id.gpx").writeText(normalizedXml, Charsets.UTF_8)
        reload()
        return _routes.value.firstOrNull { it.fileId == id }
    }

    /** Imports a GPS exercise route from Android Health Connect, deduplicated by record id. */
    fun importHealthConnectRoute(
        recordId: String,
        title: String,
        subtype: Int,
        points: List<app.aravia.protocol.GpsPoint>,
    ): Route? {
        val d = dir ?: return null
        val cleanPts = points.filter { it.latitude.isFinite() && it.longitude.isFinite() && (it.latitude != 0.0 || it.longitude != 0.0) }
            .sortedBy { it.time }
        if (cleanPts.size < 2) return null
        val track = GpsTrack(cleanPts)
        val ts = track.start ?: (System.currentTimeMillis() / 1000L)
        val hash = (recordId.hashCode().toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
        val id = "gpx_${ts}_$hash"
        val target = File(d, "$id.gpx")
        if (target.exists()) return null // already imported
        target.writeText(track.gpx(title.take(48)), Charsets.UTF_8)
        reload()
        return _routes.value.firstOrNull { it.fileId == id }
    }

    fun deleteImported(fileId: String) {
        val d = dir ?: return
        val f = File(d, "$fileId.gpx")
        if (f.exists()) {
            f.delete()
            reload()
        }
    }

    private fun reload() {
        val d = dir ?: return
        _routes.value = (d.listFiles() ?: emptyArray()).mapNotNull { f ->
            val binMatch = Regex("^([0-9a-f]{14})\\.bin$").find(f.name)
            if (binMatch != null) {
                val id = binMatch.groupValues[1]
                val bytes = ByteArray(7) { id.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
                val ts = (bytes[0].toLong() and 0xFF) or ((bytes[1].toLong() and 0xFF) shl 8) or
                    ((bytes[2].toLong() and 0xFF) shl 16) or ((bytes[3].toLong() and 0xFF) shl 24)
                val flags = bytes[6].toInt() and 0xFF
                return@mapNotNull Route(id, bytes[5].toInt() and 0xFF, (flags and 0x7F) shr 2, ts, f)
            }
            val gpxMatch = Regex("^(gpx_(\\d+)_[0-9a-f]+)\\.gpx$").find(f.name)
            if (gpxMatch != null) {
                val id = gpxMatch.groupValues[1]
                val ts = gpxMatch.groupValues[2].toLongOrNull() ?: (f.lastModified() / 1000L)
                val name = runCatching { GpsTrack.parseGpxName(f.readText(Charsets.UTF_8)) }.getOrNull()
                return@mapNotNull Route(id, version = 0, subtype = 0x01, ts = ts, file = f, customTitle = name, isImported = true)
            }
            null
        }.sortedByDescending { it.ts }
    }

    /** Portuguese names of the band's sport subtypes, as on iOS (RouteNames). */
    fun sportTitle(subtype: Int): String = when (subtype) {
        0x01 -> "Corrida ao ar livre"
        0x02, 0x16 -> "Caminhada ao ar livre"
        0x03 -> "Esteira"
        0x06, 0x17 -> "Pedalada ao ar livre"
        0x07 -> "Bicicleta indoor"
        0x08 -> "Treino livre"
        0x09 -> "Natação"
        else -> "Treino"
    }

    /** The band's sport name (ActivityFileID.Sport on iOS), for Strava. */
    fun sportName(subtype: Int): String = when (subtype) {
        0x01 -> "outdoor_running"
        0x02 -> "outdoor_walking_v1"
        0x03 -> "treadmill"
        0x06 -> "outdoor_cycling_v2"
        0x07 -> "indoor_cycling"
        0x08 -> "freestyle"
        0x09 -> "pool_swimming"
        0x0B -> "elliptical"
        0x0D -> "rowing"
        0x0E -> "jump_roping"
        0x10 -> "hiit"
        0x16 -> "outdoor_walking_v2"
        0x17 -> "outdoor_cycling"
        else -> "workout"
    }
}
