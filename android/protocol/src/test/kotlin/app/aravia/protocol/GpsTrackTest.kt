package app.aravia.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GpsTrack against synthetic files whose expected points and distances were worked out in
 *  Python (tools/make_gps_fixtures.py), not by this code. */
class GpsTrackTest {
    private val cases = JSONObject(javaClass.getResource("/gps_fixtures.json")!!.readText()).getJSONArray("cases")

    @Test fun parsesEveryFixture() {
        for (c in 0 until cases.length()) {
            val case = cases.getJSONObject(c)
            val track = GpsTrack.parse(case.getString("hex").unhex(), case.getInt("version"))!!
            val want = case.getJSONArray("points")
            assertEquals("points in case $c", want.length(), track.points.size)
            for (i in 0 until want.length()) {
                val w = want.getJSONObject(i)
                val p = track.points[i]
                assertEquals(w.getLong("time"), p.time)
                assertEquals(w.getDouble("lat"), p.latitude, 1e-9)
                assertEquals(w.getDouble("lon"), p.longitude, 1e-9)
                if (w.isNull("speed")) assertNull(p.speedKmh) else assertEquals(w.getDouble("speed"), p.speedKmh!!, 1e-9)
                assertEquals(case.getJSONArray("distances").getDouble(i), track.distances[i], 1e-6)
            }
        }
    }

    @Test fun refusesWhatIsNotATrack() {
        val v1 = cases.getJSONObject(0).getString("hex").unhex()
        assertNull(GpsTrack.parse(v1, 3))
        assertNull(GpsTrack.parse(v1.copyOfRange(0, 9 + 11 + 4), 1))
    }

    @Test fun writesGpx() {
        val track = GpsTrack.parse(cases.getJSONObject(0).getString("hex").unhex(), 1)!!
        val gpx = track.gpx("Corrida & <teste>")
        assertTrue(gpx.contains("<name>Corrida &amp; &lt;teste></name>"))
        assertTrue(gpx.contains("""<trkpt lat="-22.971100" lon="-43.182201"><time>2025-10-09T08:53:20Z</time></trkpt>"""))
        assertEquals(track.points.size, Regex("<trkpt ").findAll(gpx).count())
    }

    @Test fun parsesGpxRoundTripAndVariants() {
        val orig = GpsTrack.parse(cases.getJSONObject(0).getString("hex").unhex(), 1)!!
        val xml = orig.gpx("Corrida & <teste>")
        assertEquals("Corrida & <teste>", GpsTrack.parseGpxName(xml))
        val back = GpsTrack.parseGpx(xml)!!
        assertEquals(orig.points.size, back.points.size)
        for (i in orig.points.indices) {
            assertEquals(orig.points[i].time, back.points[i].time)
            assertEquals(orig.points[i].latitude, back.points[i].latitude, 1e-5)
            assertEquals(orig.points[i].longitude, back.points[i].longitude, 1e-5)
        }

        val variant = """
            <gpx version="1.1"><trk><name><![CDATA[Manhã no parque]]></name><trkseg>
              <trkpt lon='-46.650000' lat='-23.580000'><ele>760</ele><time>2026-11-26T09:00:00.500-03:00</time></trkpt>
              <trkpt lat="-23.581000" lon="-46.650000"><time>2026-11-26T12:00:30Z</time></trkpt>
              <trkpt lat="-23.582000" lon="-46.650000" />
            </trkseg></trk></gpx>
        """.trimIndent()
        assertEquals("Manhã no parque", GpsTrack.parseGpxName(variant))
        val parsed = GpsTrack.parseGpx(variant)!!
        assertEquals(3, parsed.points.size)
        assertEquals(parsed.points[0].time + 30L, parsed.points[1].time)
        assertEquals(parsed.points[1].time + 1L, parsed.points[2].time)
        assertTrue(parsed.distanceMeters > 200.0)
    }

    @Test fun trimsPrivacyStartAndEnd() {
        // Straight 2 km north-south run (~111 m per 0.001 deg lat), 21 points every ~100 m.
        val pts = (0..20).map { i ->
            GpsPoint(1_760_000_000L + i * 30L, -23.5800 + i * 0.0009, -46.6500, 12.0)
        }
        val track = GpsTrack(pts)
        assertEquals(21, track.trimmedForPrivacy(0.0).points.size)

        val trimmed = track.trimmedForPrivacy(500.0)
        assertTrue("expected remaining points", trimmed.points.size in 9..13)
        assertTrue(GpsTrack.meters(pts.first(), trimmed.points.first()) >= 500.0)
        assertTrue(GpsTrack.meters(pts.last(), trimmed.points.last()) >= 500.0)

        // Radius larger than half the track leaves nothing rather than leaking home.
        assertTrue(track.trimmedForPrivacy(1500.0).points.isEmpty())
    }
}
