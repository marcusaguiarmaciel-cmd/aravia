package app.aravia.protocol

// Commands and replies (docs/ACTIVITY.md 2.2, docs/STAY-CONNECTED.md 1, docs/CAPABILITIES.md).

object MiAuth {
    const val COMMAND_TYPE_AUTH = 1L
    const val SUBTYPE_NONCE = 26L
    const val SUBTYPE_AUTH = 27L

    class SessionKeys(
        val decryptionKey: ByteArray,
        val encryptionKey: ByteArray,
        val decryptionNonce: ByteArray,
        val encryptionNonce: ByteArray,
    )

    class Confirm(val command: ByteArray, val keys: SessionKeys)

    /** Step 1: Command{1, 26, auth.appVerify{nonce, app device id}}. */
    fun nonceCommand(phoneNonce: ByteArray, appDeviceID: String? = null): ByteArray {
        val verify = pb { bytes(1, phoneNonce); if (appDeviceID != null) string(2, appDeviceID) }
        return pb { varint(1, COMMAND_TYPE_AUTH); varint(2, SUBTYPE_NONCE); message(3, pb { message(30, verify) }) }.data
    }

    /** Step 3: session keys, the watch's HMAC checked (null = wrong key), Command{1, 27, appConfirm}. */
    fun confirmCommand(authKey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray, watchHmac: ByteArray,
                       deviceName: String): Confirm? {
        val okm = MiCrypto.sessionKeyMaterial(authKey, phoneNonce, watchNonce)
        val keys = SessionKeys(okm.copyOfRange(0, 16), okm.copyOfRange(16, 32), okm.copyOfRange(32, 36), okm.copyOfRange(36, 40))
        if (!MiCrypto.hmacSha256(keys.decryptionKey, watchNonce + phoneNonce).contentEquals(watchHmac)) return null
        // CompanionDevice: device_type 1, device_name, app_capability 0xFFFFFFFF.
        val companion = pb { varint(1, 1); string(3, deviceName); varint(4, 0xFFFF_FFFFL) }
        val ccmNonce = keys.encryptionNonce + ByteArray(8)
        val confirm = pb {
            bytes(1, MiCrypto.hmacSha256(keys.encryptionKey, phoneNonce + watchNonce))
            bytes(2, MiCrypto.ccmEncrypt(keys.encryptionKey, ccmNonce, companion.data))
        }
        val command = pb { varint(1, COMMAND_TYPE_AUTH); varint(2, SUBTYPE_AUTH); message(3, pb { message(32, confirm) }) }
        return Confirm(command.data, keys)
    }

    fun batteryCommand(): ByteArray = pb { varint(1, 2); varint(2, 1) }.data

    fun batteryLevel(c: PbMessage): Long? = c.message(4)?.message(2)?.message(1)?.varints?.get(1)

    /** battery state 1 charging; 2 or 3 not. */
    fun batteryCharging(c: PbMessage): Boolean? = c.message(4)?.message(2)?.message(1)?.varints?.get(2)?.let { it == 1L }

    data class LiveStats(val steps: Long, val calories: Long, val heartRate: Long, val standingHours: Long)

    fun realtimeCommand(start: Boolean): ByteArray = pb { varint(1, 8); varint(2, if (start) 45 else 46) }.data

    /** health(10).realTimeStats(39){steps 1, calories 2, heartRate 4, standingHours 6} */
    fun liveStats(c: PbMessage): LiveStats? {
        val s = c.message(10)?.message(39) ?: return null
        return LiveStats(s.varints[1] ?: 0, s.varints[2] ?: 0, s.varints[4] ?: 0, s.varints[6] ?: 0)
    }

    fun findBandCommand(start: Boolean): ByteArray =
        pb { varint(1, 2); varint(2, 18); message(4, pb { varint(5, if (start) 0 else 1) }) }.data

    fun findPhoneStopCommand(): ByteArray = pb { varint(1, 2); varint(2, 17); message(4, pb { varint(5, 1) }) }.data

    /** Command{2, 17}: true to ring (0 or missing), false to stop, null without a system field. */
    fun findPhoneRequest(c: PbMessage): Boolean? {
        if (c.varints[1] != 2L || c.varints[2] != 17L) return null
        val system = c.message(4) ?: return null
        return (system.varints[5] ?: 0L) == 0L
    }

    /** Command{8,1, health(10).activitySyncRequestToday(5){0}} or Command{8,2} for past files. */
    fun activityListCommand(today: Boolean): ByteArray = pb {
        varint(1, 8); varint(2, if (today) 1 else 2)
        if (today) message(10, pb { message(5, pb { varint(1, 0) }) })
    }.data

    fun activityFetchCommand(id: ByteArray): ByteArray = pb { varint(1, 8); varint(2, 3); message(10, pb { bytes(2, id) }) }.data

    /** The band deletes the file: only for a file that is stored and parsed. */
    fun activityAckCommand(id: ByteArray): ByteArray = pb { varint(1, 8); varint(2, 5); message(10, pb { bytes(3, id) }) }.data

    fun activityFileIDBytes(c: PbMessage): ByteArray = c.message(10)?.blobs?.get(2) ?: ByteArray(0)

    /**
     * Sets the band's clock and time zone: Command{2,3} system(4).clock(4){date 1 {y, m, d},
     * time 2 {h, min, s, ms}, zone 3 {standard offset sint in 15 min, DST sint in 15 min, name},
     * 12-hour bool 4}. The same bytes as MiAuth.clockCommand on iOS (vectors clock_*).
     */
    fun clockCommand(nowMillis: Long, zone: java.util.TimeZone, is24Hour: Boolean): ByteArray {
        val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = nowMillis }
        val dst = (if (zone.inDaylightTime(java.util.Date(nowMillis))) zone.dstSavings else 0) / 1000L
        val standard = zone.getOffset(nowMillis) / 1000L - dst
        val date = pb { varint(1, cal.get(java.util.Calendar.YEAR)); varint(2, cal.get(java.util.Calendar.MONTH) + 1); varint(3, cal.get(java.util.Calendar.DAY_OF_MONTH)) }
        val time = pb {
            varint(1, cal.get(java.util.Calendar.HOUR_OF_DAY)); varint(2, cal.get(java.util.Calendar.MINUTE))
            varint(3, cal.get(java.util.Calendar.SECOND)); varint(4, cal.get(java.util.Calendar.MILLISECOND))
        }
        val tz = pb { sint(1, standard / 900); sint(2, dst / 900); string(3, zone.id) }
        val clock = pb { message(1, date); message(2, time); message(3, tz); bool(4, !is24Hour) }
        return pb { varint(1, 2); varint(2, 3); message(4, pb { message(4, clock) }) }.data
    }

    /** Command{2, subtype} with nothing else: asks the band for a system value (2 device info, 78 state). */
    fun systemGet(subtype: Int): ByteArray = pb { varint(1, 2); varint(2, subtype) }.data

    /** Command{8, subtype} with nothing else: read-only Health query (used by Diagnostics probe). */
    fun healthGet(subtype: Int): ByteArray = pb { varint(1, 8); varint(2, subtype) }.data

    data class DeviceInfo(val serial: String, val firmware: String, val model: String)

    /** Reply to Command{2,2}: system(4).deviceInfo(3){serialNumber 1, firmware 2, model 4}. */
    fun deviceInfo(c: PbMessage): DeviceInfo? {
        val info = c.message(4)?.message(3) ?: return null
        fun text(f: Int) = info.blobs[f]?.toString(Charsets.UTF_8) ?: ""
        return DeviceInfo(text(1), text(2), text(4))
    }

    data class DeviceState(val charging: Boolean, val worn: Boolean, val asleep: Boolean)

    /**
     * The band's push Command{2,79}: system(4).deviceState(49){charging 1 (1 yes, 2 no), wearing 2, sleep 3}.
     * The reply to Command{2,78} (basicDeviceState 48) is not read (docs/CAPABILITIES.md 9.7).
     */
    data class DeviceStateChange(val charging: Boolean?, val worn: Boolean?, val asleep: Boolean?)

    fun deviceStateChange(c: PbMessage): DeviceStateChange? {
        val pushed = c.message(4)?.message(49) ?: return null
        return DeviceStateChange(
            charging = pushed.varints[1]?.let { it == 1L },
            worn = pushed.varints[2]?.let { it == 1L },
            asleep = pushed.varints[3]?.let { it == 1L },
        )
    }
}

