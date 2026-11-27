package app.aravia.band

import android.content.Context
import app.aravia.protocol.ActivityFileID
import app.aravia.protocol.ActivityFileKind
import app.aravia.protocol.ActivityParser
import app.aravia.protocol.ActivityPayload
import app.aravia.routes.RouteStore
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The watch's activity files, kept on this phone as received (files/activity/<id hex>.bin), so they
 * can be parsed again as the parsers improve. A file's modification time is when it was fetched,
 * which the sync plan uses to tell a final copy from one of an unfinished day. GPS tracks also go
 * to RouteStore. Health data: it never leaves the phone unless the user exports raw files in Diagnostics.
 */
object ActivityStore {
    data class StoredFile(
        val id: ActivityFileID,
        val sizeBytes: Int,
        val fetchedAtMillis: Long,
        val status: String,
        val note: String?,
        val kindLabel: String,
    )

    data class ReparseSummary(val total: Int, val parsed: Int, val unknown: Int, val failed: Int)

    private var dir: File? = null
    private val _version = MutableStateFlow(0)
    /** Bumped when a file is stored or reparsed, so screens that read the files redraw. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        dir = File(context.filesDir, "activity").apply { mkdirs() }
        BandLink.onActivityFile = { id, data -> save(id, data) }
        BandLink.storedAt = { id -> file(id)?.takeIf { it.exists() }?.lastModified()?.div(1000) }
        // The band deletes an acknowledged file. Not yet on Android: files stay on the band until
        // the iPhone or Mi Fitness clears them.
        BandLink.mayAcknowledge = { false }
    }

    private fun file(id: ActivityFileID): File? = dir?.let { File(it, "${id.hex}.bin") }

    fun save(id: ActivityFileID, data: ByteArray) {
        val f = file(id) ?: return
        f.writeBytes(data)
        if (id.kind == ActivityFileKind.WORKOUT_GPS) RouteStore.save(id.hex, data)
        _version.value++
    }

    /** Every stored file, parsed. */
    fun all(): List<Pair<ActivityFileID, ActivityPayload>> = (dir?.listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".bin") }
        .mapNotNull { f -> val r = ActivityParser.parse(f.readBytes()); r.fileID?.let { it to r.payload } }
        .sortedBy { it.first }

    /** Stored files inspected for Diagnostics, newest first. */
    fun inspect(limit: Int = 60): List<StoredFile> = (dir?.listFiles() ?: emptyArray())
        .filter { it.name.endsWith(".bin") }
        .mapNotNull { f ->
            val raw = runCatching { f.readBytes() }.getOrNull() ?: return@mapNotNull null
            val parsed = ActivityParser.parse(raw)
            val id = parsed.fileID ?: return@mapNotNull null
            val (status, note) = when (val p = parsed.payload) {
                is ActivityPayload.Unsupported -> if (id.kind == ActivityFileKind.WORKOUT_GPS) "lido" to "${parsed.payloadLength} B GPS"
                    else "desconhecido" to "tipo ${id.type}-${id.subtype}-${id.detailType} v${id.version}"
                is ActivityPayload.Error -> "erro" to (p.error.message ?: p.error.javaClass.simpleName)
                is ActivityPayload.Empty -> "vazio" to null
                is ActivityPayload.Daily -> "lido" to "${p.details.records.size} min"
                is ActivityPayload.Summary -> "lido" to p.summary.steps?.let { "$it passos" }
                is ActivityPayload.Sleep -> "lido" to p.details.session?.let { "sono" }
                is ActivityPayload.Stages -> "lido" to "${p.stages.stages.size} trechos"
                is ActivityPayload.Manual -> "lido" to "${p.samples.samples.size} leituras"
                is ActivityPayload.Workout -> "lido" to p.summary.sport
            }
            StoredFile(id, raw.size, f.lastModified(), status, note, kindLabel(id))
        }
        .sortedByDescending { it.id.timestamp }
        .take(limit)

    /** Re-reads every stored raw file, syncs GPS routes and bumps version. */
    fun reparseAll(): ReparseSummary {
        var total = 0; var parsed = 0; var unknown = 0; var failed = 0
        for (f in (dir?.listFiles() ?: emptyArray()).filter { it.name.endsWith(".bin") }) {
            val raw = runCatching { f.readBytes() }.getOrNull() ?: continue
            total++
            val r = ActivityParser.parse(raw)
            val id = r.fileID
            if (id != null && id.kind == ActivityFileKind.WORKOUT_GPS) RouteStore.save(id.hex, raw)
            when (r.payload) {
                is ActivityPayload.Unsupported -> if (id?.kind == ActivityFileKind.WORKOUT_GPS) parsed++ else unknown++
                is ActivityPayload.Error -> failed++
                else -> parsed++
            }
        }
        _version.value++
        return ReparseSummary(total, parsed, unknown, failed)
    }

    /** Packs all raw .bin files into cache/raw/aravia-arquivos-brutos.zip using the iOS naming convention. */
    fun exportRawZip(context: Context): File? {
        val files = (dir?.listFiles() ?: emptyArray()).filter { it.name.endsWith(".bin") }
        if (files.isEmpty()) return null
        val outDir = File(context.cacheDir, "raw").apply { mkdirs() }
        val zipFile = File(outDir, "aravia-arquivos-brutos.zip")
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
            for (f in files.sortedBy { it.name }) {
                val raw = runCatching { f.readBytes() }.getOrNull() ?: continue
                val id = ActivityFileID.of(raw)
                val entryName = if (id != null) "${id.type}-${id.subtype}-${id.detailType}-v${id.version}_${id.hex}.bin" else f.name
                zos.putNextEntry(ZipEntry(entryName))
                zos.write(raw)
                zos.closeEntry()
            }
        }
        return zipFile
    }

    private fun kindLabel(id: ActivityFileID): String = when (id.kind) {
        ActivityFileKind.DAILY_DETAILS -> "Minutos do dia"
        ActivityFileKind.DAILY_SUMMARY -> "Resumo do dia"
        ActivityFileKind.SLEEP_DETAILS -> "Detalhes do sono"
        ActivityFileKind.SLEEP_STAGES -> "Fases do sono"
        ActivityFileKind.MANUAL_SAMPLES -> "Medições manuais"
        ActivityFileKind.WORKOUT_SUMMARY -> "Resumo de treino"
        ActivityFileKind.WORKOUT_DETAILS -> "Detalhes de treino"
        ActivityFileKind.WORKOUT_GPS -> "Rota GPS"
        ActivityFileKind.UNKNOWN -> "Arquivo ${id.type}-${id.subtype}-${id.detailType} v${id.version}"
    }

    val count: Int get() = dir?.listFiles()?.count { it.name.endsWith(".bin") } ?: 0
}
