# Workout GPS track files

A workout's route arrives as its own activity file, next to the summary and the details.

## File id

Same 7-byte id as every activity file (`docs/ACTIVITY.md`): timestamp u32 LE, timezone i8 (quarter hours), version u8, flags u8. For a GPS track: type = 1 (sports, flags bit 7), detail type = 2 (flags bits 0–1), subtype = the sport (flags bits 2–6). [CODE] Gadgetbridge `service/devices/xiaomi/activity/XiaomiActivityFileId.java` (`DetailType.GPS_TRACK(0x02)`; parse in `from(ByteBuffer)`).

## Layout

`id (7) | padding (1, expected 0) | header (1) | samples | CRC32 LE (4)` [CODE] Gadgetbridge `.../xiaomi/activity/impl/WorkoutGpsParser.java`, `parse()`.

| Version | Sample size | Sample fields (little endian) |
|---|---|---|
| 1 | 12 | timestamp u32 (Unix seconds), longitude f32, latitude f32 |
| 2 | 18 | timestamp u32, longitude f32, latitude f32, 4 bytes not interpreted, speed i16 (`>> 2`, then ÷ 10) |

Note the order: longitude comes before latitude.

## Speed unit

[MEASURED] Redmi Watch 5 Lite `M2352W1`, firmware 5.2.154, 2026-11-24, outdoor walk (subtype 0x16, version 2): the speed field read 2.4–5.2 during the walk, and the distance between fixes over the elapsed time gave about 4.9 km/h on average. So the unit is km/h. [UNVERIFIED] for other sports and devices.

## Measured file

[MEASURED] Same device and date: 14 035 bytes, CRC32 over everything but the trailer matches, `(14035 − 13) / 18` = 779 samples exactly, one per second (gaps of 1–2 s), no jump between fixes over 3 m. The second 4-byte field looked like a float that varies per fix (perhaps accuracy or altitude) [UNVERIFIED].

## In the app

`ios/Freeband/Band/GpsTrack.swift` reads the layout above; `ios/Freeband/UI/Routes/` lists GPS files from the stored `ActivityBlob`s (type 1, detail type 2), replays them on a MapLibre 3D map and records a vertical video for Stories with ReplayKit.
