package app.aravia.protocol

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Kotlin activity parser against the iOS fixtures (ios/tests/activity/fixtures/synthetic.json),
 * whose expectations the Python reference activity_ref.py checks on its own. Results are put in the
 * reference's JSON shape, as the Swift harness does (ios/tests/activity/swift/main.swift), and
 * compared the same way: only the expected keys, lists of equal length, error texts by presence.
 */
class ActivityFilesTest {
    private val cases = JSONObject(File("../../ios/tests/activity/fixtures/synthetic.json").readText()).getJSONArray("cases")

    @Test fun everyFixture() {
        val failures = mutableListOf<String>()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val errors = run(c)
            if (errors.isNotEmpty()) failures += "${c.getString("name")}: ${errors.take(5)}"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        assertTrue(cases.length() >= 19)
    }

    @Test fun crcCheckValue() = assertEquals(ActivityCRC32.CHECK_VALUE, ActivityCRC32.checksum("123456789".toByteArray()))

    @Test fun syncPlanKeepsUnsettledFiles() {
        val now = 1_760_500_000L
        val old = ActivityFileID.make(now - 3 * 86400, -12, 4, 0, 0, 0)
        val newer = ActivityFileID.make(now - 3600, -12, 4, 0, 0, 0)
        val plan = ActivitySyncPlan.make(listOf(newer), listOf(old), now,
            storedAt = { if (it == old) now - 100 else null }, mayAcknowledge = { true })
        assertEquals(listOf(newer), plan.fetch)
        assertEquals(listOf(old), plan.acknowledge)
    }

    private fun hex8(v: Long) = v.toString(16).padStart(8, '0')

    private fun run(c: JSONObject): List<String> {
        val expectError = c.optBoolean("expect_error", false)
        return when (c.getString("kind")) {
            "idlist" -> match(c.get("expect"), JSONArray(ActivityFileID.list(c.getString("hex").unhex()).map { fileIdJson(it) }))
            "fetch_order" -> {
                val ids = c.getJSONArray("ids").let { a -> (0 until a.length()).mapNotNull { ActivityFileID.fromHex(a.getString(it)) } }
                val got = ids.sorted().map { it.hex }
                val want = c.getJSONArray("expect").let { a -> (0 until a.length()).map { a.getString(it) } }
                if (got == want) emptyList() else listOf("order $got != $want")
            }
            "crc" -> {
                val got = hex8(ActivityCRC32.checksum(c.getString("hex").unhex()))
                if (got == c.getString("expect").lowercase()) emptyList() else listOf("crc $got")
            }
            "chunks" -> {
                val asm = ActivityAssembler()
                var file: ByteArray? = null
                var failed: AssemblerEvent.Failed? = null
                val chunks = c.getJSONArray("chunks")
                for (i in 0 until chunks.length()) {
                    when (val e = asm.add(chunks.getString(i).unhex())) {
                        is AssemblerEvent.Complete -> file = e.file
                        is AssemblerEvent.Failed -> { failed = e; break }
                        else -> {}
                    }
                }
                val f = failed
                when {
                    f != null && f.crcMismatch -> if (c.has("expect_crc_ok") && !c.getBoolean("expect_crc_ok")) emptyList() else listOf(f.reason)
                    f != null -> if (expectError) emptyList() else listOf("assembler error: ${f.reason}")
                    file == null -> listOf("no complete file")
                    expectError -> listOf("expected an assembler error")
                    else -> {
                        val errors = mutableListOf<String>()
                        if (c.has("expect_file_hex") && activityHex(file) != c.getString("expect_file_hex").lowercase()) errors += "reassembled bytes differ"
                        errors + match(c.optJSONObject("expect") ?: JSONObject(), result(ActivityParser.parse(file)))
                    }
                }
            }
            "file" -> {
                val r = ActivityParser.parse(c.getString("hex").unhex(), hasCRC = c.optBoolean("has_crc", true))
                val out = result(r)
                if (out.has("error_class")) return if (expectError) emptyList() else listOf("error: ${(r.payload as? ActivityPayload.Error)?.error?.message ?: r.payload}")
                if (expectError) return listOf("expected an error")
                match(c.optJSONObject("expect") ?: JSONObject(), out)
            }
            else -> listOf("unknown case kind")
        }
    }

    /** Subset match, as activity_ref.py `_match`. */
    private fun match(e: Any?, a: Any?, path: String = "$"): List<String> = when (e) {
        is JSONObject -> {
            if (a !is JSONObject) listOf("$path: expected object, got $a") else e.keys().asSequence().sorted().flatMap { k ->
                when {
                    !a.has(k) -> listOf("$path.$k: missing")
                    (k == "error" || k == "blocks_error") && e.get(k) is String && a.get(k) is String -> emptyList()
                    else -> match(e.get(k), a.get(k), "$path.$k")
                }
            }.toList()
        }
        is JSONArray -> {
            if (a !is JSONArray || a.length() != e.length()) listOf("$path: expected list of ${e.length()}, got ${(a as? JSONArray)?.length() ?: a}")
            else (0 until e.length()).flatMap { match(e.get(it), a.get(it), "$path[$it]") }
        }
        is Int, is Long -> if (a is Number && a.toDouble() == (e as Number).toDouble()) emptyList() else listOf("$path: $a != $e")
        is Number -> if (a is Number && kotlin.math.abs(a.toDouble() - e.toDouble()) <= 1e-6) emptyList() else listOf("$path: $a != $e")
        null, JSONObject.NULL -> if (a == null || a == JSONObject.NULL) emptyList() else listOf("$path: $a != null")
        else -> if (e == a) emptyList() else listOf("$path: $a != $e")
    }

    private fun n(v: Any?): Any = v ?: JSONObject.NULL

    private fun fileIdJson(id: ActivityFileID) = JSONObject()
        .put("hex", id.hex).put("ts", id.timestamp).put("tz_quarters", id.tzQuarterHours).put("utc_offset_min", id.utcOffsetMinutes)
        .put("version", id.version).put("type", id.type).put("type_name", id.typeName).put("subtype", id.subtype)
        .put("subtype_name", id.subtypeName).put("detail", id.detailType).put("detail_name", id.detailName)

    private fun <V> block(b: SleepSampleBlock<V>?): Any = b?.let {
        JSONObject().put("unit", it.unit).put("count", it.count).put("first_ts", n(it.firstTs)).put("values", JSONArray(it.values.map { v -> if (v is Float) v.toDouble() else v }))
    } ?: JSONObject.NULL

    private fun stage(s: SleepStageSample) = JSONObject().put("ts", s.ts).put("stage", s.stage.raw).put("raw", s.raw)
        .also { o -> s.durationMinutes?.let { o.put("duration_min", it) } }

    private fun data(p: ActivityPayload): Any = when (p) {
        is ActivityPayload.Daily -> JSONObject().put("header_hex", activityHex(p.details.header)).put("records", JSONArray(p.details.records.map { r ->
            JSONObject().put("ts", r.ts).put("steps", n(r.steps)).put("active_calories", n(r.activeCalories)).put("activity_level", n(r.activityLevel))
                .put("distance_m", n(r.distanceMeters)).put("heart_rate", n(r.heartRate)).put("energy", n(r.energy))
                .put("spo2", n(r.spo2)).put("stress", n(r.stress)).also { o ->
                    r.extraByte?.let { o.put("extra_byte", it) }; r.lightRaw?.let { o.put("light_raw", it) }
                    r.bodyMomentumRaw?.let { o.put("body_momentum_raw", it) }
                }
        })).also { if (p.details.truncatedTail > 0) it.put("truncated_tail", p.details.truncatedTail) }
        is ActivityPayload.Summary -> p.summary.let { s ->
            JSONObject().put("bitmap_hex", activityHex(s.bitmap)).put("steps", n(s.steps)).put("active_calories", n(s.activeCalories))
                .put("hr_resting", n(s.hrResting)).put("hr_max", n(s.hrMax)).put("hr_max_ts", n(s.hrMaxTs)).put("hr_min", n(s.hrMin))
                .put("hr_min_ts", n(s.hrMinTs)).put("hr_avg", n(s.hrAvg)).put("stress_avg", n(s.stressAvg)).put("stress_max", n(s.stressMax))
                .put("stress_min", n(s.stressMin)).put("standing", n(s.standing)).put("calories", n(s.calories))
                .put("recovery_hours", n(s.recoveryHours)).put("spo2_max", n(s.spo2Max)).put("spo2_max_ts", n(s.spo2MaxTs))
                .put("spo2_min", n(s.spo2Min)).put("spo2_min_ts", n(s.spo2MinTs)).put("spo2_avg", n(s.spo2Avg))
                .put("training_load_day", n(s.trainingLoadDay)).put("training_load_week", n(s.trainingLoadWeek))
                .put("training_load_level", n(s.trainingLoadLevel)).put("vitality_light", n(s.vitalityIncreaseLight))
                .put("vitality_moderate", n(s.vitalityIncreaseModerate)).put("vitality_high", n(s.vitalityIncreaseHigh))
                .put("vitality_current", n(s.vitalityCurrent)).also { o ->
                    s.standingHours?.let { o.put("standing_hours", JSONArray(it)) }
                    if (s.trailingBytes > 0) o.put("trailing_bytes", s.trailingBytes)
                }
        }
        is ActivityPayload.Sleep -> p.details.let { s ->
            JSONObject().put("bitmap_hex", activityHex(s.bitmap)).put("is_awake", s.isAwake).put("bed_time", s.bedTime)
                .put("wakeup_time", s.wakeTime).put("sleep_quality", n(s.sleepQuality)).put("hr", block(s.heartRate))
                .put("spo2", block(s.spo2)).put("snore", block(s.snore)).put("skipped_bytes", s.skippedBytes)
                .put("trailing_bytes", s.trailingBytes)
                .put("sleep_spans", JSONArray(s.sleepSpans.map { JSONArray().put(it.start).put(it.end) }))
                .put("packets", JSONArray(s.packets.map { pk ->
                    JSONObject().put("type", pk.type).put("ts", pk.tsRaw).put("parity", pk.parity).put("header_len", pk.headerLength).also { o ->
                        pk.flags?.let { o.put("flags", it) }
                        pk.length?.let { o.put("length", it) }
                        val decoded = pk.type == 17 || pk.type == 1 || (pk.type == 16 && (pk.length ?: 0) >= 13)
                        if (pk.payload != null && !decoded) o.put("payload_hex", activityHex(pk.payload))
                    }
                }))
                .put("stages", JSONArray(s.stages.map { stage(it) }))
                .put("summaries", JSONArray(s.summaries.map { m ->
                    JSONObject().put("ts", m.ts).put("sleep_index", m.sleepIndex).put("wake_count", m.wakeCount).put("sleep_min", m.sleepMinutes)
                        .put("awake_min", m.awakeMinutes).put("light_min", m.lightMinutes).put("rem_min", m.remMinutes)
                        .put("deep_min", m.deepMinutes).put("flags", m.flags).put("unknown_min", m.unknownMinutes)
                }))
                .put("rr", JSONArray(s.rrIntervals.map { JSONObject().put("ts_raw", it.tsRaw).put("rr_ms", JSONArray(it.intervalsMs)) }))
                .put("respiratory", JSONArray(s.respiratoryRate.map { JSONObject().put("ts", it.ts).put("brpm", it.breathsPerMinute) }))
                .also { o ->
                    if (s.version >= 5) {
                        o.put("v5_unknown_hex", activityHex(s.unknownV5 ?: ByteArray(0))).put("bed_time2", n(s.bedTime2)).put("wakeup_time2", n(s.wakeTime2))
                    }
                    if (s.version >= 6) o.put("v6_unknown_hex", activityHex(s.unknownV6 ?: ByteArray(0)))
                    s.blocksError?.let { o.put("blocks_error", it) }
                    s.streamError?.let { o.put("error", it) }
                }
        }
        is ActivityPayload.Stages -> p.stages.let { s ->
            JSONObject().put("unknown1_hex", activityHex(s.unknown1)).put("total_min", s.totalMinutes).put("bed_time", s.bedTime)
                .put("wakeup_time", s.wakeTime).put("unknown2_hex", activityHex(s.unknown2)).put("deep_min", s.deepMinutes)
                .put("light_min", s.lightMinutes).put("rem_min", s.remMinutes).put("awake_min", s.awakeMinutes)
                .put("no_data", s.noData).put("unknown3", s.unknown3).put("stages", JSONArray(s.stages.map { stage(it) }))
                .also { if (s.trailingBytes > 0) it.put("trailing_bytes", s.trailingBytes) }
        }
        is ActivityPayload.Manual -> p.samples.let { m ->
            JSONObject().put("samples", JSONArray(m.samples.map { s ->
                JSONObject().put("ts", s.ts).put("type", s.type).put("type_name", s.kind.raw).put("value", s.value).also { o ->
                    s.celsius?.let { o.put("celsius", it) }; s.temperatureHigh16?.let { o.put("high16", it) }
                }
            })).also { o ->
                if (m.version >= 2) { o.put("zero_value_records", m.zeroValueRecords); m.stoppedReason?.let { o.put("error", it) } }
            }
        }
        is ActivityPayload.Workout -> JSONObject().put("sport", p.summary.sport).also { o ->
            val floats = setOf("te_aerobic", "te_anaerobic", "speed_avg", "speed_max")
            for ((k, v) in p.summary.values) o.put(k, if (k in floats) Math.round(v * 100) / 100.0 else v.toLong())
        }
        else -> JSONObject.NULL
    }

    private fun result(r: ActivityParseResult): JSONObject {
        val p = r.payload
        val errorClass = when {
            p is ActivityPayload.Error && p.error is ActivityParseError.CrcMismatch -> "crc"
            p is ActivityPayload.Error && p.error is ActivityParseError.Truncated -> "truncated"
            p is ActivityPayload.Error -> "format"
            p is ActivityPayload.Unsupported && p.version != null -> "format"
            else -> null
        }
        if (errorClass != null) return JSONObject().put("error_class", errorClass)
        val id = r.fileID ?: return JSONObject().put("error_class", "format")
        val crc = r.crc?.let { JSONObject().put("stored", hex8(it.first)).put("computed", hex8(it.second)).put("ok", it.first == it.second) } ?: JSONObject.NULL
        return JSONObject().put("file_id", fileIdJson(id))
            .put("kind", if (id.kind == ActivityFileKind.UNKNOWN) "unsupported" else id.kind.raw)
            .put("pad", n(r.pad)).put("crc", crc).put("payload_len", r.payloadLength).put("data", data(p))
    }
}
