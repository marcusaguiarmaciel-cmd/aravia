package app.aravia.protocol

import app.aravia.analytics.HRV
import kotlin.math.roundToInt

/**
 * Pure JVM parser for Bluetooth SIG standard Heart Rate Service (0x180D / 0x2A37) and
 * Battery Service (0x180F / 0x2A19). Works with any standard BLE chest strap (Polar H10/H9,
 * Garmin HRM, Coospo, Magene, Wahoo) and any smartwatch broadcasting HR over BLE.
 *
 * Bluetooth SIG GATT Specification — Heart Rate Measurement (0x2A37):
 * - Byte 0: Flags
 *   - Bit 0: 0 = UINT8 HR, 1 = UINT16 HR (little-endian)
 *   - Bits 1..2: Sensor Contact Status (0/1 unsupported, 2 not detected, 3 detected)
 *   - Bit 3: Energy Expended Status (1 = UINT16 kJ present)
 *   - Bit 4: RR-Interval present (1 = one or more UINT16 values in 1/1024 s resolution)
 */
object HrGattParser {
    const val SERVICE_HEART_RATE = "0000180d-0000-1000-8000-00805f9b34fb"
    const val CHAR_HEART_RATE_MEASUREMENT = "00002a37-0000-1000-8000-00805f9b34fb"
    const val SERVICE_BATTERY = "0000180f-0000-1000-8000-00805f9b34fb"
    const val CHAR_BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"

    enum class SensorContact { UNSUPPORTED, NOT_DETECTED, DETECTED }

    data class HeartRateMeasurement(
        val bpm: Int,
        val sensorContact: SensorContact,
        val energyExpendedKj: Int? = null,
        /** Beat-to-beat RR intervals converted from 1/1024 s to milliseconds. */
        val rrIntervalsMs: List<Int> = emptyList(),
    )

    fun parseHeartRate(bytes: ByteArray): HeartRateMeasurement? {
        if (bytes.size < 2) return null
        val flags = bytes[0].toInt() and 0xFF
        val is16Bit = (flags and 0x01) != 0
        val contactBits = (flags ushr 1) and 0x03
        val hasEnergy = (flags and 0x08) != 0
        val hasRr = (flags and 0x10) != 0

        val contact = when (contactBits) {
            2 -> SensorContact.NOT_DETECTED
            3 -> SensorContact.DETECTED
            else -> SensorContact.UNSUPPORTED
        }

        var offset = 1
        val bpm = if (is16Bit) {
            if (offset + 2 > bytes.size) return null
            val v = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            v
        } else {
            val v = bytes[offset].toInt() and 0xFF
            offset += 1
            v
        }

        val energy = if (hasEnergy) {
            if (offset + 2 > bytes.size) return null
            val v = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
            offset += 2
            v
        } else null

        val rrList = mutableListOf<Int>()
        if (hasRr) {
            while (offset + 2 <= bytes.size) {
                val raw = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
                offset += 2
                if (raw > 0) {
                    rrList += (raw * 1000.0 / 1024.0).roundToInt()
                }
            }
        }
        return HeartRateMeasurement(bpm = bpm, sensorContact = contact, energyExpendedKj = energy, rrIntervalsMs = rrList)
    }

    fun parseBatteryLevel(bytes: ByteArray): Int? {
        if (bytes.isEmpty()) return null
        val v = bytes[0].toInt() and 0xFF
        return v.takeIf { it in 0..100 }
    }

    /**
     * Accumulates RR intervals during an ultra-short-term (60–120 s) seated/resting check-in
     * (Shaffer et al. 2020, PMC7710683) and computes artifact-filtered RMSSD using HRV.
     */
    class SpotHrvSession {
        private val intervals = mutableListOf<Int>()
        private val hrSamples = mutableListOf<Int>()

        val beatCount: Int get() = intervals.size
        val elapsedSeconds: Double get() = intervals.sumOf { it.coerceIn(0, 2000) } / 1000.0

        fun add(m: HeartRateMeasurement) {
            if (m.bpm in 30..220) hrSamples += m.bpm
            for (rr in m.rrIntervalsMs) {
                if (rr in 250..2200) intervals += rr
            }
        }

        /** Computes RMSSD (ms) once at least 30 valid successive differences (~30-60 s) exist. */
        fun computeRmssd(): Double? {
            val diffs = HRV.validDifferences(intervals)
            if (diffs.size < 30) return null
            return HRV.rmssdFromDifferences(diffs)
        }

        fun meanHeartRate(): Int? = if (hrSamples.isEmpty()) null else hrSamples.average().roundToInt()

        fun reset() {
            intervals.clear()
            hrSamples.clear()
        }
    }
}
