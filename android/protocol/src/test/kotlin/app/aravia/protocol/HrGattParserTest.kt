package app.aravia.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HrGattParserTest {
    @Test
    fun parses8BitHeartRateWithContactAndRrIntervals() {
        // flags = 0x16 (bit0=0 8-bit HR, bits1-2=3 contact detected, bit3=0 no energy, bit4=1 RR present)
        // bpm = 68 (0x44)
        // RR1 = 1024 (0x00, 0x04) -> 1000 ms
        // RR2 = 922  (0x9A, 0x03) -> 900 ms
        val pkt = byteArrayOf(0x16, 0x44, 0x00, 0x04, 0x9A.toByte(), 0x03)
        val m = HrGattParser.parseHeartRate(pkt)
        assertNotNull(m)
        assertEquals(68, m!!.bpm)
        assertEquals(HrGattParser.SensorContact.DETECTED, m.sensorContact)
        assertNull(m.energyExpendedKj)
        assertEquals(listOf(1000, 900), m.rrIntervalsMs)
    }

    @Test
    fun parses16BitHeartRateWithEnergyExpended() {
        // flags = 0x0D (bit0=1 16-bit HR, bits1-2=2 not detected, bit3=1 energy present, bit4=0 no RR)
        // bpm = 185 (0xB9, 0x00), energy = 420 kJ (0xA4, 0x01)
        val pkt = byteArrayOf(0x0D, 0xB9.toByte(), 0x00, 0xA4.toByte(), 0x01)
        val m = HrGattParser.parseHeartRate(pkt)
        assertNotNull(m)
        assertEquals(185, m!!.bpm)
        assertEquals(HrGattParser.SensorContact.NOT_DETECTED, m.sensorContact)
        assertEquals(420, m.energyExpendedKj)
        assertEquals(emptyList<Int>(), m.rrIntervalsMs)
    }

    @Test
    fun batteryAndSpotHrvSessionComputeAccurateRmssd() {
        assertEquals(84, HrGattParser.parseBatteryLevel(byteArrayOf(84)))
        assertNull(HrGattParser.parseBatteryLevel(byteArrayOf(120)))

        val session = HrGattParser.SpotHrvSession()
        // 60 alternating 800ms / 850ms intervals -> successive diff = 50ms -> RMSSD = 50.0ms
        repeat(30) {
            session.add(HrGattParser.HeartRateMeasurement(72, HrGattParser.SensorContact.DETECTED, rrIntervalsMs = listOf(800, 850)))
        }
        val rmssd = session.computeRmssd()
        assertNotNull(rmssd)
        assertEquals(50.0, rmssd!!, 0.5)
        assertEquals(72, session.meanHeartRate())
    }
}
