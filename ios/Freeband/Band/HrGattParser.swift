import Foundation

/// Pure Swift parser (no CoreBluetooth / no SwiftUI I/O) for Bluetooth SIG standard
/// Heart Rate Service (0x180D / 0x2A37) and Battery Service (0x180F / 0x2A19).
/// Works with any standard BLE chest strap (Polar H10/H9, Garmin HRM, Coospo, Magene, Wahoo)
/// and any smartwatch broadcasting HR over BLE.
///
/// Bluetooth SIG GATT Specification — Heart Rate Measurement (0x2A37):
/// - Byte 0: Flags
///   - Bit 0: 0 = UINT8 HR, 1 = UINT16 HR (little-endian)
///   - Bits 1..2: Sensor Contact Status (0/1 unsupported, 2 not detected, 3 detected)
///   - Bit 3: Energy Expended Status (1 = UINT16 kJ present)
///   - Bit 4: RR-Interval present (1 = one or more UINT16 values in 1/1024 s resolution)
enum HrGattParser {
    static let serviceHeartRate = "180D"
    static let charHeartRateMeasurement = "2A37"
    static let serviceBattery = "180F"
    static let charBatteryLevel = "2A19"

    enum SensorContact: Sendable, Equatable {
        case unsupported, notDetected, detected
    }

    struct HeartRateMeasurement: Sendable, Equatable {
        let bpm: Int
        let sensorContact: SensorContact
        let energyExpendedKj: Int?
        /// Beat-to-beat RR intervals converted from 1/1024 s to milliseconds.
        let rrIntervalsMs: [Int]
    }

    static func parseHeartRate(_ data: Data) -> HeartRateMeasurement? {
        let bytes = [UInt8](data)
        guard bytes.count >= 2 else { return nil }
        let flags = bytes[0]
        let is16Bit = (flags & 0x01) != 0
        let contactBits = (flags >> 1) & 0x03
        let hasEnergy = (flags & 0x08) != 0
        let hasRr = (flags & 0x10) != 0

        let contact: SensorContact = switch contactBits {
        case 2: .notDetected
        case 3: .detected
        default: .unsupported
        }

        var offset = 1
        let bpm: Int
        if is16Bit {
            guard offset + 2 <= bytes.count else { return nil }
            bpm = Int(bytes[offset]) | (Int(bytes[offset + 1]) << 8)
            offset += 2
        } else {
            bpm = Int(bytes[offset])
            offset += 1
        }

        var energy: Int?
        if hasEnergy {
            guard offset + 2 <= bytes.count else { return nil }
            energy = Int(bytes[offset]) | (Int(bytes[offset + 1]) << 8)
            offset += 2
        }

        var rrList: [Int] = []
        if hasRr {
            while offset + 2 <= bytes.count {
                let raw = Int(bytes[offset]) | (Int(bytes[offset + 1]) << 8)
                offset += 2
                if raw > 0 {
                    rrList.append(Int((Double(raw) * 1000.0 / 1024.0).rounded()))
                }
            }
        }
        return HeartRateMeasurement(bpm: bpm, sensorContact: contact, energyExpendedKj: energy, rrIntervalsMs: rrList)
    }

    static func parseBatteryLevel(_ data: Data) -> Int? {
        guard let first = data.first else { return nil }
        let v = Int(first)
        return (0 ... 100).contains(v) ? v : nil
    }

    /// Accumulates RR intervals during an ultra-short-term (60–120 s) seated/resting check-in
    /// (Shaffer et al. 2020, PMC7710683) and computes artifact-filtered RMSSD using `HRV`.
    final class SpotHrvSession {
        private var intervals: [Int] = []
        private var hrSamples: [Int] = []

        var beatCount: Int { intervals.count }
        var elapsedSeconds: Double {
            intervals.reduce(0.0) { $0 + Double(min(max($1, 0), 2000)) / 1000.0 }
        }

        func add(_ m: HeartRateMeasurement) {
            if (30 ... 220).contains(m.bpm) { hrSamples.append(m.bpm) }
            for rr in m.rrIntervalsMs where (250 ... 2200).contains(rr) {
                intervals.append(rr)
            }
        }

        /// Computes RMSSD (ms) once at least 30 valid successive differences (~30–60 s) exist.
        func computeRmssd() -> Double? {
            let diffs = HRV.validDifferences(intervals)
            guard diffs.count >= 30 else { return nil }
            return HRV.rmssd(differences: diffs)
        }

        func meanHeartRate() -> Int? {
            guard !hrSamples.isEmpty else { return nil }
            return Int((Double(hrSamples.reduce(0, +)) / Double(hrSamples.count)).rounded())
        }

        func reset() {
            intervals.removeAll()
            hrSamples.removeAll()
        }
    }
}
