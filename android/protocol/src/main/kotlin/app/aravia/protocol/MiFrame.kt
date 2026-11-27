package app.aravia.protocol

// Xiaomi wearable protocol over BLE V2 (service FE95, RX 005E, TX 005F): framing.
// Written from Aravia's own MIT Swift (ios/Freeband/Band/MiProtocol.swift) and docs/; checked
// against the same vectors (ios/tests/protocol/check_vectors.py).

object MiFrame {
    const val KIND_NACK = 0
    const val KIND_ACK = 1
    const val KIND_SESSION_CONFIG = 2
    const val KIND_DATA = 3

    class Packet(val kind: Int, val seq: Int, val payload: ByteArray)

    /** START_SESSION_REQUEST: opcode 1, version 01.00.00, max frame 0xFC00, TX window 32, timeout 10000 ms. */
    val startSessionPayload: ByteArray = bytes(
        0x01,
        0x01, 0x03, 0x00, 0x01, 0x00, 0x00,
        0x02, 0x02, 0x00, 0x00, 0xFC,
        0x03, 0x02, 0x00, 0x20, 0x00,
        0x04, 0x02, 0x00, 0x10, 0x27,
    )

    const val TX_WINDOW = 32

    /** Seqs to resend for a NACK carrying `nack` when `next` is about to be sent; none outside the window. */
    fun resendSeqs(next: Int, nack: Int): List<Int> {
        val pending = (next - nack) and 0xFF
        if (pending <= 0 || pending > TX_WINDOW) return emptyList()
        return (0 until pending).map { (nack + it) and 0xFF }
    }

    /** CRC-16/ARC over the payload only. */
    fun crc16arc(data: ByteArray): Int {
        var crc = 0
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 }
        }
        return crc
    }

    /** a5 a5 | type | seq | len (u16 LE) | crc16 (u16 LE) | payload */
    fun encode(kind: Int, seq: Int, payload: ByteArray): ByteArray {
        val len = payload.size
        val crc = crc16arc(payload)
        return bytes(0xA5, 0xA5, kind and 0x0F, seq and 0xFF, len and 0xFF, len ushr 8, crc and 0xFF, crc ushr 8) + payload
    }

    /** Removes every complete frame from the front of `buffer`; junk and bad checksums are dropped. */
    fun drain(buffer: MutableList<Byte>): List<Packet> {
        val packets = mutableListOf<Packet>()
        fun at(i: Int) = buffer[i].toInt() and 0xFF
        while (buffer.size >= 2) {
            if (at(0) != 0xA5 || at(1) != 0xA5) {
                val next = (1 until buffer.size).firstOrNull { at(it) == 0xA5 }
                if (next != null) repeat(next) { buffer.removeAt(0) } else buffer.clear()
                continue
            }
            if (buffer.size < 3) break
            if (at(2) and 0x0F > KIND_DATA) { buffer.removeAt(0); continue }
            if (buffer.size < 8) break
            val len = at(4) or (at(5) shl 8)
            if (buffer.size < 8 + len) break
            val crc = at(6) or (at(7) shl 8)
            val payload = ByteArray(len) { buffer[8 + it] }
            val kind = at(2) and 0x0F
            val seq = at(3)
            repeat(8 + len) { buffer.removeAt(0) }
            if (crc16arc(payload) == crc) packets.add(Packet(kind, seq, payload))
        }
        return packets
    }
}

internal fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.unhex(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
