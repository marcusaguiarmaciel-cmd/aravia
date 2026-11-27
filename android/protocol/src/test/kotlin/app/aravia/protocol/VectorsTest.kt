package app.aravia.protocol

import java.io.File
import org.junit.Test

/**
 * Prints the protocol's outputs for the fixed inputs of ios/tests/protocol/main.swift into
 * build/vectors.json; check_vectors.py --subset recomputes them on its own (never copied from here).
 */
class VectorsTest {
    private fun r(a: Int, b: Int) = ByteArray(b - a) { (a + it).toByte() }

    @Test
    fun writeVectors() {
        val key = r(0, 16); val phone = r(16, 32); val watch = r(32, 48); val ccmNonce = r(48, 60)
        val long = "BandProbe companion device test vector!".toByteArray()
        val ctrIn = ByteArray(40) { (it * 7 and 0xFF).toByte() }

        val okm = MiCrypto.sessionKeyMaterial(key, phone, watch)
        val watchHmac = MiCrypto.hmacSha256(okm.copyOfRange(0, 16), watch + phone)
        val confirm = MiAuth.confirmCommand(key, phone, watch, watchHmac, "BandProbe")!!
        val wrong = MiAuth.confirmCommand(ByteArray(16), phone, watch, watchHmac, "BandProbe")

        val buf = (bytes(0x00, 0xA5) + MiFrame.encode(3, 7, bytes(1, 2, 0xAA)) + MiFrame.encode(1, 9, ByteArray(0)) + bytes(0xA5, 0xA5, 3))
            .toMutableList()
        val drained = MiFrame.drain(buf)

        val resp = pb { varint(1, 2); varint(2, 1); message(4, pb { message(2, pb { message(1, pb { varint(1, 87) }) }) }) }
        val liveCmd = pb {
            varint(1, 8); varint(2, 47)
            message(10, pb { message(39, pb { varint(1, 8421); varint(2, 312); varint(3, 5); varint(4, 74); varint(5, 49); varint(6, 9) }) })
        }
        val live = MiAuth.liveStats(PbMessage.parse(liveCmd.data)!!)!!

        // V1 scenarios, as main.swift builds them
        fun fmt(o: MiV1Channel.Output): List<String> = o.writes.map { "w:" + it.hex() } + o.events.map {
            when (it) {
                is MiV1Channel.Event.Message -> "msg:" + it.data.hex()
                is MiV1Channel.Event.Acknowledged -> "ack:${it.accepted}"
                is MiV1Channel.Event.Undecryptable -> "bad:${it.count}"
                is MiV1Channel.Event.Unexpected -> "?"
            }
        }
        val keys = confirm.keys
        val v1Cipher = MiV1Cipher(keys)
        fun fromWatch(p: ByteArray) = MiCrypto.ccmEncrypt(keys.decryptionKey, MiV1Cipher.nonce(keys.decryptionNonce, 0), p)
        val longCipher = MiCrypto.ccmEncrypt(key, ccmNonce, long)
        val tampered = longCipher.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        val nonceCmd = MiAuth.nonceCommand(phone)
        val batteryCmd = MiAuth.batteryCommand()
        val big = r(0, 40); val plain30 = r(0x80, 0x9E)
        val inPlain = "watch says hi".toByteArray()
        val inBig = ByteArray(41) { (255 - it).toByte() }

        val chA = MiV1Channel()
        val plainQueue = listOf(fmt(chA.send(nonceCmd)), fmt(chA.send(batteryCmd)), fmt(chA.receive("00000300".unhex())))
        val chB = MiV1Channel().apply { this.cipher = v1Cipher; authenticated() }
        val encSingle = listOf(fmt(chB.send(batteryCmd)), fmt(chB.receive("00000300".unhex())), fmt(chB.send(batteryCmd)))
        val chC = MiV1Channel(20).apply { this.cipher = v1Cipher; authenticated() }
        val encChunked = listOf(fmt(chC.send(big)), fmt(chC.receive("00000101".unhex())), fmt(chC.receive("000001050200".unhex())),
            fmt(chC.receive("00000100".unhex())))
        val chD = MiV1Channel(20)
        val plainChunked = listOf(fmt(chD.send(plain30)), fmt(chD.receive("00000101".unhex())), fmt(chD.receive("00000102".unhex())))
        val chE = MiV1Channel().apply { this.cipher = v1Cipher }
        val inEncFrame = "00000201".unhex() + fromWatch(inPlain)
        val inSingle = listOf(fmt(chE.receive("00000202".unhex() + inPlain)), fmt(chE.receive(inEncFrame)),
            fmt(chE.receive("00000201".unhex() + ByteArray(8) { 0x5A.toByte() })))
        val chF = MiV1Channel(20).apply { this.cipher = v1Cipher }
        val inCT = fromWatch(inBig)
        val inSlices = listOf("0100".unhex() + inCT.copyOfRange(0, 18), "0200".unhex() + inCT.copyOfRange(18, 36),
            "0300".unhex() + inCT.copyOfRange(36, inCT.size))
        val inChunked = mutableListOf(fmt(chF.receive("000000010300".unhex())), fmt(chF.receive(inSlices[1])))
        inChunked += listOf("receiving:${chF.isReceiving}")
        inChunked += fmt(chF.requestMissing())
        inChunked += fmt(chF.receive(inSlices[0]))
        inChunked += fmt(chF.receive(inSlices[2]))
        inChunked += listOf("receiving:${chF.isReceiving}")
        inChunked += fmt(chF.requestMissing())
        val chG = MiV1Channel()
        val unexpected = listOf(fmt(chG.receive("00000300".unhex())), fmt(chG.receive("00000101".unhex())),
            fmt(chG.receive("0500aa".unhex())), fmt(chG.receive("0000".unhex())))
        val chH = MiV1Channel().apply { this.cipher = v1Cipher }
        val authReset = mutableListOf(fmt(chH.send(nonceCmd)))
        chH.authenticated()
        authReset += fmt(chH.send(batteryCmd))

        fun findPhone(subtype: Int = 17, build: PbWriter.() -> Unit): String {
            val c = pb { varint(1, 2); varint(2, subtype); build() }
            return MiAuth.findPhoneRequest(PbMessage.parse(c.data)!!)?.toString() ?: "nil"
        }
        fun batteryReply(state: Long?): PbMessage {
            val battery = pb { varint(1, 82); if (state != null) varint(2, state); message(3, pb { varint(1, 3); varint(2, 1_760_000_000) }) }
            return PbMessage.parse(pb { varint(1, 2); varint(2, 1); message(4, pb { message(2, pb { message(1, battery) }) }) }.data)!!
        }

        // Health monitoring settings (docs/CAPABILITIES.md §9.1-9.3)
        val hrBody = pb {
            varint(1, 0); varint(2, 30); varint(3, 1); varint(4, 120)
            message(5, pb { varint(1, 1) }); varint(7, 1)
            message(8, pb { varint(1, 0); varint(2, 40) })
            varint(9, 2); varint(10, 0); varint(11, 1); varint(20, 300); string(21, "ab")
        }.data + bytes(0xB5, 0x01, 1, 2, 3, 4)
        val spo2Body = pb { varint(1, 1); varint(2, 0); message(4, pb { varint(1, 1); varint(2, 90) }) }.data
        val stressBody = pb { varint(1, 0); message(2, pb { varint(1, 1); varint(2, 0) }) }.data
        val hrReply = pb { varint(1, 8); varint(2, 10); message(10, pb { bytes(8, hrBody) }) }.data
        fun hrSet(body: ByteArray, mode: MiMonitoring.HeartRateMode?, sleep: Boolean? = null): String =
            MiMonitoring.heartRate(body, mode, sleep)?.let { MiMonitoring.set(MiMonitoring.Setting.HEART_RATE, it).hex() } ?: "nil"

        // Band state (2/78 ignored, 2/79 push)
        val basicState = pb { varint(1, 2); varint(2, 78); message(4, pb { message(48, pb { varint(1, 1); varint(2, 0); varint(3, 0); varint(4, 0) }) }) }.data
        val pushedState = pb { varint(1, 2); varint(2, 79); message(4, pb { message(49, pb { varint(1, 2); varint(2, 1); varint(3, 2) }) }) }.data
        val pushedCharging = pb { varint(1, 2); varint(2, 79); message(4, pb { message(49, pb { varint(1, 2) }) }) }.data
        fun stateText(s: MiAuth.DeviceStateChange?): String {
            fun t(b: Boolean?) = b?.toString() ?: "nil"
            return s?.let { "${t(it.charging)}/${t(it.worn)}/${t(it.asleep)}" } ?: "nil"
        }

        // Local bind (docs/BIND.md)
        val bindPhoneKey = MiBind.P256.fromScalar(ByteArray(32) { (0x01 + it).toByte() })
        val bindBandKey = MiBind.P256.fromScalar(ByteArray(32) { (0x21 + it).toByte() })
        val bindSecret = MiBind.P256.sharedSecret(bindBandKey.privateKey, bindPhoneKey.publicKeyRaw)!!
        val bindAppRandom = ByteArray(16) { (0x50 + it).toByte() }
        val bindBandRandom = ByteArray(16) { (0x60 + it).toByte() }
        fun bandReply(id: Long, field: Int, build: PbWriter.() -> Unit): ByteArray =
            pb { varint(1, 1); varint(2, id); message(3, pb { message(field, pb(build)) }) }.data
        fun bandError(id: Long, code: Long): ByteArray =
            pb { varint(1, 1); varint(2, id); message(3, pb { varint(3, code) }) }.data
        fun bandInfo(verify: Long, oob: Long): ByteArray = bandReply(17, 12) {
            varint(1, verify); string(2, "SN0000"); string(3, "AA:BB:CC:DD:5E:6F"); string(4, "M0000")
            varint(5, oob); bytes(6, ByteArray(16) { (0x70 + it).toByte() }); string(7, "did0")
        }
        fun bandVerify(code: String = "", keyBytes: ByteArray = bindBandKey.publicKeyRaw, flip: Boolean = false): ByteArray {
            val sign = MiCrypto.hmacSha256(bindSecret, bindBandRandom + code.toByteArray(Charsets.UTF_8))
            if (flip) sign[5] = (sign[5].toInt() xor 1).toByte()
            return bandReply(18, 18) { bytes(1, keyBytes); bytes(2, bindBandRandom); bytes(3, sign) }
        }
        fun bandConfirm(ok: Boolean): ByteArray = bandReply(19, 20) { bool(1, ok) }
        fun bindStep(step: MiBind.Step): String = when (step) {
            is MiBind.Step.Send -> "send:${step.data.hex()}:${step.confirmOnBand}"
            is MiBind.Step.BoundResult -> {
                val b = step.bound
                "bound:" + listOf(b.authKey, b.keys.decryptionKey, b.keys.encryptionKey, b.keys.decryptionNonce, b.keys.encryptionNonce)
                    .joinToString(":") { it.hex() } + ":${b.appDeviceID}:${b.info.model}:${b.info.oobMode}"
            }
            is MiBind.Step.Failed -> "failed:${step.failure}:" + (step.tellBand?.hex() ?: "-")
            is MiBind.Step.Ignored -> "ignored"
        }
        fun bindRun(name: String = "Freeband", code: String = "", replies: List<ByteArray>): List<String> {
            val cfg = MiBind.Config("freeband-test", "0123456789abcdef", name, code)
            val m = MiBind.Machine.create(cfg, bindPhoneKey, bindAppRandom)!!
            return listOf(m.start().hex()) + replies.map { bindStep(m.receive(it)) }
        }
        val bindAAD = "bind-data".toByteArray(Charsets.UTF_8)
        val bindCCM = MiCrypto.ccmEncrypt(key, ccmNonce, long, bindAAD)
        val offCurve = bindBandKey.publicKeyRaw.copyOf().also { it[63] = (it[63].toInt() xor 1).toByte() }

        val out = linkedMapOf<String, Any>(
            "crc_123456789" to "%04x".format(MiFrame.crc16arc("123456789".toByteArray())),
            "okm" to okm.hex(),
            "ccm_short" to MiCrypto.ccmEncrypt(key, ccmNonce, "hello".toByteArray()).hex(),
            "ccm_long" to longCipher.hex(),
            "ctr" to MiCrypto.ctr(key, ctrIn).hex(),
            "nonce_cmd" to nonceCmd.hex(),
            "nonce_cmd_app_id" to MiAuth.nonceCommand(phone, "0123456789abcdef").hex(),
            "confirm_cmd" to confirm.command.hex(),
            "wrong_key_rejected" to (wrong == null),
            "session_frame" to MiFrame.encode(2, 0, MiFrame.startSessionPayload).hex(),
            "drained" to drained.map { "${it.kind}:${it.seq}:${it.payload.hex()}" },
            "leftover" to buf.toByteArray().hex(),
            "battery_parsed" to MiAuth.batteryLevel(PbMessage.parse(resp.data)!!)!!,
            "battery_cmd" to batteryCmd.hex(),
            "realtime_start" to MiAuth.realtimeCommand(true).hex(),
            "realtime_stop" to MiAuth.realtimeCommand(false).hex(),
            "live_parsed" to "${live.steps}/${live.calories}/${live.heartRate}/${live.standingHours}",
            "activity_today" to MiAuth.activityListCommand(true).hex(),
            "activity_past" to MiAuth.activityListCommand(false).hex(),
            "activity_fetch" to MiAuth.activityFetchCommand("00f15365040001".unhex()).hex(),
            "activity_ack" to MiAuth.activityAckCommand("00f15365040001".unhex()).hex(),
            "find_start" to MiAuth.findBandCommand(true).hex(),
            "find_stop" to MiAuth.findBandCommand(false).hex(),
            "find_phone_stop" to MiAuth.findPhoneStopCommand().hex(),
            "clock_istanbul" to MiAuth.clockCommand(1_760_000_000_000L, java.util.TimeZone.getTimeZone("Europe/Istanbul"), true).hex(),
            "clock_newyork_12h" to MiAuth.clockCommand(1_760_000_000_000L, java.util.TimeZone.getTimeZone("America/New_York"), false).hex(),
            "find_phone_parsed" to listOf(
                findPhone { message(4, pb { varint(5, 0) }) },
                findPhone { message(4, pb { varint(5, 1) }) },
                findPhone { message(4, PbWriter()) },
                findPhone { },
                findPhone(18) { message(4, pb { varint(5, 0) }) },
            ),
            "resend_seqs" to listOf(10 to 10, 10 to 7, 3 to 250, 40 to 8, 40 to 7, 10 to 11).map { MiFrame.resendSeqs(it.first, it.second) },
            "battery_charging" to listOf(2L, 1L, 3L, null).map { MiAuth.batteryCharging(batteryReply(it))?.toString() ?: "nil" },
            "battery_level_with_state" to MiAuth.batteryLevel(batteryReply(2))!!,
            "state_basic_not_read" to stateText(MiAuth.deviceStateChange(PbMessage.parse(basicState)!!)),
            "state_pushed" to stateText(MiAuth.deviceStateChange(PbMessage.parse(pushedState)!!)),
            "state_pushed_one_field" to stateText(MiAuth.deviceStateChange(PbMessage.parse(pushedCharging)!!)),
            "mon_get" to MiMonitoring.Setting.entries.map { MiMonitoring.get(it).hex() },
            "mon_reply" to (MiMonitoring.reply(PbMessage.parse(hrReply)!!, MiMonitoring.Setting.HEART_RATE) ?: ByteArray(0)).hex(),
            "mon_reply_other" to (MiMonitoring.reply(PbMessage.parse(hrReply)!!, MiMonitoring.Setting.SPO2) == null),
            "mon_hr_parsed" to "${MiMonitoring.heartRateMode(hrBody)!!}/${MiMonitoring.sleepHeartRate(hrBody)!!}",
            "mon_hr_1min" to hrSet(hrBody, MiMonitoring.HeartRateMode.Every(1)),
            "mon_hr_off" to hrSet(hrBody, MiMonitoring.HeartRateMode.Off),
            "mon_hr_smart_nosleep" to hrSet(hrBody, MiMonitoring.HeartRateMode.Smart, false),
            "mon_hr_empty_smart" to hrSet(ByteArray(0), MiMonitoring.HeartRateMode.Smart),
            "mon_hr_garbage" to hrSet(bytes(0x0A, 0x05, 0x01), MiMonitoring.HeartRateMode.Smart),
            "mon_spo2_parsed" to (MiMonitoring.spo2Mode(spo2Body) ?: -1),
            "mon_spo2_allday" to MiMonitoring.set(MiMonitoring.Setting.SPO2, MiMonitoring.spo2(spo2Body, 2)!!).hex(),
            "mon_stress_parsed" to (MiMonitoring.stressAllDay(stressBody) ?: true),
            "mon_stress_on" to MiMonitoring.set(MiMonitoring.Setting.STRESS, MiMonitoring.stress(stressBody, true)!!).hex(),
            "ccm_decrypt" to (MiCrypto.ccmDecrypt(key, ccmNonce, longCipher) ?: ByteArray(0)).hex(),
            "ccm_decrypt_empty" to (MiCrypto.ccmDecrypt(key, ccmNonce, MiCrypto.ccmEncrypt(key, ccmNonce, ByteArray(0)))?.hex() ?: "nil"),
            "ccm_decrypt_rejects" to listOf(MiCrypto.ccmDecrypt(key, ccmNonce, tampered) == null,
                MiCrypto.ccmDecrypt(key, ccmNonce, bytes(1, 2, 3)) == null),
            "ccm_aad" to bindCCM.hex(),
            "ccm_aad_roundtrip" to (MiCrypto.ccmDecrypt(key, ccmNonce, bindCCM, bindAAD)?.contentEquals(long) == true),
            "ccm_aad_wrong_rejected" to (MiCrypto.ccmDecrypt(key, ccmNonce, bindCCM, "bind-datb".toByteArray()) == null
                && MiCrypto.ccmDecrypt(key, ccmNonce, bindCCM) == null),
            "bind_band_replies" to listOf(bandInfo(2, 3), bandVerify(), bandConfirm(true), bandError(25, 0)).map { it.hex() },
            "bind_ok" to bindRun(replies = listOf(bandInfo(2, 3), bandConfirm(true), resp.data, bandVerify(), bandConfirm(true), bandError(25, 0), bandError(25, 0))),
            "bind_code" to bindRun(name = "", code = "123456", replies = listOf(bandInfo(2, 2), bandVerify(code = "123456"), bandConfirm(true), bandReply(25, 29) {})),
            "bind_server_psk" to bindRun(replies = listOf(bandInfo(1, 0), bandVerify())),
            "bind_verify_mode_3" to bindRun(replies = listOf(bandInfo(3, 0))),
            "bind_oob_numeric" to bindRun(replies = listOf(bandInfo(2, 1))),
            "bind_already_bound" to bindRun(replies = listOf(bandError(17, 1))),
            "bind_wrong_proof" to bindRun(replies = listOf(bandInfo(2, 0), bandVerify(flip = true), bandConfirm(true))),
            "bind_wrong_code" to bindRun(code = "123456", replies = listOf(bandInfo(2, 2), bandVerify(code = "123457"))),
            "bind_off_curve" to bindRun(replies = listOf(bandInfo(2, 0), bandVerify(keyBytes = offCurve))),
            "bind_declined" to bindRun(replies = listOf(bandInfo(2, 3), bandVerify(), bandConfirm(false))),
            "bind_cancelled" to bindRun(replies = listOf(bandInfo(2, 3), bandVerify(), bandError(19, 6))),
            "bind_state_error" to bindRun(replies = listOf(bandInfo(2, 3), bandError(18, 8))),
            "bind_result_error" to bindRun(replies = listOf(bandInfo(2, 3), bandVerify(), bandConfirm(true), bandError(25, 3))),
            "bind_unexpected" to bindRun(replies = listOf(bandReply(17, 4) { string(1, "x") })),
            "bind_bad_config" to listOf(
                MiBind.Config("", "a", ""),
                MiBind.Config("u", "a".repeat(65), ""),
                MiBind.Config("u\u0000", "a", ""),
                MiBind.Config("u", "a".repeat(64), ""),
            ).map { MiBind.Machine.create(it) == null },
            "v1_nonce" to MiV1Cipher.nonce(bytes(1, 2, 3, 4), 0x0A0B0C0DL).hex(),
            "v1_plain_queue" to plainQueue,
            "v1_enc_single" to encSingle,
            "v1_enc_chunked" to encChunked,
            "v1_plain_chunked" to plainChunked,
            "v1_in_single" to inSingle,
            "v1_in_enc_frame" to inEncFrame.hex(),
            "v1_in_slices" to inSlices.map { it.hex() },
            "v1_in_chunked" to inChunked,
            "v1_unexpected" to unexpected,
            "v1_auth_reset" to authReset,
        )
        val file = File("build/vectors.json")
        file.parentFile.mkdirs()
        file.writeText(json(out))
        println("wrote ${out.size} vectors to ${file.absolutePath}")
    }

    private fun json(v: Any?): String = when (v) {
        null -> "null"
        is String -> "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        is Boolean, is Int, is Long -> v.toString()
        is Map<*, *> -> v.entries.joinToString(",", "{", "}") { json(it.key) + ":" + json(it.value) }
        is List<*> -> v.joinToString(",", "[", "]") { json(it) }
        else -> error("unsupported ${v::class}")
    }
}
