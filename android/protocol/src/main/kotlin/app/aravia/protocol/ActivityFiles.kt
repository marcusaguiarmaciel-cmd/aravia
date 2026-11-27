package app.aravia.protocol

// Activity files: the 7-byte file id, reassembly of the decrypted channel-5 packets, the CRC-32
// trailer and the payload parsers. A Kotlin port of the app's own ios/Freeband/Band/ActivityFiles.swift
// (MIT), written from docs/ACTIVITY.md sections 3-10 and tested against the same fixtures
// (ios/tests/activity/fixtures/synthetic.json), which the Python reference activity_ref.py checks
// on its own.
//
// Every read of file data goes through ActivityByteReader, which throws instead of reading out of
// range, so malformed input gives an error or a partial result, never a crash. Times are Unix
// seconds.

private const val HEX = "0123456789abcdef"

fun activityHex(bytes: ByteArray): String = buildString(bytes.size * 2) {
    for (b in bytes) { val v = b.toInt() and 0xFF; append(HEX[v shr 4]); append(HEX[v and 0x0F]) }
}

private fun hexByte(b: Int) = "0x" + activityHex(byteArrayOf(b.toByte()))

// CRC-32

/** Standard CRC-32 (zlib): reflected polynomial 0xEDB88320, init and final XOR 0xFFFFFFFF. Spec section 4.2. */
object ActivityCRC32 {
    const val CHECK_VALUE = 0xCBF43926L
    private val table = LongArray(256) { n ->
        var c = n.toLong()
        repeat(8) { c = if (c and 1L != 0L) (c ushr 1) xor 0xEDB88320L else c ushr 1 }
        c
    }

    fun checksum(bytes: ByteArray, from: Int = 0, to: Int = bytes.size): Long {
        var c = 0xFFFFFFFFL
        for (i in from until to) c = table[((c xor (bytes[i].toLong() and 0xFF)) and 0xFF).toInt()] xor (c ushr 8)
        return c xor 0xFFFFFFFFL
    }

    /** The stored trailer (last 4 bytes, LE) and the CRC of everything before it; null under 4 bytes. */
    fun trailer(bytes: ByteArray): Pair<Long, Long>? {
        if (bytes.size < 4) return null
        val n = bytes.size - 4
        val stored = (bytes[n].toLong() and 0xFF) or ((bytes[n + 1].toLong() and 0xFF) shl 8) or
            ((bytes[n + 2].toLong() and 0xFF) shl 16) or ((bytes[n + 3].toLong() and 0xFF) shl 24)
        return stored to checksum(bytes, 0, n)
    }
}

// Errors

sealed class ActivityParseError(message: String) : Exception(message) {
    class FileTooShort(val length: Int, val minimum: Int) : ActivityParseError("file of $length bytes is shorter than the $minimum-byte minimum")
    class CrcMismatch(val stored: Long, val computed: Long) : ActivityParseError("crc stored ${stored.toString(16)} computed ${computed.toString(16)}")
    class Truncated(val offset: Int, val needed: Int, val available: Int) : ActivityParseError("need $needed byte(s) at payload offset $offset, $available left")
    class Malformed(message: String) : ActivityParseError(message)
}

// Bounds-checked reader

/** Little-endian (unless noted) reader over bytes[position until end]; throws Truncated instead of reading past the end. */
class ActivityByteReader(val bytes: ByteArray, from: Int = 0, to: Int? = null) {
    val end: Int = minOf(maxOf(to ?: bytes.size, 0), bytes.size)
    var position: Int = minOf(maxOf(from, 0), end); private set
    val remaining: Int get() = end - position

    fun copy(): ActivityByteReader = ActivityByteReader(bytes, position, end)

    fun take(n: Int): ByteArray {
        if (n < 0 || n > remaining) throw ActivityParseError.Truncated(position, n, remaining)
        val out = bytes.copyOfRange(position, position + n)
        position += n
        return out
    }

    fun skip(n: Int) { take(n) }

    fun uintLE(n: Int): Long {
        if (n !in 1..8) throw ActivityParseError.Malformed("integer width $n")
        var v = 0L
        take(n).forEachIndexed { i, b -> v = v or ((b.toLong() and 0xFF) shl (8 * i)) }
        return v
    }

    fun uintBE(n: Int): Long {
        if (n !in 1..8) throw ActivityParseError.Malformed("integer width $n")
        var v = 0L
        for (b in take(n)) v = (v shl 8) or (b.toLong() and 0xFF)
        return v
    }

    fun u8(): Int = uintLE(1).toInt()
    fun u16(): Int = uintLE(2).toInt()
    fun i16(): Int = uintLE(2).toInt().toShort().toInt()
    fun u16BE(): Int = uintBE(2).toInt()
    fun u32(): Long = uintLE(4)
    fun i64(): Long = uintLE(8)
    fun f32(): Float = java.lang.Float.intBitsToFloat(uintLE(4).toInt())
}

// File id (spec section 3)

enum class ActivityFileKind(val raw: String) {
    DAILY_DETAILS("daily_details"), DAILY_SUMMARY("daily_summary"), SLEEP_DETAILS("sleep_details"),
    SLEEP_STAGES("sleep_stages"), MANUAL_SAMPLES("manual_samples"), WORKOUT_SUMMARY("workout_summary"),
    WORKOUT_DETAILS("workout_details"), WORKOUT_GPS("workout_gps"), UNKNOWN("unknown");

    /** File format versions this module parses; empty for kinds stored raw only. */
    val supportedVersions: Set<Int> get() = when (this) {
        DAILY_DETAILS -> setOf(1, 2, 3, 4)
        DAILY_SUMMARY -> setOf(3, 4, 5)
        SLEEP_DETAILS -> setOf(1, 2, 3, 4, 5, 6)
        SLEEP_STAGES -> setOf(2)
        MANUAL_SAMPLES -> setOf(1, 2)
        else -> emptySet()
    }

    val isWorkout: Boolean get() = this == WORKOUT_SUMMARY || this == WORKOUT_DETAILS || this == WORKOUT_GPS
}

/** The 7-byte activity file id: ts u32 LE | tz i8 (quarter hours) | version u8 | flags u8 = type << 7 | subtype << 2 | detailType. */
data class ActivityFileID(
    /** Unix seconds, UTC. */
    val timestamp: Long,
    /** UTC offset in 15-minute blocks, signed (0xF4 = -12 = UTC-3). */
    val tzQuarterHours: Int,
    val version: Int,
    val type: Int,
    val subtype: Int,
    val detailType: Int,
) : Comparable<ActivityFileID> {
    companion object {
        const val ACTIVITY = 0
        const val SPORTS = 1
        const val SUB_DAILY = 0x00
        const val SUB_SLEEP_STAGES = 0x03
        const val SUB_MANUAL = 0x06
        const val SUB_SLEEP = 0x08
        const val DETAILS = 0
        const val SUMMARY = 1
        const val GPS = 2

        /** Sports subtypes and their names. */
        val sports = mapOf(
            0x01 to "outdoor_running", 0x02 to "outdoor_walking_v1", 0x03 to "treadmill", 0x06 to "outdoor_cycling_v2",
            0x07 to "indoor_cycling", 0x08 to "freestyle", 0x09 to "pool_swimming", 0x0B to "elliptical", 0x0D to "rowing",
            0x0E to "jump_roping", 0x10 to "hiit", 0x16 to "outdoor_walking_v2", 0x17 to "outdoor_cycling",
        )

        fun make(timestamp: Long, tzQuarterHours: Int = 0, version: Int, type: Int, subtype: Int, detailType: Int) =
            ActivityFileID(timestamp and 0xFFFFFFFFL, tzQuarterHours.toByte().toInt(), version and 0xFF, type and 1, subtype and 0x1F, detailType and 3)

        /** Exactly 7 bytes, else null. */
        fun of(b: ByteArray, from: Int = 0): ActivityFileID? {
            if (b.size - from < 7) return null
            val u = { i: Int -> b[from + i].toInt() and 0xFF }
            val flags = u(6)
            return ActivityFileID(
                u(0).toLong() or (u(1).toLong() shl 8) or (u(2).toLong() shl 16) or (u(3).toLong() shl 24),
                b[from + 4].toInt(), u(5), flags shr 7, (flags and 0x7F) shr 2, flags and 0x03,
            )
        }

        fun fromHex(hex: String): ActivityFileID? = if (hex.length == 14) of(hex.unhex()) else null

        /** A list reply: N × 7 bytes. Placeholders are dropped; a length not a multiple of 7 gives [] (the whole list is untrustworthy). */
        fun list(bytes: ByteArray): List<ActivityFileID> {
            if (bytes.size % 7 != 0) return emptyList()
            return (0 until bytes.size / 7).mapNotNull { i -> of(bytes, i * 7)?.takeIf { !it.isPlaceholder } }
        }

        /** Daily details files that a later daily details file of the same list follows: they will not change again. */
        fun finishedDetails(list: Collection<ActivityFileID>): Set<ActivityFileID> {
            val details = list.filter { it.kind == ActivityFileKind.DAILY_DETAILS }
            val newest = details.maxOfOrNull { it.timestamp } ?: return emptySet()
            return details.filter { it.timestamp < newest }.toSet()
        }
    }

    val flags: Int get() = (type shl 7) or (subtype shl 2) or detailType

    val bytes: ByteArray get() = byteArrayOf(
        timestamp.toByte(), (timestamp shr 8).toByte(), (timestamp shr 16).toByte(), (timestamp shr 24).toByte(),
        tzQuarterHours.toByte(), version.toByte(), flags.toByte(),
    )

    val hex: String get() = activityHex(bytes)
    val utcOffsetMinutes: Int get() = tzQuarterHours * 15
    val isPlaceholder: Boolean get() = timestamp == 0L && version == 0

    val kind: ActivityFileKind get() {
        if (type == SPORTS) return when (detailType) {
            DETAILS -> ActivityFileKind.WORKOUT_DETAILS
            SUMMARY -> ActivityFileKind.WORKOUT_SUMMARY
            GPS -> ActivityFileKind.WORKOUT_GPS
            else -> ActivityFileKind.UNKNOWN
        }
        return when {
            subtype == SUB_DAILY && detailType == DETAILS -> ActivityFileKind.DAILY_DETAILS
            subtype == SUB_DAILY && detailType == SUMMARY -> ActivityFileKind.DAILY_SUMMARY
            subtype == SUB_SLEEP_STAGES && detailType == DETAILS -> ActivityFileKind.SLEEP_STAGES
            subtype == SUB_MANUAL && detailType == DETAILS -> ActivityFileKind.MANUAL_SAMPLES
            subtype == SUB_SLEEP -> ActivityFileKind.SLEEP_DETAILS
            else -> ActivityFileKind.UNKNOWN
        }
    }

    val sport: String? get() = if (type == SPORTS) sports[subtype] else null
    val typeName: String get() = if (type == ACTIVITY) "activity" else "sports"
    val subtypeName: String get() = if (type == SPORTS) sport ?: "unknown_${activityHex(byteArrayOf(subtype.toByte()))}" else when (subtype) {
        SUB_DAILY -> "daily"; SUB_SLEEP_STAGES -> "sleep_stages"; SUB_MANUAL -> "manual_samples"; SUB_SLEEP -> "sleep"
        else -> "unknown_${activityHex(byteArrayOf(subtype.toByte()))}"
    }
    val detailName: String get() = when (detailType) { DETAILS -> "details"; SUMMARY -> "summary"; GPS -> "gps_track"; else -> "unknown" }

    /** Summary first, so a workout's summary is at hand when its details arrive. */
    val fetchRank: Int get() = when (detailType) { SUMMARY -> 0; DETAILS -> 1; GPS -> 2; else -> 3 }

    /** Fetch order: ts, tz, type, subtype, detail fetch rank, version (spec section 2.5). */
    override fun compareTo(other: ActivityFileID): Int = compareValuesBy(this, other,
        { it.timestamp }, { it.tzQuarterHours }, { it.type }, { it.subtype }, { it.fetchRank }, { it.version })

    override fun toString() = "$hex $typeName/$subtypeName/$detailName v$version"
}

// Chunk reassembly (spec section 4)

sealed class AssemblerEvent {
    object NeedMore : AssemblerEvent()
    /** The whole file, id through CRC trailer; the CRC is already checked. */
    class Complete(val file: ByteArray) : AssemblerEvent()
    class Failed(val reason: String, val crcMismatch: Boolean = false) : AssemblerEvent()
}

/**
 * Joins decrypted channel-5 packets into one file. Each packet: total u16 LE | num u16 LE
 * (1-based) | the next slice. num == total closes the file. Any chunk other than the expected next
 * one, or a change of total, drops the buffer and fails the file; a num == 1 chunk always starts a
 * new file.
 */
class ActivityAssembler(private val maxFileSize: Int = 16 shl 20) {
    companion object {
        /** id(7) + pad(1) + CRC(4): an empty file means the band has no records of its kind. */
        const val MINIMUM_FILE_LENGTH = 12
    }

    private val buffer = java.io.ByteArrayOutputStream()
    private var total = 0
    private var nextNum = 1

    val isAssembling: Boolean get() = total != 0
    val progress: Pair<Int, Int>? get() = if (isAssembling) (nextNum - 1) to total else null

    fun reset() { buffer.reset(); total = 0; nextNum = 1 }

    fun add(packet: ByteArray): AssemblerEvent {
        if (packet.size < 4) { reset(); return AssemblerEvent.Failed("chunk of ${packet.size} bytes has no header") }
        val t = (packet[0].toInt() and 0xFF) or ((packet[1].toInt() and 0xFF) shl 8)
        val num = (packet[2].toInt() and 0xFF) or ((packet[3].toInt() and 0xFF) shl 8)
        when {
            num == 1 -> {
                reset()
                if (t < 1) return AssemblerEvent.Failed("first chunk announces a total of 0")
                total = t
            }
            !isAssembling -> return AssemblerEvent.Failed("chunk $num/$t out of sequence, expected 1")
            t != total -> { val e = total; reset(); return AssemblerEvent.Failed("chunk $num/$t changes the total from $e") }
            num != nextNum -> { val e = nextNum; reset(); return AssemblerEvent.Failed("chunk $num/$t out of sequence, expected $e") }
        }
        if (packet.size - 4 > maxFileSize - buffer.size()) { reset(); return AssemblerEvent.Failed("file grew past $maxFileSize bytes") }
        buffer.write(packet, 4, packet.size - 4)
        nextNum = num + 1
        if (num < total) return AssemblerEvent.NeedMore
        val file = buffer.toByteArray()
        reset()
        if (file.size < MINIMUM_FILE_LENGTH) return AssemblerEvent.Failed("file of ${file.size} bytes is shorter than the $MINIMUM_FILE_LENGTH-byte minimum")
        val (stored, computed) = ActivityCRC32.trailer(file)!!
        if (stored != computed) return AssemblerEvent.Failed("crc stored ${stored.toString(16)} computed ${computed.toString(16)}", crcMismatch = true)
        return AssemblerEvent.Complete(file)
    }
}

// Shared payload pieces

/** Validity bitmap, MSB first: field i is bit 7 - i % 8 of bitmap[i / 8] (spec section 5.1). */
private fun bitValid(bitmap: ByteArray, index: Int): Boolean {
    if (index < 0 || index / 8 >= bitmap.size) return false
    return (bitmap[index / 8].toInt() and (0x80 shr (index % 8))) != 0
}

enum class SleepStage(val raw: String) {
    AWAKE("awake"), LIGHT("light"), DEEP("deep"), REM("rem"), NOT_SLEEP("not_sleep"), UNKNOWN("unknown");

    companion object {
        /** Raw code in a sleep details type-17 packet (spec section 8, first table). */
        fun details(code: Int) = when (code) { 0 -> AWAKE; 1 -> LIGHT; 2 -> DEEP; 3 -> REM; 4 -> NOT_SLEEP; else -> UNKNOWN }
        /** Phase byte in a sleep stages file (spec section 8, second table). */
        fun stored(code: Int) = when (code) { 2 -> DEEP; 3 -> LIGHT; 4 -> REM; 5 -> AWAKE; else -> UNKNOWN }
    }
}

data class SleepStageSample(val ts: Long, val stage: SleepStage, val raw: Int, val durationMinutes: Int?)

/** One night (or nap) as the band bounds it. */
data class SleepSession(
    val bedTime: Long, val wakeTime: Long,
    val deepMinutes: Int?, val lightMinutes: Int?, val remMinutes: Int?, val awakeMinutes: Int?,
    val stages: List<SleepStageSample>,
) {
    companion object {
        /** Keeps the latest wake time per bed time (spec section 8), sorted by bed time. */
        fun merge(sessions: List<SleepSession>): List<SleepSession> {
            val latest = LinkedHashMap<Long, SleepSession>()
            for (s in sessions) { val kept = latest[s.bedTime]; if (kept != null && kept.wakeTime >= s.wakeTime) continue; latest[s.bedTime] = s }
            return latest.values.sortedBy { it.bedTime }
        }
    }
}

// Daily details (spec section 6.1)

/** ACTIVITY / DAILY / DETAILS: one record per minute. */
data class DailyDetails(val version: Int, val header: ByteArray, val records: List<Record>, val truncatedTail: Int) {
    data class Record(
        val ts: Long,
        var steps: Int? = null,
        var activeCalories: Int? = null,
        /** 0 quiet … 5 severe; a still wrist reports 7. 3…5 are the band's "Moving" minutes. */
        var activityLevel: Int? = null,
        var distanceMeters: Int? = null,
        /** Raw bpm; 0 likely means not measured. */
        var heartRate: Int? = null,
        var energy: Int? = null,
        var spo2: Int? = null,
        /** 255 (none) is already null; 0 means none. */
        var stress: Int? = null,
        var extraByte: Int? = null,
        var lightRaw: Int? = null,
        var bodyMomentumRaw: Int? = null,
    )

    private class Group(val raw: Long, val width: Int, val nibble: Int) {
        fun valid(slot: Int) = (nibble and (0x4 shr slot)) != 0
        fun bits(start: Int, count: Int): Int = ((raw ushr (width - start - count)) and ((1L shl count) - 1)).toInt()
    }

    private class Groups(val header: ByteArray) {
        var index = 0
        fun next(width: Int, r: ActivityByteReader): Group? {
            val i = index++
            if (i / 2 >= header.size) return null
            val b = header[i / 2].toInt() and 0xFF
            val nibble = if (i % 2 == 0) b shr 4 else b and 0x0F
            if (nibble and 0x8 == 0) return null
            return Group(r.uintLE(width / 8), width, nibble)
        }
    }

    companion object {
        val headerLengths = mapOf(1 to 4, 2 to 4, 3 to 5, 4 to 6)

        private fun record(version: Int, header: ByteArray, r: ActivityByteReader, ts: Long): Record {
            val g = Groups(header)
            val rec = Record(ts)
            var extra = false
            g.next(16, r)?.let { x ->  // g0: [15] unknown | [14] extra-byte flag | [13..0] steps
                if (x.valid(1)) extra = x.bits(1, 1) == 1
                if (x.valid(2)) rec.steps = x.bits(2, 14)
            }
            g.next(8, r)?.let { if (it.valid(1)) rec.activeCalories = it.bits(2, 6) }
            g.next(8, r)?.let { if (it.valid(0)) rec.activityLevel = it.bits(0, 3) }
            g.next(16, r)?.let { if (it.valid(0)) rec.distanceMeters = it.raw.toInt() }
            g.next(8, r)?.let { if (it.valid(0)) rec.heartRate = it.raw.toInt() }
            g.next(8, r)?.let { if (it.valid(0)) rec.energy = it.raw.toInt() }
            g.next(16, r)  // g6: unknown
            if (version >= 3) {
                g.next(8, r)?.let { if (it.valid(0)) rec.spo2 = it.raw.toInt() }
                g.next(8, r)?.let { if (it.valid(0) && it.raw != 0xFFL) rec.stress = it.raw.toInt() }
            }
            if (extra) rec.extraByte = r.u8()
            if (version >= 4) {
                g.next(16, r)?.let { if (it.valid(0)) rec.lightRaw = it.raw.toInt() }
                g.next(16, r)?.let { if (it.valid(0)) rec.bodyMomentumRaw = it.raw.toInt() }
            }
            return rec
        }

        fun parse(body: ByteArray, id: ActivityFileID, warnings: MutableList<String>): DailyDetails {
            val headerLength = headerLengths[id.version] ?: throw ActivityParseError.Malformed("daily details version ${id.version} unsupported")
            val r = ActivityByteReader(body)
            val header = r.take(headerLength)
            val records = mutableListOf<Record>()
            var tail = 0
            while (r.remaining > 0) {
                val start = r.position
                val rec = try {
                    record(id.version, header, r, id.timestamp + 60L * records.size)
                } catch (e: ActivityParseError) {
                    tail = r.end - start
                    warnings += "daily details: partial record of $tail byte(s) after ${records.size} record(s)"
                    break
                }
                if (r.position <= start) {
                    tail = r.remaining
                    warnings += "daily details: header ${activityHex(header)} marks no group present, $tail byte(s) unread"
                    break
                }
                records += rec
            }
            return DailyDetails(id.version, header, records, tail)
        }
    }
}

// Daily summary (spec section 6.2)

/** ACTIVITY / DAILY / SUMMARY: one record for the day. Every slot is consumed; the validity bit decides if it is used. */
data class DailySummary(val version: Int, val day: Long, val tzQuarterHours: Int, val bitmap: ByteArray, val slots: List<Int?>, val trailingBytes: Int) {
    fun slot(i: Int): Int? = slots.getOrNull(i)
    val steps get() = slot(0)
    val activeCalories get() = slot(1)
    val hrResting get() = slot(3)
    val hrMax get() = slot(4)
    val hrMaxTs get() = slot(5)?.toLong()
    val hrMin get() = slot(6)
    val hrMinTs get() = slot(7)?.toLong()
    val hrAvg get() = slot(8)
    val stressAvg get() = slot(9)
    val stressMax get() = slot(10)
    val stressMin get() = slot(11)
    /** 24-bit mask: bit h set = stood during hour h. */
    val standing get() = slot(12)
    val standingHours: List<Int>? get() = standing?.let { m -> (0 until 24).filter { (m shr it) and 1 == 1 } }
    val calories get() = slot(13)
    val recoveryHours get() = slot(14)
    val spo2Max get() = slot(16)
    val spo2MaxTs get() = slot(17)?.toLong()
    val spo2Min get() = slot(18)
    val spo2MinTs get() = slot(19)?.toLong()
    val spo2Avg get() = slot(20)
    val trainingLoadDay get() = slot(21)
    val trainingLoadWeek get() = slot(22)
    val trainingLoadLevel get() = slot(23)
    val vitalityIncreaseLight get() = slot(24)
    val vitalityIncreaseModerate get() = slot(25)
    val vitalityIncreaseHigh get() = slot(26)
    val vitalityCurrent get() = slot(27)

    companion object {
        val slotSizes = intArrayOf(4, 2, 1, 1, 1, 4, 1, 4, 1, 1, 1, 1, 3, 2, 2, 1, 1, 4, 1, 4, 1, 2, 2, 1, 1, 1, 1, 2, 1, 1, 2, 2)
        val layouts = mapOf(3 to (3 to 21), 4 to (3 to 21), 5 to (4 to 32))
        /** A Band 10 Pro v5 file stops after slot 29; slots past the v3/v4 set may be missing. */
        const val BASE_SLOTS = 21

        fun parse(body: ByteArray, id: ActivityFileID): DailySummary {
            val (bitmapBytes, count) = layouts[id.version] ?: throw ActivityParseError.Malformed("daily summary version ${id.version} unsupported")
            val r = ActivityByteReader(body)
            val bitmap = r.take(bitmapBytes)
            val slots = MutableList<Int?>(slotSizes.size) { null }
            for (i in 0 until minOf(count, slotSizes.size)) {
                if (i >= BASE_SLOTS && r.remaining == 0) break
                val v = r.uintLE(slotSizes[i])
                slots[i] = if (bitValid(bitmap, i)) v.toInt() else null
            }
            return DailySummary(id.version, id.timestamp, id.tzQuarterHours, bitmap, slots, r.remaining)
        }
    }
}

// Sleep details (spec section 6.3)

/** unit i16 | count i16 | first ts u32 (count > 0 and v2+; v1 uses bed time) | values. */
data class SleepSampleBlock<V>(val unit: Int, val count: Int, val firstTs: Long?, val values: List<V>) {
    val samples: List<Pair<Long, V>> get() = firstTs?.let { f -> values.mapIndexed { i, v -> (f + unit.toLong() * i) to v } } ?: emptyList()
}

data class SleepPacket(val type: Int, val tsRaw: Long, val parity: Int, val headerLength: Int, val flags: Int?, val length: Int?, val payload: ByteArray?)

data class SleepSummaryPacket(
    val ts: Long, val sleepIndex: Int, val wakeCount: Int, val sleepMinutes: Int, val awakeMinutes: Int,
    val lightMinutes: Int, val remMinutes: Int, val deepMinutes: Int, val flags: Int, val unknownMinutes: Int,
)

data class SleepRRPacket(val tsRaw: Long, val intervalsMs: List<Int>)
data class SleepBreathSample(val ts: Long, val breathsPerMinute: Int)
data class TimeSpan(val start: Long, val end: Long) { val duration: Long get() = end - start }

/** ACTIVITY / SLEEP / any detail type. */
data class SleepDetails(
    val version: Int, val bitmap: ByteArray, val isAwake: Int, val bedTime: Long, val wakeTime: Long,
    val sleepQuality: Int?, val unknownV5: ByteArray?, val bedTime2: Long?, val wakeTime2: Long?, val unknownV6: ByteArray?,
    val heartRate: SleepSampleBlock<Int>?, val spo2: SleepSampleBlock<Int>?, val snore: SleepSampleBlock<Float>?,
    val blocksError: String?, val packets: List<SleepPacket>, val stages: List<SleepStageSample>,
    /** The last type-17 packet per ts (the band re-sends the night as it goes), ordered by ts. */
    val nightStages: List<SleepStageSample>,
    val summaries: List<SleepSummaryPacket>, val rrIntervals: List<SleepRRPacket>, val respiratoryRate: List<SleepBreathSample>,
    /** Fell-asleep (type 2) and woke-up (type 3) events paired into spans of sleep. */
    val sleepSpans: List<TimeSpan>,
    val skippedBytes: Int, val trailingBytes: Int, val streamError: String?,
) {
    /** A file with neither summary nor stages but with fell-asleep/woke-up events takes its session from the events. */
    val sessionFromSpans: Boolean get() = summaries.isEmpty() && nightStages.isEmpty() && sleepSpans.isNotEmpty()

    val session: SleepSession? get() {
        if (sessionFromSpans) {
            val first = sleepSpans.first(); val last = sleepSpans.last()
            val asleep = sleepSpans.sumOf { it.duration }
            val awake = Math.round((last.end - first.start - asleep) / 60.0).toInt()
            return SleepSession(first.start, last.end, null, null, null, awake, emptyList())
        }
        if (bedTime <= 0 || wakeTime <= 0 || wakeTime < bedTime) return null
        var deep: Int? = null; var light: Int? = null; var rem: Int? = null; var awake: Int? = null
        val s = summaries.lastOrNull()
        if (s != null) {
            deep = s.deepMinutes; light = s.lightMinutes; rem = s.remMinutes; awake = s.awakeMinutes
        } else if (nightStages.isNotEmpty()) {
            fun total(st: SleepStage) = nightStages.filter { it.stage == st }.sumOf { it.durationMinutes ?: 0 }
            deep = total(SleepStage.DEEP); light = total(SleepStage.LIGHT); rem = total(SleepStage.REM); awake = total(SleepStage.AWAKE)
        }
        return SleepSession(bedTime, wakeTime, deep, light, rem, awake, nightStages)
    }

    companion object {
        val bitmapLengths = mapOf(1 to 1, 2 to 1, 3 to 1, 4 to 1, 5 to 2, 6 to 3)
        const val V6_EXTRA = 24
        const val PACKET_HEADER = 17
        val payloadless = setOf(2, 3, 9, 12, 13, 14, 15, 18, 19)
        const val BREATH_RECORD = 4
        const val BREATH_WINDOW = 600L
        val breathRange = 1..40

        private fun <V> block(version: Int, bedTime: Long, r: ActivityByteReader, read: (ActivityByteReader) -> V): SleepSampleBlock<V> {
            val unit = r.i16()
            val count = r.i16()
            if (count < 0) throw ActivityParseError.Malformed("sleep sample block count $count")
            if (count == 0) return SleepSampleBlock(unit, 0, null, emptyList())
            val first = if (version >= 2) r.u32() else bedTime
            val values = ArrayList<V>(minOf(count, r.remaining))
            repeat(count) { values += read(r) }
            return SleepSampleBlock(unit, count, first, values)
        }

        fun parse(body: ByteArray, id: ActivityFileID, warnings: MutableList<String>): SleepDetails {
            val v = id.version
            val bitmapLength = bitmapLengths[v] ?: throw ActivityParseError.Malformed("sleep details version $v unsupported")
            var r = ActivityByteReader(body)
            val bitmap = r.take(bitmapLength)
            val isAwake = r.u8()
            val bed = r.u32()
            val wake = r.u32()
            var idx = 3
            var quality: Int? = null
            if (v >= 4) { if (bitValid(bitmap, idx)) quality = r.u8(); idx++ }
            var unknownV5: ByteArray? = null; var bed2: Long? = null; var wake2: Long? = null
            if (v >= 5) { unknownV5 = r.take(9); bed2 = r.u32(); wake2 = r.u32(); idx += 5 }
            val unknownV6 = if (v >= 6) r.take(V6_EXTRA) else null
            val blocksStart = r.copy()
            var hr: SleepSampleBlock<Int>? = null; var spo2: SleepSampleBlock<Int>? = null; var snore: SleepSampleBlock<Float>? = null
            var blocksError: String? = null
            try {
                if (bitValid(bitmap, idx)) hr = block(v, bed, r) { it.u8() }
                idx++
                if (bitValid(bitmap, idx)) spo2 = block(v, bed, r) { it.u8() }
                idx++
                if (v >= 3) {
                    if (bitValid(bitmap, idx)) snore = block(v, bed, r) { if (v >= 6) it.u16().toFloat() else it.f32() }
                    idx++
                }
                if (v >= 6 && r.remaining > 0 && magic(body, r.position, r.end) != r.position) {
                    throw ActivityParseError.Malformed("sample blocks end at offset ${r.position}, not at a packet")
                }
            } catch (e: ActivityParseError) {
                if (v < 6) throw e
                // v6 field bits are a guess (spec section 6.3.1): keep the stream rather than lose the night.
                blocksError = e.message
                warnings += "sleep details v6: sample blocks dropped: ${e.message}"
                hr = null; spo2 = null; snore = null
                r = blocksStart
            }
            val s = scan(body, r.position, r.end)
            s.error?.let { warnings += "sleep details: $it" }
            return SleepDetails(
                v, bitmap, isAwake, bed, wake, quality, unknownV5, bed2, wake2, unknownV6, hr, spo2, snore, blocksError,
                s.packets, s.stages, s.lastStagesByTs.keys.sorted().flatMap { s.lastStagesByTs[it] ?: emptyList() },
                s.summaries, s.rr, s.breaths, sleepSpans(s.packets), s.skipped, s.trailing, s.error,
            )
        }

        /** Packet timestamps to Unix seconds; the unit (s, ms, µs, ns) is told by the magnitude. */
        fun packetSeconds(raw: Long): Long {
            val m = kotlin.math.abs(raw)
            return when {
                m >= 100_000_000_000_000_000L -> raw / 1_000_000_000
                m >= 100_000_000_000_000L -> raw / 1_000_000
                m >= 100_000_000_000L -> raw / 1_000
                else -> raw
            }
        }

        /** Type 2 and 3 events paired in time order; a second fall-asleep keeps the first, a lone wake-up is dropped. */
        fun sleepSpans(packets: List<SleepPacket>): List<TimeSpan> {
            val events = packets.filter { it.type == 2 || it.type == 3 }.map { packetSeconds(it.tsRaw) to it.type }
                .sortedWith(compareBy({ it.first }, { it.second }))
            val spans = mutableListOf<TimeSpan>()
            var start: Long? = null
            for ((sec, type) in events) {
                if (type == 2) { if (start == null) start = sec }
                else if (start != null) { if (sec > start) spans += TimeSpan(start, sec); start = null }
            }
            return spans
        }

        private class Stream {
            val packets = mutableListOf<SleepPacket>()
            val stages = mutableListOf<SleepStageSample>()
            val lastStagesByTs = HashMap<Long, List<SleepStageSample>>()
            val summaries = mutableListOf<SleepSummaryPacket>()
            val rr = mutableListOf<SleepRRPacket>()
            val breaths = mutableListOf<SleepBreathSample>()
            var skipped = 0
            var trailing = 0
            var error: String? = null
        }

        private fun magic(d: ByteArray, from: Int, end: Int): Int? {
            val last = end - PACKET_HEADER
            if (from < 0 || from > last || end > d.size) return null
            for (i in from..last) {
                if (d[i] == 0xFB.toByte() && d[i + 1] == 0xFA.toByte() && d[i + 2] == 0xFC.toByte() && d[i + 3] == 0xFF.toByte()) return i
            }
            return null
        }

        /** Packet: magic FB FA FC FF | header length u8 | ts i64 LE | parity u8 | type u8 | length u16 BE | payload (big-endian contents). */
        private fun scan(d: ByteArray, start: Int, end: Int): Stream {
            val s = Stream()
            var pos = start
            try {
                while (end - pos >= PACKET_HEADER) {
                    val at = magic(d, pos, end) ?: break
                    s.skipped += at - pos
                    val h = ActivityByteReader(d, at + 4, end)
                    val headerLength = h.u8()
                    val ts = h.i64()
                    val parity = h.u8()
                    val type = h.u8()
                    val lengthField = h.u16BE()
                    pos = h.position
                    if (type in payloadless) {
                        s.packets += SleepPacket(type, ts, parity, headerLength, lengthField, null, null)
                        continue
                    }
                    val length = lengthField
                    if (length > end - pos) {
                        s.packets += SleepPacket(type, ts, parity, headerLength, null, null, null)
                        s.error = "packet type $type wants $length bytes, ${end - pos} left"
                        pos = end
                        break
                    }
                    val p = ActivityByteReader(d, pos, pos + length)
                    pos += length
                    val payload = d.copyOfRange(p.position, p.end)
                    s.packets += SleepPacket(type, ts, parity, headerLength, null, length, payload)
                    when {
                        type == 17 -> {  // stages: u16 BE words, stage = top 4 bits, minutes = low 12 bits
                            var t = ts
                            val run = mutableListOf<SleepStageSample>()
                            while (p.remaining >= 2) {
                                val word = p.u16BE()
                                val code = word shr 12
                                val minutes = word and 0x0FFF
                                run += SleepStageSample(t, SleepStage.details(code), code, minutes)
                                t += minutes * 60L
                            }
                            s.stages += run
                            s.lastStagesByTs[ts] = run
                        }
                        type == 16 && length >= 13 -> {
                            val b0 = p.u8()
                            s.summaries += SleepSummaryPacket(ts, b0 shr 4, b0 and 0x0F, p.u16BE(), p.u16BE(), p.u16BE(), p.u16BE(), p.u16BE(), p.u8(), p.u8())
                        }
                        type == 1 -> s.rr += SleepRRPacket(ts, payload.map { (it.toInt() and 0xFF) * 10 })
                        type == 10 -> {
                            val count = length / BREATH_RECORD
                            val first = packetSeconds(ts) - BREATH_WINDOW
                            for (i in 0 until count) {
                                val rate = payload[i * BREATH_RECORD + 1].toInt() and 0xFF
                                if (rate !in breathRange) continue
                                s.breaths += SleepBreathSample(first + (i + 1) * BREATH_WINDOW / (count + 1), rate)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                s.error = e.message
            }
            s.trailing = maxOf(end - pos, 0)
            return s
        }
    }
}

// Sleep stages (spec section 6.4)

/** ACTIVITY / SLEEP_STAGES / DETAILS, version 2. Negative durations are treated as missing in the session. */
data class SleepStages(
    val version: Int, val unknown1: ByteArray, val totalMinutes: Int, val bedTime: Long, val wakeTime: Long, val unknown2: ByteArray,
    val deepMinutes: Int, val lightMinutes: Int, val remMinutes: Int, val awakeMinutes: Int, val unknown3: Int,
    val noData: Boolean, val stages: List<SleepStageSample>, val trailingBytes: Int,
) {
    val session: SleepSession? get() {
        if (noData) return null
        fun m(x: Int) = x.takeIf { it >= 0 }
        return SleepSession(bedTime, wakeTime, m(deepMinutes), m(lightMinutes), m(remMinutes), m(awakeMinutes), stages)
    }

    companion object {
        fun parse(body: ByteArray, id: ActivityFileID): SleepStages {
            if (id.version != 2) throw ActivityParseError.Malformed("sleep stages version ${id.version} unsupported")
            val r = ActivityByteReader(body)
            val u1 = r.take(7)
            val total = r.i16()
            val bed = r.u32()
            val wake = r.u32()
            val u2 = r.take(3)
            val deep = r.i16(); val light = r.i16(); val rem = r.i16(); val awake = r.i16()
            val u3 = r.u8()
            val stages = mutableListOf<SleepStageSample>()
            while (r.remaining >= 5) {
                val ts = r.u32(); val code = r.u8()
                stages += SleepStageSample(ts, SleepStage.stored(code), code, null)
            }
            return SleepStages(id.version, u1, total, bed, wake, u2, deep, light, rem, awake, u3,
                bed == 0L || wake == 0L || total == 0, stages, r.remaining)
        }
    }
}

// Manual samples (spec section 6.5)

data class ManualSample(val ts: Long, val type: Int, val kind: Kind, val value: Long) {
    enum class Kind(val raw: String) { HEART_RATE("heart_rate"), SPO2("spo2"), STRESS("stress"), TEMPERATURE("temperature") }

    val celsius: Double? get() = if (kind == Kind.TEMPERATURE) (value and 0xFFFF) / 100.0 else null
    val temperatureHigh16: Long? get() = if (kind == Kind.TEMPERATURE) value shr 16 else null

    companion object {
        const val HEART_RATE = 0x11
        const val SPO2 = 0x12
        const val STRESS = 0x13
        const val TEMPERATURE = 0x44
    }
}

/** ACTIVITY / MANUAL_SAMPLES / DETAILS, versions 1 and 2. */
data class ManualSamples(val version: Int, val samples: List<ManualSample>, val zeroValueRecords: Int, val stoppedReason: String?, val trailingBytes: Int) {
    companion object {
        private fun kind(t: Int) = when (t) {
            ManualSample.HEART_RATE -> ManualSample.Kind.HEART_RATE
            ManualSample.SPO2 -> ManualSample.Kind.SPO2
            ManualSample.STRESS -> ManualSample.Kind.STRESS
            ManualSample.TEMPERATURE -> ManualSample.Kind.TEMPERATURE
            else -> null
        }

        fun parse(body: ByteArray, id: ActivityFileID, pad: Int, warnings: MutableList<String>): ManualSamples {
            val r = ActivityByteReader(body)
            when (id.version) {
                1 -> {
                    // Strict, as upstream: any deviation rejects the whole file.
                    if (pad != 0) throw ActivityParseError.Malformed("manual samples v1 pad byte ${hexByte(pad)}")
                    // 0xFF on a Mi Band 9 Active, 0xE0 on a Redmi Watch 5 Lite (docs/ACTIVITY.md 6.5).
                    if (r.u8() !in setOf(0xFF, 0xE0)) throw ActivityParseError.Malformed("manual samples v1 header byte is not 0xff or 0xe0")
                    if (r.remaining == 0 || r.remaining % 6 != 0) throw ActivityParseError.Malformed("manual samples v1 payload of ${r.remaining} bytes")
                    val samples = mutableListOf<ManualSample>()
                    while (r.remaining > 0) {
                        val ts = r.u32(); val code = r.u8(); val value = r.u8()
                        val type = when (code) { 0x01 -> ManualSample.HEART_RATE; 0x02 -> ManualSample.SPO2; 0x03 -> ManualSample.STRESS
                            else -> throw ActivityParseError.Malformed("manual samples v1 type ${hexByte(code)}") }
                        if (value !in 1..(if (type == ManualSample.HEART_RATE) 250 else 100)) throw ActivityParseError.Malformed("manual samples v1 value $value")
                        samples += ManualSample(ts, type, kind(type)!!, value.toLong())
                    }
                    return ManualSamples(1, samples, 0, null, 0)
                }
                2 -> {
                    val samples = mutableListOf<ManualSample>()
                    var zero = 0
                    var stopped: String? = null
                    var trailing = 0
                    while (r.remaining > 0) {
                        val at = r.position
                        try {
                            val ts = r.u32()
                            val type = r.u8()
                            val k = kind(type)
                            if (k == null) {
                                stopped = "unknown sample type ${hexByte(type)} at payload offset $at"
                                trailing = r.end - at
                                break
                            }
                            val value = if (k == ManualSample.Kind.TEMPERATURE) r.u32() else r.u8().toLong()
                            if (value == 0L) { zero++; continue }
                            samples += ManualSample(ts, type, k, value)
                        } catch (e: ActivityParseError) {
                            stopped = "partial record of ${r.end - at} byte(s) at payload offset $at"
                            trailing = r.end - at
                            break
                        }
                    }
                    stopped?.let { warnings += "manual samples: $it" }
                    return ManualSamples(2, samples, zero, stopped, trailing)
                }
                else -> throw ActivityParseError.Malformed("manual samples version ${id.version} unsupported")
            }
        }
    }
}

// Workout summary (spec section 6.6)

/** SPORTS / * / SUMMARY. Values are raw (the validity header is not applied); a key is missing only when the layout has no such field. */
data class WorkoutSummary(val sport: String, val values: Map<String, Double>) {
    fun int(key: String): Int? = values[key]?.toInt()
    val start: Long get() = (values["start"] ?: 0.0).toLong()
    val end: Long get() = (values["end"] ?: 0.0).toLong()

    companion object {
        val workoutTypes = mapOf(
            1 to "outdoor_running", 2 to "walking", 3 to "hiking", 4 to "trekking", 5 to "trail_running", 6 to "outdoor_cycling",
            7 to "indoor_cycling", 8 to "freestyle", 15 to "outdoor_walking", 16 to "hiit", 333 to "indoor_walking", 399 to "indoor_fitness",
        )

        /** Sport name and validity header length by version, for the subtypes that have a table. */
        fun headers(subtype: Int): Pair<String, Map<Int, Int>>? = when (subtype) {
            0x03 -> "treadmill" to mapOf(5 to 4, 9 to 6, 10 to 8, 11 to 9)
            0x07 -> "indoor_cycling" to mapOf(8 to 7, 9 to 8)
            0x08 -> "freestyle" to mapOf(5 to 3, 7 to 5, 8 to 6, 9 to 6, 10 to 6)
            0x0B -> "elliptical" to mapOf(3 to 4, 4 to 4, 5 to 4, 6 to 4)
            0x0D -> "rowing" to mapOf(4 to 4, 6 to 5, 7 to 5, 8 to 6)
            0x16 -> "workout" to mapOf(1 to 5, 2 to 6, 4 to 7, 5 to 9, 6 to 9, 9 to 13)
            0x17 -> "outdoor_cycling" to mapOf(4 to 6, 5 to 6, 6 to 7, 8 to 8)
            else -> null
        }

        /** Fields in file order: `name:1|2|4` unsigned, `name:f` f32, `_:n` skipped, `route` the route byte. */
        fun fields(subtype: Int, v: Int): String {
            val times = "start:4 end:4 active_s:4"
            val hr = "hr_avg:1 hr_max:1 hr_min:1"
            val zones = "zone_extreme_s:4 zone_anaerobic_s:4 zone_aerobic_s:4 zone_fat_burn_s:4 zone_warm_up_s:4"
            val loadVitality = "load:2 _:15 vitality:1"
            return when (subtype) {
                0x01, 0x02 -> "$times distance_m:4 calories:4 _:12 steps:4 _:2 $hr"
                0x16 -> buildString {
                    append("workout_type:2 $times _:4 distance_m:4 total_calories:2 calories:2")
                    append((if (v >= 5) " pace_avg:4" else "") + " pace_max:4 pace_min:4" + (if (v >= 5) " speed_avg:f" else ""))
                    append(" speed_max:f steps:4" + (if (v >= 5) " step_length_cm:2 cadence_avg:2" else ""))
                    append(" cadence_max:2 $hr _:20 te_aerobic:f")
                    append(if (v == 1) " _:9" else " _:1 te_anaerobic:f _:${if (v >= 9) 6 else 1} vo2max:1 _:2 recovery_h:2 _:1")
                    append(" $zones")
                    if (v == 6) append(" _:21 $loadVitality")
                    if (v == 9) append(" _:46 $loadVitality")
                }
                0x03 -> buildString {
                    append("$times distance_m:4 calories:2" + (if (v >= 10) " pace_avg:4" else "") + " pace_max:4 pace_min:4 steps:4")
                    append((if (v >= 10) " step_length_cm:2 cadence_avg:2" else "") + " cadence_max:2 $hr te_aerobic:f")
                    append(if (v == 9) " _:4" else if (v == 5) " vo2max:1 _:1" else " _:1 vo2max:1 _:2")
                    append(" recovery_h:2 $zones")
                    if (v == 9) append(" _:39 load:2 _:7 vitality:1")
                    if (v == 11) append(" total_calories:2 _:4 te_anaerobic:f _:25 distance_calibrated_m:4 $loadVitality")
                }
                0x08 -> buildString {
                    append("$times calories:2 $hr" + (if (v > 5) " _:6" else "") + " te_aerobic:f _:${if (v > 6) 2 else 1} recovery_h:2 $zones")
                    if (v == 5) append(" _:10 workout_type:2")
                    else append(" total_calories:2 _:4 te_anaerobic:f _:1 workout_type:2 _:8 load:2 _:1" + (if (v > 7) " vitality:1" else ""))
                }
                0x07 -> "$times _:4 calories:2 _:4 $hr te_aerobic:f _:2 recovery_h:2 $zones _:6 te_anaerobic:f _:39 load:2"
                0x17 -> buildString {
                    append("workout_type:2 $times _:4 distance_m:4 total_calories:2 calories:2 pace_max:4 pace_min:4")
                    append((if (v >= 5) " speed_avg:f" else "") + " speed_max:f $hr _:20 te_aerobic:f _:1 te_anaerobic:f")
                    append(" _:${if (v >= 7) 6 else 1} vo2max:1 _:2 recovery_h:2 _:1 $zones")
                    if (v >= 6) append(" _:${if (v >= 7) 19 else 14} route $loadVitality")
                }
                0x0B -> buildString {
                    append("$times calories:2 steps:4" + (if (v >= 6) " cadence_avg:2" else "") + " cadence_max:2 $hr te_aerobic:f")
                    append(" _:${if (v >= 4) 2 else 1} recovery_h:2 $zones")
                    if (v >= 6) append(" total_calories:2 _:4 te_anaerobic:f _:1 route _:9 load:2 _:1 vitality:1")
                }
                0x0D -> buildString {
                    append("$times calories:2 $hr" + (if (v >= 7) " te_aerobic:f _:2 recovery_h:2" else " _:${if (v > 4) 8 else 7}"))
                    append(" $zones" + (if (v >= 8) " _:5" else "") + (if (v >= 7) " total_calories:2" else " _:2") + " strokes:4 stroke_rate_avg:4")
                    if (v >= 7) append(" stroke_rate_max:4 _:17 te_anaerobic:f _:${if (v >= 8) 6 else 1} _:10 load:2 _:1 vitality:1")
                }
                else -> ""
            }
        }

        /** Null when the subtype or version has no table. */
        fun parse(body: ByteArray, id: ActivityFileID): WorkoutSummary? {
            val (sport, sizes) = headers(id.subtype) ?: return null
            val header = sizes[id.version] ?: return null
            val r = ActivityByteReader(body)
            r.skip(header)  // validity bitmap, not applied
            val values = LinkedHashMap<String, Double>()
            for (field in fields(id.subtype, id.version).split(" ")) {
                val parts = field.split(":")
                val name = parts[0]
                when {
                    name == "route" -> if (r.u8() in setOf(0xFC, 0xFD, 0xFF)) r.skip(8)
                    name == "_" -> r.skip(parts[1].toIntOrNull() ?: 0)
                    parts[1] == "f" -> values[name] = r.f32().toDouble()
                    else -> values[name] = r.uintLE(parts[1].toIntOrNull() ?: 1).toDouble()
                }
            }
            val named = values["workout_type"]?.let { workoutTypes[it.toInt()] } ?: sport
            return WorkoutSummary(named, values)
        }
    }
}

// Dispatch

sealed class ActivityPayload {
    class Daily(val details: DailyDetails) : ActivityPayload()
    class Summary(val summary: DailySummary) : ActivityPayload()
    class Sleep(val details: SleepDetails) : ActivityPayload()
    class Stages(val stages: SleepStages) : ActivityPayload()
    class Manual(val samples: ManualSamples) : ActivityPayload()
    class Workout(val summary: WorkoutSummary) : ActivityPayload()
    /** Nothing between the pad and a matching CRC: the band has no records of this kind. */
    object Empty : ActivityPayload()
    /** Workout details, GPS, sports without a table and unknown kinds (version null), or an unknown version. */
    class Unsupported(val kind: ActivityFileKind, val version: Int?) : ActivityPayload()
    class Error(val error: ActivityParseError) : ActivityPayload()
}

class ActivityParseResult(
    val fileID: ActivityFileID?,
    val pad: Int?,
    /** stored to computed; null without a CRC trailer. */
    val crc: Pair<Long, Long>?,
    val payloadLength: Int,
    val payload: ActivityPayload,
    val warnings: List<String>,
) {
    val kind: ActivityFileKind get() = fileID?.kind ?: ActivityFileKind.UNKNOWN
    val isParsed: Boolean get() = payload !is ActivityPayload.Unsupported && payload !is ActivityPayload.Error
}

object ActivityParser {
    /** A whole file as the assembler returns it: id(7) | pad(1) | body | CRC32 LE(4). Without a CRC (test vectors), `hasCRC` false. */
    fun parse(file: ByteArray, hasCRC: Boolean = true, expected: ActivityFileID? = null): ActivityParseResult {
        val id = if (file.size >= 7) ActivityFileID.of(file) else null
        val minimum = if (hasCRC) ActivityAssembler.MINIMUM_FILE_LENGTH else 8
        if (file.size < minimum || id == null) {
            return ActivityParseResult(id, if (file.size >= 8) file[7].toInt() and 0xFF else null, null, 0,
                ActivityPayload.Error(ActivityParseError.FileTooShort(file.size, minimum)), emptyList())
        }
        val pad = file[7].toInt() and 0xFF
        var end = file.size
        var crc: Pair<Long, Long>? = null
        if (hasCRC) { crc = ActivityCRC32.trailer(file); end -= 4 }
        val body = file.copyOfRange(8, end)
        if (crc != null && crc.first != crc.second) {
            return ActivityParseResult(id, pad, crc, body.size, ActivityPayload.Error(ActivityParseError.CrcMismatch(crc.first, crc.second)), emptyList())
        }
        val warnings = mutableListOf<String>()
        if (expected != null && expected != id) warnings += "file carries id ${id.hex}, ${expected.hex} was requested"
        if (pad != 0) warnings += "pad byte ${hexByte(pad)} is not 0"

        val kind = id.kind
        fun guarded(work: () -> ActivityPayload): ActivityPayload = try { work() } catch (e: ActivityParseError) { ActivityPayload.Error(e) }
            catch (e: Exception) { ActivityPayload.Error(ActivityParseError.Malformed(e.toString())) }
        val payload = when {
            body.isEmpty() && crc != null -> ActivityPayload.Empty
            kind == ActivityFileKind.WORKOUT_SUMMARY -> guarded {
                WorkoutSummary.parse(body, id)?.let { ActivityPayload.Workout(it) }
                    ?: if (WorkoutSummary.headers(id.subtype) != null) ActivityPayload.Unsupported(kind, id.version) else ActivityPayload.Unsupported(kind, null)
            }
            kind.supportedVersions.isEmpty() -> ActivityPayload.Unsupported(kind, null)
            id.version !in kind.supportedVersions -> ActivityPayload.Unsupported(kind, id.version)
            else -> guarded {
                when (kind) {
                    ActivityFileKind.DAILY_DETAILS -> ActivityPayload.Daily(DailyDetails.parse(body, id, warnings))
                    ActivityFileKind.DAILY_SUMMARY -> ActivityPayload.Summary(DailySummary.parse(body, id))
                    ActivityFileKind.SLEEP_DETAILS -> ActivityPayload.Sleep(SleepDetails.parse(body, id, warnings))
                    ActivityFileKind.SLEEP_STAGES -> ActivityPayload.Stages(SleepStages.parse(body, id))
                    ActivityFileKind.MANUAL_SAMPLES -> ActivityPayload.Manual(ManualSamples.parse(body, id, pad, warnings))
                    else -> ActivityPayload.Unsupported(kind, null)
                }
            }
        }
        return ActivityParseResult(id, pad, crc, body.size, payload, warnings)
    }
}

/**
 * What one sync does with the band's two file lists: which files to ask for and which to
 * acknowledge (the band deletes an acknowledged file, so this decides what leaves the band). Same
 * rules as iOS (ActivitySyncPlan): a file is fetched unless it is settled and its stored copy is
 * final; it is acknowledged only when it is settled, final and `mayAcknowledge` agrees.
 */
data class ActivitySyncPlan(val fetch: List<ActivityFileID>, val acknowledge: List<ActivityFileID>) {
    companion object {
        /** A night that began yesterday evening can still grow until the morning; nothing older can. */
        const val SETTLED_AFTER = 36 * 3600L
        /** A band that closes the minute file at a fetch starts the next one within the minute. */
        const val CLOSE_SLACK = 60L

        /** `storedAt`: when the copy on the phone was fetched (Unix seconds), null when there is none. */
        fun make(today: List<ActivityFileID>, past: List<ActivityFileID>, now: Long,
                 storedAt: (ActivityFileID) -> Long?, mayAcknowledge: (ActivityFileID) -> Boolean): ActivitySyncPlan {
            val all = (today + past).toSet()
            val finished = ActivityFileID.finishedDetails(all)
            val detailStarts = all.filter { it.kind == ActivityFileKind.DAILY_DETAILS }.map { it.timestamp }.sorted()
            val done = all.filter { f ->
                val fetched = storedAt(f) ?: return@filter false
                if (f in finished) {
                    val next = detailStarts.firstOrNull { it > f.timestamp }
                    if (next != null && fetched >= next - CLOSE_SLACK) return@filter true
                }
                now - f.timestamp > SETTLED_AFTER && fetched - f.timestamp > SETTLED_AFTER
            }.toSet()
            return ActivitySyncPlan((all - done).sorted(), done.filter(mayAcknowledge).sorted())
        }
    }
}
