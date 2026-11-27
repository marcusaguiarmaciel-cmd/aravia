"""Writes src/test/resources/gps_fixtures.json: synthetic GPS_TRACK files (docs/GPS.md) and what
they must parse to, worked out here on their own (struct and math), not from the Kotlin code.

    python3 -I android/protocol/tools/make_gps_fixtures.py
"""
import json, math, os, struct, zlib

def f32(x):
    return struct.unpack("<f", struct.pack("<f", x))[0]

def haversine(a, b):
    r, rad = 6371000.0, math.pi / 180
    dlat, dlon = (b[1] - a[1]) * rad, (b[2] - a[2]) * rad
    h = math.sin(dlat / 2) ** 2 + math.cos(a[1] * rad) * math.cos(b[1] * rad) * math.sin(dlon / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(h)))

def make(version, fixes):
    """fixes: (ts, lat, lon, speed_kmh or None). Made-up places, not anyone's route."""
    body = bytearray(bytes.fromhex("6a0b1c2d0e0f01") + b"\x00" + bytes([version]))
    kept = []
    for ts, lat, lon, speed in fixes:
        body += struct.pack("<Iff", ts, lon, lat)
        if version == 2:
            raw = (int(round(speed * 10)) << 2) | 0b01  # low two bits are not speed
            body += b"\xAA\xBB\xCC\xDD" + struct.pack("<h", raw)
        flat, flon = f32(lat), f32(lon)
        if ts > 0 and not (flat == 0 and flon == 0) and abs(flat) <= 90 and abs(flon) <= 180:
            kept.append((ts, flat, flon, (int(round(speed * 10)) / 10) if version == 2 else None))
    body += struct.pack("<I", zlib.crc32(bytes(body)) & 0xFFFFFFFF)
    total, dist = 0.0, []
    for i, p in enumerate(kept):
        if i: total += haversine(kept[i - 1], p)
        dist.append(total)
    return {
        "version": version,
        "hex": body.hex(),
        "points": [{"time": t, "lat": la, "lon": lo, "speed": s} for t, la, lo, s in kept],
        "distances": dist,
    }

t0 = 1_760_000_000
cases = [
    make(1, [(t0, -22.9711, -43.1822, None), (t0 + 1, -22.9712, -43.1824, None),
             (t0 + 2, 0.0, 0.0, None), (0, -22.9713, -43.1826, None), (t0 + 3, -22.9715, -43.1829, None)]),
    make(2, [(t0, 48.8584, 2.2945, 9.5), (t0 + 1, 48.8586, 2.2949, 10.2), (t0 + 2, 48.8589, 2.2954, -1.5),
             (t0 + 3, 48.8591, 2.2958, 0.0)]),
]
out = os.path.join(os.path.dirname(__file__), "..", "src", "test", "resources", "gps_fixtures.json")
with open(out, "w") as f:
    json.dump({"source": "make_gps_fixtures.py", "cases": cases}, f, indent=1)
print(out)
