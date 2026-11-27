package app.aravia

import android.content.Intent
import app.aravia.band.BandLink
import app.aravia.band.BandSettings
import app.aravia.band.BandVault
import app.aravia.band.FindPhone
import app.aravia.protocol.MiMonitoring
import app.aravia.protocol.pb
import app.aravia.routes.RouteStore
import app.aravia.training.TrainingSession
import app.aravia.training.TrainingStore
import app.aravia.training.WorkoutController
import kotlin.math.cos
import kotlin.math.sin

/**
 * Made-up data and a starting screen for the emulator screenshots (android/tools/screens.sh).
 * Only a debug build reads the extras, so the published APK never shows it:
 *
 *     adb shell am start -S -n app.aravia.watch/app.aravia.MainActivity --es aravia.demo 1 --es aravia.screen train
 *
 * Screens: today, sleep, trends, train, band, workout, rest, treadmill, session, route, story,
 * video, monitoring, diagnostics, findphone, bind.
 * Nothing here is anyone's data: the route is a circle drawn around a park and the demo watch uses
 * a made-up identifier (AA:BB:CC:DD:5E:6F).
 */
object Demo {
    var active = false; private set
    var screen: String? = null; private set

    fun read(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent?.getStringExtra("aravia.demo") == null) return
        active = true
        screen = intent.getStringExtra("aravia.screen")
        seed()
        if (screen in setOf("band", "monitoring", "diagnostics", "beta")) {
            BandVault.setDemo(
                BandVault.Band(
                    name = "XiaomiWatchS1Active 5E6F",
                    mac = "AA:BB:CC:DD:5E:6F",
                    model = "M2352W1",
                    keys = listOf("000102030405060708090a0b0c0d0e0f"),
                    firmware = "5.2.154",
                    hardwareModel = "M2352W1",
                )
            )
            BandLink.note("— sync: XiaomiWatchS1Active, 1 key(s)")
            BandLink.note("← device: M2352W1, firmware 5.2.154")
            BandLink.note("result: completo")
        }
        when (screen) {
            "monitoring" -> {
                val hr = pb { varint(1, 0); varint(2, 10); message(5, pb { varint(1, 1) }); varint(7, 1) }.data
                val spo2 = pb { varint(1, 1); varint(2, 2) }.data
                val stress = pb { varint(1, 1) }.data
                BandSettings.setDemoMonitoring(mapOf(
                    MiMonitoring.Setting.HEART_RATE to hr,
                    MiMonitoring.Setting.SPO2 to spo2,
                    MiMonitoring.Setting.STRESS to stress,
                ))
            }
            "findphone" -> FindPhone.demoShow("XiaomiWatchS1Active")
            "workout", "rest" -> {
                WorkoutController.discard()
                WorkoutController.start(TrainingSession.Kind.GYM)
                WorkoutController.addEntry("Supino reto")
                val id = WorkoutController.session.value!!.entries.last().id
                WorkoutController.logSet(id, 10, 40.0)
                if (screen == "workout") WorkoutController.skipRest()
                WorkoutController.addEntry("Remada baixa")
            }
            "treadmill" -> {
                WorkoutController.discard()
                WorkoutController.start(TrainingSession.Kind.TREADMILL)
                WorkoutController.setTypedDistance(2350.0)
            }
        }
    }

    private fun seed() {
        if (TrainingStore.sessions.value.none { it.id.startsWith("demo-") }) {
            val day = 24 * 3600_000L
            val now = System.currentTimeMillis()
            TrainingStore.add(TrainingSession(id = "demo-treadmill", kind = TrainingSession.Kind.TREADMILL,
                start = now - 2 * day, end = now - 2 * day + 32 * 60_000L, steps = 4210, typedDistanceMeters = 4100.0,
                heart = (0 until 60).map { TrainingSession.Beat(now - 2 * day + it * 30_000L, 128 + (it % 7) * 3) }))
            val t = now - day
            TrainingStore.add(TrainingSession(id = "demo-gym", kind = TrainingSession.Kind.GYM, start = t, end = t + 51 * 60_000L,
                entries = listOf(
                    TrainingSession.Entry(exercise = "Agachamento livre", sets = (1..4).map { TrainingSession.WorkSet(reps = 8, kg = 60.0, doneAt = t + it * 240_000L) }),
                    TrainingSession.Entry(exercise = "Leg press", sets = (1..3).map { TrainingSession.WorkSet(reps = 12, kg = 140.0, doneAt = t + 1_200_000L + it * 200_000L) }),
                    TrainingSession.Entry(exercise = "Panturrilha", sets = (1..3).map { TrainingSession.WorkSet(reps = 15, kg = 40.5, doneAt = t + 2_400_000L + it * 150_000L) }),
                ),
                heart = (0 until 100).map { TrainingSession.Beat(t + it * 30_000L, 105 + (it % 11) * 4) }))
        }
        if (RouteStore.routes.value.isEmpty()) RouteStore.save(fileIdHex(), routeFile())
        if (app.aravia.band.ActivityStore.count < 20) seedBandFiles()
    }

    /** Two weeks of made-up day summaries (v5) and nights (sleep stages v2), as the band would send them. */
    private fun seedBandFiles() {
        val zone = java.time.ZoneOffset.ofHours(-3)
        val today = java.time.LocalDate.now(zone)
        for (back in 0 until 14) {
            val day = today.minusDays(back.toLong())
            val midnight = day.atStartOfDay().toEpochSecond(zone)
            val wave = (back * 37) % 11
            // Daily summary: bitmap (4) then slots; the bits mark the slots filled here.
            val slots = mapOf(0 to 6200 + wave * 650, 1 to 280 + wave * 22, 3 to 58 + wave % 5, 4 to 128 + wave * 3,
                6 to 49, 8 to 74 + wave, 9 to 31 + wave * 2, 12 to (0x3FFC0 and (0xFFFFFF shl (wave % 3))), 13 to 2100 + wave * 40,
                20 to 96, 27 to 60 + wave * 4)
            val body = java.io.ByteArrayOutputStream()
            val bitmap = ByteArray(4)
            for (i in slots.keys) bitmap[i / 8] = (bitmap[i / 8].toInt() or (0x80 shr (i % 8))).toByte()
            body.write(bitmap)
            for (i in 0 until 30) {
                val v = (slots[i] ?: 0).toLong()
                val size = app.aravia.protocol.DailySummary.slotSizes[i]
                for (b in 0 until size) body.write(((v shr (8 * b)) and 0xFF).toInt())
            }
            app.aravia.band.ActivityStore.save(app.aravia.protocol.ActivityFileID.make(midnight, -12, 5, 0, 0, 1), file(app.aravia.protocol.ActivityFileID.make(midnight, -12, 5, 0, 0, 1), body.toByteArray()))
            // The night before: bed about 23:30, about 7 h 20 min asleep.
            val bed = midnight - 1800 + wave * 120L
            val deep = 70 + wave * 3; val light = 230 + wave * 5; val rem = 85 + wave * 2; val awake = 15 + wave
            val wake = bed + (deep + light + rem + awake) * 60L
            val s = java.nio.ByteBuffer.allocate(7 + 2 + 4 + 4 + 3 + 8 + 1 + 5 * 6).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            s.put(ByteArray(7) { if (it < 2) -1 else 0 })
            s.putShort((deep + light + rem + awake).toShort()); s.putInt(bed.toInt()); s.putInt(wake.toInt()); s.put(ByteArray(3))
            s.putShort(deep.toShort()); s.putShort(light.toShort()); s.putShort(rem.toShort()); s.putShort(awake.toShort()); s.put(0)
            var t = bed
            for ((code, minutes) in listOf(3 to light / 2, 2 to deep, 4 to rem, 3 to light - light / 2, 5 to awake, 4 to 0)) {
                s.putInt(t.toInt()); s.put(code.toByte()); t += minutes * 60L
            }
            val sid = app.aravia.protocol.ActivityFileID.make(bed, -12, 2, 0, 3, 0)
            app.aravia.band.ActivityStore.save(sid, file(sid, s.array()))
        }
    }

    /** id | pad | body | CRC32 LE, as the assembler hands a file over. */
    private fun file(id: app.aravia.protocol.ActivityFileID, body: ByteArray): ByteArray {
        val head = id.bytes + byteArrayOf(0) + body
        val crc = app.aravia.protocol.ActivityCRC32.checksum(head)
        return head + byteArrayOf(crc.toByte(), (crc shr 8).toByte(), (crc shr 16).toByte(), (crc shr 24).toByte())
    }

    private val routeStart = 1_763_900_000L

    /** File id: ts u32 LE | tz -12 (UTC-3) | version 2 | flags: type 1, subtype 0x01 (outdoor run), detail 2 (GPS). */
    private fun fileIdHex(): String {
        val ts = routeStart
        val b = byteArrayOf(ts.toByte(), (ts shr 8).toByte(), (ts shr 16).toByte(), (ts shr 24).toByte(),
            (-12).toByte(), 2, (0x80 or (0x01 shl 2) or 2).toByte())
        return b.joinToString("") { "%02x".format(it) }
    }

    /** A version 2 GPS file: a ~3.8 km park loop so the default 500 m privacy zone still leaves
     *  a crisp ~2.8 km track for overlays and Story videos. */
    private fun routeFile(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(ByteArray(9))
        val n = 240
        for (i in 0 until n) {
            val a = 2 * Math.PI * i / n
            val lat = -23.5874 + 0.0052 * sin(a) + 0.0008 * sin(3 * a)
            val lon = -46.6576 + 0.0064 * cos(a)
            val bb = java.nio.ByteBuffer.allocate(18).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.putInt((routeStart + i * 5).toInt())
            bb.putFloat(lon.toFloat())
            bb.putFloat(lat.toFloat())
            bb.putInt(0)
            bb.putShort(((108 + (i % 9)) shl 2).toShort())
            out.write(bb.array())
        }
        out.write(ByteArray(4))
        return out.toByteArray()
    }
}
