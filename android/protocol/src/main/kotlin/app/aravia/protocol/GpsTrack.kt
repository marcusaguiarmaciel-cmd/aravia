package app.aravia.protocol

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One GPS fix from a workout's GPS_TRACK file. `time` is Unix seconds; `speedKmh` only in version 2. */
data class GpsPoint(val time: Long, val latitude: Double, val longitude: Double, val speedKmh: Double?)

/**
 * A workout's GPS track (activity file type 1, detail type 2), as docs/GPS.md lays it out:
 * file id (7) | padding (1) | header (1) | samples | CRC32 LE (4).
 *
 * - **Version 1 sample (12 bytes):** ts u32, lon f32, lat f32.
 * - **Version 2 sample (18 bytes):** the same, then an unknown 4-byte field and speed i16, whose
 *   value >> 2 is tenths of km/h.
 *
 * Fixes that are not real positions are dropped: 0/0, out of range, not finite, or ts 0.
 */
class GpsTrack(val points: List<GpsPoint>) {
    /** Cumulative distance in metres at each point. */
    val distances: List<Double>

    init {
        var total = 0.0
        distances = points.mapIndexed { i, p ->
            if (i > 0) total += meters(points[i - 1], p)
            total
        }
    }

    val distanceMeters: Double get() = distances.lastOrNull() ?: 0.0
    val start: Long? get() = points.firstOrNull()?.time
    val end: Long? get() = points.lastOrNull()?.time
    val durationSeconds: Long get() = (end ?: 0) - (start ?: 0)

    /** Where the track is `seconds` after its start, interpolated between fixes (as on iOS). */
    data class Position(val index: Int, val latitude: Double, val longitude: Double, val distance: Double, val speedKmh: Double)

    fun position(seconds: Double): Position {
        val t0 = start ?: return Position(0, 0.0, 0.0, 0.0, 0.0)
        val t = t0 + seconds
        var lo = 0
        var hi = points.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (points[mid].time <= t) lo = mid else hi = mid
        }
        if (points.size == 1 || t <= points[0].time) { lo = 0; hi = 0 }
        val a = points[lo]; val b = points[hi]
        val span = (b.time - a.time).toDouble()
        val f = if (span > 0) ((t - a.time) / span).coerceIn(0.0, 1.0) else 0.0
        val va = a.speedKmh ?: 0.0
        val vb = b.speedKmh ?: va
        return Position(lo, a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f,
            distances[lo] + (distances[hi] - distances[lo]) * f, va + (vb - va) * f)
    }

    /** The camera's heading: a few fixes ahead, turning smoothly at corners (as on iOS). */
    fun nextHeading(seconds: Double, current: Double?): Double {
        val now = position(seconds)
        val here = points[now.index]
        val ahead = points[minOf(now.index + 12, points.size - 1)]
        val target = if (meters(here, ahead) > 3) bearing(here, ahead) else (current ?: 0.0)
        val h = current ?: return target
        val diff = ((target - h + 540) % 360) - 180
        return (h + diff * 0.06 + 360) % 360
    }

    /** Runs of fixes in one speed band (slow to fast, 0-4), for colouring the line by speed. */
    fun speedRuns(): List<Pair<IntRange, Int>> {
        if (points.size < 2) return emptyList()
        fun band(v: Double?): Int = when { v == null -> 3; v < 3 -> 0; v < 4.5 -> 1; v < 6 -> 2; v < 10 -> 3; else -> 4 }
        val out = mutableListOf<Pair<IntRange, Int>>()
        var start = 0
        var current = band(points[1].speedKmh)
        for (i in 2 until points.size) {
            val b = band(points[i].speedKmh)
            if (b != current) { out += (start..i - 1) to current; start = i - 1; current = b }
        }
        out += (start..points.size - 1) to current
        return out
    }

    /**
     * Removes fixes near the start and end of the track to protect the user's home address when
     * sharing routes, videos, overlays, GPX files or Strava uploads.
     *
     * Drops leading points until the route moves at least [radiusMeters] away from the starting
     * fix (straight-line distance), and trailing points until at least [radiusMeters] away from
     * the ending fix. Returns an empty track when the entire route fits inside the radius.
     */
    fun trimmedForPrivacy(radiusMeters: Double): GpsTrack {
        if (radiusMeters <= 0.0 || points.size < 2) return this
        val first = points.first()
        val last = points.last()
        var startIdx = 0
        while (startIdx < points.size && meters(first, points[startIdx]) < radiusMeters) {
            startIdx++
        }
        var endIdx = points.lastIndex
        while (endIdx >= startIdx && meters(last, points[endIdx]) < radiusMeters) {
            endIdx--
        }
        return if (endIdx - startIdx + 1 >= 2) GpsTrack(points.subList(startIdx, endIdx + 1)) else GpsTrack(emptyList())
    }

    /** A GPX 1.1 document of the track; Strava and other apps import it. */
    fun gpx(name: String): String {
        val safe = name.replace("&", "&amp;").replace("<", "&lt;")
        val lines = mutableListOf(
            """<?xml version="1.0" encoding="UTF-8"?>""",
            """<gpx version="1.1" creator="Aravia" xmlns="http://www.topografix.com/GPX/1/1">""",
            "<trk><name>$safe</name><trkseg>",
        )
        for (p in points) {
            lines += String.format(java.util.Locale.US, """<trkpt lat="%.6f" lon="%.6f"><time>%s</time></trkpt>""",
                p.latitude, p.longitude, iso(p.time))
        }
        lines += "</trkseg></trk></gpx>"
        return lines.joinToString("\n")
    }

    companion object {
        fun sampleSize(version: Int): Int? = when (version) {
            1 -> 12
            2 -> 18
            else -> null
        }

        /** Parses a whole file as stored (with its 7-byte id and CRC trailer). Null for an unknown
         *  version, a file too short for one sample, or one with no real fix. */
        fun parse(data: ByteArray, version: Int): GpsTrack? {
            val size = sampleSize(version) ?: return null
            val first = 9
            val end = data.size - 4
            if (end - first < size) return null
            fun u32(o: Int): Long = (data[o].toLong() and 0xFF) or ((data[o + 1].toLong() and 0xFF) shl 8) or
                ((data[o + 2].toLong() and 0xFF) shl 16) or ((data[o + 3].toLong() and 0xFF) shl 24)
            fun f32(o: Int): Double = java.lang.Float.intBitsToFloat(u32(o).toInt()).toDouble()

            val points = ArrayList<GpsPoint>((end - first) / size)
            var i = first
            while (i + size <= end) {
                val ts = u32(i)
                val lon = f32(i + 4)
                val lat = f32(i + 8)
                val speed = if (version == 2) {
                    val raw = ((data[i + 16].toInt() and 0xFF) or ((data[i + 17].toInt() and 0xFF) shl 8)).toShort().toInt()
                    (raw shr 2) / 10.0
                } else null
                if (lat.isFinite() && lon.isFinite() && kotlin.math.abs(lat) <= 90 && kotlin.math.abs(lon) <= 180 &&
                    !(lat == 0.0 && lon == 0.0) && ts > 0) {
                    points += GpsPoint(ts, lat, lon, speed)
                }
                i += size
            }
            return if (points.isEmpty()) null else GpsTrack(points)
        }

        private val trkptRegex = Regex("""<trkpt\b([^>]*?)(?:/>|>(.*?)</trkpt>)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val latRegex = Regex("""\blat\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val lonRegex = Regex("""\blon\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val timeRegex = Regex("""<time>\s*([^<\s]+)\s*</time>""", RegexOption.IGNORE_CASE)
        private val trkNameRegex = Regex("""<trk>.*?<name>(.*?)</name>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
        private val anyNameRegex = Regex("""<name>(.*?)</name>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        /** Extracts the track title from a GPX document, or null when absent/blank. */
        fun parseGpxName(xml: String): String? {
            val raw = (trkNameRegex.find(xml) ?: anyNameRegex.find(xml))?.groupValues?.get(1) ?: return null
            val cleaned = raw.replace("<![CDATA[", "").replace("]]>", "")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&apos;", "'")
                .trim()
            return cleaned.takeIf { it.isNotEmpty() }
        }

        /** Parses a GPX 1.0/1.1 document exported from Strava, Garmin, Mi Fitness or Aravia.
         *  Pure JVM (no Android XML parser), returning null if fewer than 2 valid fixes exist. */
        fun parseGpx(xml: String): GpsTrack? {
            val raw = ArrayList<Triple<Long?, Double, Double>>()
            for (m in trkptRegex.findAll(xml)) {
                val attrs = m.groupValues[1]
                val body = m.groupValues[2]
                val lat = latRegex.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                val lon = lonRegex.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                if (!lat.isFinite() || !lon.isFinite() || kotlin.math.abs(lat) > 90 || kotlin.math.abs(lon) > 180) continue
                if (lat == 0.0 && lon == 0.0) continue
                val ts = timeRegex.find(body)?.groupValues?.get(1)?.let { parseIsoSeconds(it) }
                raw += Triple(ts, lat, lon)
            }
            if (raw.size < 2) return null
            val baseTime = raw.firstOrNull { it.first != null }?.first ?: (System.currentTimeMillis() / 1000L - raw.size)
            val points = ArrayList<GpsPoint>(raw.size)
            var lastTs = baseTime - 1L
            for ((idx, item) in raw.withIndex()) {
                val ts = item.first ?: (if (idx == 0) baseTime else lastTs + 1L)
                lastTs = ts
                val prev = points.lastOrNull()
                val speed = if (prev != null && ts > prev.time) {
                    val dt = (ts - prev.time).toDouble()
                    val dist = meters(prev, GpsPoint(ts, item.second, item.third, null))
                    (dist / dt * 3.6).coerceIn(0.0, 120.0)
                } else null
                points += GpsPoint(ts, item.second, item.third, speed)
            }
            return GpsTrack(points)
        }

        /** Parses ISO-8601 UTC or offset timestamps (`2025-10-09T08:53:20Z`, `.123Z`, `+03:00`). */
        fun parseIsoSeconds(text: String): Long? {
            val clean = text.trim()
            val m = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d+)?(Z|[+-]\d{2}:?\d{2})?$""").find(clean)
                ?: return null
            val cal = java.util.GregorianCalendar(java.util.TimeZone.getTimeZone("UTC")).apply {
                isLenient = false
                set(
                    m.groupValues[1].toInt(),
                    m.groupValues[2].toInt() - 1,
                    m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(),
                    m.groupValues[5].toInt(),
                    m.groupValues[6].toInt(),
                )
                set(java.util.Calendar.MILLISECOND, 0)
            }
            var sec = runCatching { cal.timeInMillis / 1000L }.getOrNull() ?: return null
            val tz = m.groupValues[7]
            if (tz.isNotEmpty() && tz != "Z") {
                val sign = if (tz[0] == '-') -1 else 1
                val digits = tz.substring(1).replace(":", "")
                val offH = digits.substring(0, 2).toIntOrNull() ?: 0
                val offM = digits.substring(2, 4).toIntOrNull() ?: 0
                sec -= sign * (offH * 3600L + offM * 60L)
            }
            return sec
        }

        /** Great-circle distance in metres (haversine, Earth radius 6,371 km). */
        fun meters(a: GpsPoint, b: GpsPoint): Double {
            val r = 6_371_000.0
            val rad = Math.PI / 180
            val dLat = (b.latitude - a.latitude) * rad
            val dLon = (b.longitude - a.longitude) * rad
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(a.latitude * rad) * cos(b.latitude * rad) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(min(1.0, sqrt(h)))
        }

        /** Compass bearing from a to b, in degrees. */
        fun bearing(a: GpsPoint, b: GpsPoint): Double {
            val rad = Math.PI / 180
            val y = sin((b.longitude - a.longitude) * rad) * cos(b.latitude * rad)
            val x = cos(a.latitude * rad) * sin(b.latitude * rad) -
                sin(a.latitude * rad) * cos(b.latitude * rad) * cos((b.longitude - a.longitude) * rad)
            return (atan2(y, x) / rad + 360) % 360
        }

        fun iso(seconds: Long): String {
            val f = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
            f.timeZone = java.util.TimeZone.getTimeZone("UTC")
            return f.format(java.util.Date(seconds * 1000))
        }
    }
}
