import CoreLocation
import Foundation

/// One GPS fix from a workout's GPS_TRACK file.
struct GpsPoint: Sendable, Equatable {
    let time: Date
    let latitude: Double
    let longitude: Double
    /// km/h, from version 2 files only.
    let speedKmh: Double?

    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
}

/// A workout's GPS track (activity file type 1, detail type 2).
///
/// Layout (docs/GPS.md): file id (7) | padding (1) | header (1) |
/// samples | CRC32 LE (4). A version 1 sample is 12 bytes: ts u32, lon f32, lat f32. A version 2
/// sample is 18 bytes: ts u32, lon f32, lat f32, an unknown 4-byte field, speed i16 (value >> 2,
/// in tenths). Verified on a Redmi Watch 5 Lite (firmware 5.2.154): 779 points at 1 Hz, CRC ok.
struct GpsTrack: Sendable {
    let points: [GpsPoint]
    /// Cumulative distance in metres at each point.
    let distances: [Double]

    var distanceMeters: Double { distances.last ?? 0 }
    var start: Date? { points.first?.time }
    var end: Date? { points.last?.time }
    var duration: TimeInterval { (end ?? .distantPast).timeIntervalSince(start ?? .distantPast) }

    init(points: [GpsPoint]) {
        self.points = points
        var total = 0.0
        var d: [Double] = []
        d.reserveCapacity(points.count)
        for (i, p) in points.enumerated() {
            if i > 0 { total += GpsTrack.meters(points[i - 1], p) }
            d.append(total)
        }
        distances = d
    }

    static func sampleSize(version: Int) -> Int? {
        switch version {
        case 1: 12
        case 2: 18
        default: nil
        }
    }

    /// Parses a whole file as stored (with its 7-byte id and CRC trailer). Nil for an unknown
    /// version or a file too short to hold one sample.
    static func parse(_ data: Data, version: Int) -> GpsTrack? {
        guard let size = sampleSize(version: version) else { return nil }
        let bytes = [UInt8](data)
        let first = 9
        let end = bytes.count - 4
        guard end - first >= size else { return nil }

        func u32(_ o: Int) -> UInt32 {
            UInt32(bytes[o]) | UInt32(bytes[o + 1]) << 8 | UInt32(bytes[o + 2]) << 16 | UInt32(bytes[o + 3]) << 24
        }
        func f32(_ o: Int) -> Double { Double(Float(bitPattern: u32(o))) }

        var points: [GpsPoint] = []
        points.reserveCapacity((end - first) / size)
        var i = first
        while i + size <= end {
            let ts = u32(i)
            let lon = f32(i + 4)
            let lat = f32(i + 8)
            var speed: Double?
            if version == 2 {
                let raw = Int16(bitPattern: UInt16(bytes[i + 16]) | UInt16(bytes[i + 17]) << 8)
                speed = Double(raw >> 2) / 10
            }
            if lat.isFinite, lon.isFinite, abs(lat) <= 90, abs(lon) <= 180, !(lat == 0 && lon == 0), ts > 0 {
                points.append(GpsPoint(time: Date(timeIntervalSince1970: TimeInterval(ts)), latitude: lat, longitude: lon, speedKmh: speed))
            }
            i += size
        }
        return points.isEmpty ? nil : GpsTrack(points: points)
    }

    static func meters(_ a: GpsPoint, _ b: GpsPoint) -> Double {
        let r = 6_371_000.0, rad = Double.pi / 180
        let dLat = (b.latitude - a.latitude) * rad, dLon = (b.longitude - a.longitude) * rad
        let h = sin(dLat / 2) * sin(dLat / 2) + cos(a.latitude * rad) * cos(b.latitude * rad) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(min(1, sqrt(h)))
    }

    /// Compass bearing from a to b, in degrees.
    static func bearing(_ a: GpsPoint, _ b: GpsPoint) -> Double {
        let rad = Double.pi / 180
        let y = sin((b.longitude - a.longitude) * rad) * cos(b.latitude * rad)
        let x = cos(a.latitude * rad) * sin(b.latitude * rad) - sin(a.latitude * rad) * cos(b.latitude * rad) * cos((b.longitude - a.longitude) * rad)
        return (atan2(y, x) / rad + 360).truncatingRemainder(dividingBy: 360)
    }

    /// The position `seconds` after the start, interpolated between fixes.
    struct Position {
        let index: Int
        let latitude: Double
        let longitude: Double
        let distance: Double
        let speedKmh: Double
        var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitude, longitude: longitude) }
    }

    func position(at seconds: TimeInterval) -> Position {
        guard let t0 = start, !points.isEmpty else { return Position(index: 0, latitude: 0, longitude: 0, distance: 0, speedKmh: 0) }
        let t = t0.addingTimeInterval(seconds)
        var lo = 0, hi = points.count - 1
        while hi - lo > 1 {
            let mid = (lo + hi) / 2
            if points[mid].time <= t { lo = mid } else { hi = mid }
        }
        if points.count == 1 || t <= points[0].time { lo = 0; hi = 0 }
        let a = points[lo], b = points[hi]
        let span = b.time.timeIntervalSince(a.time)
        let f = span > 0 ? min(1, max(0, t.timeIntervalSince(a.time) / span)) : 0
        let va = a.speedKmh ?? 0, vb = b.speedKmh ?? va
        return Position(index: lo,
                        latitude: a.latitude + (b.latitude - a.latitude) * f,
                        longitude: a.longitude + (b.longitude - a.longitude) * f,
                        distance: distances[lo] + (distances[hi] - distances[lo]) * f,
                        speedKmh: va + (vb - va) * f)
    }

    /// Returns a copy of the track with all points within `radiusMeters` of the start AND finish
    /// removed, protecting the person's home address when sharing outside the phone.
    func trimmedForPrivacy(radiusMeters: Double) -> GpsTrack {
        guard radiusMeters > 0, let first = points.first, let last = points.last else { return self }
        var startIdx = 0
        while startIdx < points.count && GpsTrack.meters(first, points[startIdx]) < radiusMeters {
            startIdx += 1
        }
        var endIdx = points.count - 1
        while endIdx >= startIdx && GpsTrack.meters(last, points[endIdx]) < radiusMeters {
            endIdx -= 1
        }
        guard endIdx - startIdx + 1 >= 2 else { return GpsTrack(points: []) }
        return GpsTrack(points: Array(points[startIdx ... endIdx]))
    }

    /// A GPX 1.1 document of the track.
    func gpx(name: String) -> String {
        let iso = ISO8601DateFormatter()
        var lines = [
            #"<?xml version="1.0" encoding="UTF-8"?>"#,
            #"<gpx version="1.1" creator="Aravia" xmlns="http://www.topografix.com/GPX/1/1">"#,
            "<trk><name>\(name.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;"))</name><trkseg>",
        ]
        for p in points {
            lines.append(String(format: #"<trkpt lat="%.6f" lon="%.6f"><time>%@</time></trkpt>"#, p.latitude, p.longitude, iso.string(from: p.time)))
        }
        lines.append("</trkseg></trk></gpx>")
        return lines.joined(separator: "\n")
    }

    /// Extracts the track title from a GPX document, or nil when absent/blank.
    static func parseGpxName(_ xml: String) -> String? {
        let patterns = [
            #"<trk>[\s\S]*?<name>([\s\S]*?)</name>"#,
            #"<name>([\s\S]*?)</name>"#,
        ]
        for pattern in patterns {
            guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]),
                  let match = regex.firstMatch(in: xml, range: NSRange(xml.startIndex..., in: xml)),
                  match.numberOfRanges > 1,
                  let range = Range(match.range(at: 1), in: xml) else { continue }
            let cleaned = String(xml[range])
                .replacingOccurrences(of: "<![CDATA[", with: "")
                .replacingOccurrences(of: "]]>", with: "")
                .replacingOccurrences(of: "&amp;", with: "&")
                .replacingOccurrences(of: "&lt;", with: "<")
                .replacingOccurrences(of: "&gt;", with: ">")
                .replacingOccurrences(of: "&quot;", with: "\"")
                .replacingOccurrences(of: "&apos;", with: "'")
                .trimmingCharacters(in: .whitespacesAndNewlines)
            if !cleaned.isEmpty { return cleaned }
        }
        return nil
    }

    /// Parses a GPX 1.0/1.1 document exported from Strava, Garmin, Polar, Amazfit, Apple Health or Aravia.
    static func parseGpx(_ xml: String) -> GpsTrack? {
        guard let trkptRe = try? NSRegularExpression(pattern: #"<trkpt\b([^>]*?)(?:/>|>([\s\S]*?)</trkpt>)"#, options: [.caseInsensitive]),
              let latRe = try? NSRegularExpression(pattern: #"\blat\s*=\s*["']([^"']+)["']"#, options: [.caseInsensitive]),
              let lonRe = try? NSRegularExpression(pattern: #"\blon\s*=\s*["']([^"']+)["']"#, options: [.caseInsensitive]),
              let timeRe = try? NSRegularExpression(pattern: #"<time>\s*([^<\s]+)\s*</time>"#, options: [.caseInsensitive])
        else { return nil }

        func firstCapture(_ re: NSRegularExpression, in text: String) -> String? {
            guard let m = re.firstMatch(in: text, range: NSRange(text.startIndex..., in: text)),
                  m.numberOfRanges > 1,
                  let r = Range(m.range(at: 1), in: text) else { return nil }
            return String(text[r])
        }

        let nsRange = NSRange(xml.startIndex..., in: xml)
        let matches = trkptRe.matches(in: xml, range: nsRange)
        var raw: [(TimeInterval?, Double, Double)] = []
        raw.reserveCapacity(matches.count)

        for m in matches {
            guard m.numberOfRanges > 1, let attrRange = Range(m.range(at: 1), in: xml) else { continue }
            let attrs = String(xml[attrRange])
            let body = (m.numberOfRanges > 2 && m.range(at: 2).location != NSNotFound)
                .flatMap { $0 ? Range(m.range(at: 2), in: xml).map { String(xml[$0]) } : nil } ?? ""
            guard let latStr = firstCapture(latRe, in: attrs), let lat = Double(latStr),
                  let lonStr = firstCapture(lonRe, in: attrs), let lon = Double(lonStr),
                  lat.isFinite, lon.isFinite, abs(lat) <= 90, abs(lon) <= 180, !(lat == 0 && lon == 0)
            else { continue }
            let ts = firstCapture(timeRe, in: body).flatMap(parseIsoSeconds)
            raw.append((ts, lat, lon))
        }
        guard raw.count >= 2 else { return nil }

        let baseTime = raw.first(where: { $0.0 != nil })?.0 ?? (Date().timeIntervalSince1970 - Double(raw.count))
        var points: [GpsPoint] = []
        points.reserveCapacity(raw.count)
        var lastTs = baseTime - 1
        for (idx, item) in raw.enumerated() {
            let ts = item.0 ?? (idx == 0 ? baseTime : lastTs + 1)
            lastTs = ts
            let date = Date(timeIntervalSince1970: ts)
            var speed: Double?
            if let prev = points.last, ts > prev.time.timeIntervalSince1970 {
                let dt = ts - prev.time.timeIntervalSince1970
                let dist = meters(prev, GpsPoint(time: date, latitude: item.1, longitude: item.2, speedKmh: nil))
                speed = min(max(dist / dt * 3.6, 0), 120)
            }
            points.append(GpsPoint(time: date, latitude: item.1, longitude: item.2, speedKmh: speed))
        }
        return GpsTrack(points: points)
    }

    static func parseIsoSeconds(_ text: String) -> TimeInterval? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        let f1 = ISO8601DateFormatter()
        f1.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = f1.date(from: trimmed) { return d.timeIntervalSince1970 }
        let f2 = ISO8601DateFormatter()
        f2.formatOptions = [.withInternetDateTime]
        return f2.date(from: trimmed)?.timeIntervalSince1970
    }
}

private extension Bool {
    func flatMap<T>(_ transform: (Bool) -> T?) -> T? { transform(self) }
}
