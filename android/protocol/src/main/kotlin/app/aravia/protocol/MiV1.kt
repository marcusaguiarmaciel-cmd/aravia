package app.aravia.protocol

// BLE V1 (FE95: 0051 read, 0052 write, 0053 activity), docs/BLE-V1.md. No I/O: each call
// returns what to write to the same characteristic and what happened.

class MiV1Cipher(val keys: MiAuth.SessionKeys) {
    fun encrypt(plaintext: ByteArray, counter: Int): ByteArray =
        MiCrypto.ccmEncrypt(keys.encryptionKey, nonce(keys.encryptionNonce, counter.toLong()), plaintext)

    /** Watch to phone, counter 0. */
    fun decrypt(ciphertextAndTag: ByteArray): ByteArray? =
        MiCrypto.ccmDecrypt(keys.decryptionKey, nonce(keys.decryptionNonce, 0), ciphertextAndTag)

    companion object {
        /** nonce4 ‖ 00 00 00 00 ‖ counter (u32 LE). */
        fun nonce(base: ByteArray, counter: Long): ByteArray =
            base + ByteArray(4) + bytes((counter and 0xFF).toInt(), ((counter shr 8) and 0xFF).toInt(),
                ((counter shr 16) and 0xFF).toInt(), ((counter shr 24) and 0xFF).toInt())
    }
}

class MiV1Channel(var maxWrite: Int = 244) {
    sealed class Event {
        class Message(val data: ByteArray) : Event()
        data class Acknowledged(val accepted: Boolean) : Event()
        data class Undecryptable(val count: Int) : Event()
        data class Unexpected(val what: String) : Event()
    }

    class Output(val writes: MutableList<ByteArray> = mutableListOf(), val events: MutableList<Event> = mutableListOf()) {
        fun append(o: Output) { writes += o.writes; events += o.events }
    }

    var cipher: MiV1Cipher? = null
    var encrypts = false; private set
    var counter = 1; private set

    private sealed class InFlight {
        object Single : InFlight()
        class Chunked(val body: ByteArray, val sliceSize: Int) : InFlight()
    }

    private val queue = ArrayDeque<ByteArray>()
    private var inFlight: InFlight? = null
    private var expectedSlices = 0
    private var incomingEncrypted = false
    private val slices = mutableMapOf<Int, ByteArray>()

    val isReceiving: Boolean get() = expectedSlices > 0
    private val writeSize: Int get() = maxOf(maxWrite, 20)

    fun authenticated() {
        encrypts = true; counter = 1
        queue.clear(); inFlight = null; expectedSlices = 0; slices.clear()
    }

    fun send(message: ByteArray): Output { queue.addLast(message); return sendNext() }

    fun receive(value: ByteArray): Output {
        val out = Output()
        if (value.size < 3) { out.events += Event.Unexpected("short value ${value.hex()}"); return out }
        val b = { i: Int -> value[i].toInt() and 0xFF }
        val chunk = b(0) or (b(1) shl 8)
        if (chunk != 0) { receiveSlice(chunk, value.copyOfRange(2, value.size), out); return out }
        val type = b(2)
        if (type > 3 || value.size < (if (type == 0) 6 else 4)) {
            out.events += Event.Unexpected("control ${value.hex()}"); return out
        }
        when (type) {
            0 -> {
                if (expectedSlices > 0) out.events += Event.Unexpected("chunked start with ${slices.size} of $expectedSlices slices pending")
                incomingEncrypted = b(3) == 1
                expectedSlices = b(4) or (b(5) shl 8)
                slices.clear()
                out.writes += bytes(0, 0, 1, 1)
            }
            1 -> chunkAcknowledgement(b(3), value.copyOfRange(4, value.size), out)
            2 -> {
                out.writes += bytes(0, 0, 3, 0)
                deliver(value.copyOfRange(4, value.size), b(3) == 1, out)
            }
            else -> {
                if (inFlight !is InFlight.Single) {
                    out.events += Event.Unexpected("acknowledgement ${value.hex()} with no single message in flight"); return out
                }
                inFlight = null
                out.events += Event.Acknowledged(b(3) == 0)
                out.append(sendNext())
            }
        }
        return out
    }

    fun requestMissing(): Output {
        val out = Output()
        if (expectedSlices == 0) return out
        val missing = (1..expectedSlices).filter { it !in slices }
        if (missing.isEmpty()) return out
        var write = bytes(0, 0, 1, 5)
        for (i in missing.take((writeSize - 4) / 2)) write += u16(i)
        out.writes += write
        return out
    }

    private fun sendNext(): Output {
        val out = Output()
        if (inFlight != null || queue.isEmpty()) return out
        val message = queue.removeFirst()
        var body = message
        var ctr: Int? = null
        val c = cipher
        if (encrypts && c != null) {
            ctr = counter
            body = c.encrypt(message, counter)
            counter = (counter + 1) and 0xFFFF
        }
        if (body.size + 6 <= writeSize) {
            var write = bytes(0, 0, 2, if (ctr == null) 2 else 1)
            if (ctr != null) write += u16(ctr)
            inFlight = InFlight.Single
            out.writes += write + body
        } else {
            val whole = (ctr?.let { u16(it) } ?: ByteArray(0)) + body
            val sliceSize = writeSize - 2
            val count = (whole.size + sliceSize - 1) / sliceSize
            inFlight = InFlight.Chunked(whole, sliceSize)
            out.writes += bytes(0, 0, 0, if (ctr == null) 0 else 1) + u16(count)
        }
        return out
    }

    private fun chunkAcknowledgement(subtype: Int, rest: ByteArray, out: Output) {
        val f = inFlight as? InFlight.Chunked ?: run {
            out.events += Event.Unexpected("chunk acknowledgement $subtype with no chunked message in flight"); return
        }
        val count = (f.body.size + f.sliceSize - 1) / f.sliceSize
        fun slice(index: Int): ByteArray {
            val start = (index - 1) * f.sliceSize
            return u16(index) + f.body.copyOfRange(start, minOf(start + f.sliceSize, f.body.size))
        }
        when (subtype) {
            1 -> for (i in 1..count) out.writes += slice(i)
            0, 2 -> { inFlight = null; out.events += Event.Acknowledged(subtype == 0); out.append(sendNext()) }
            5 -> {
                val wanted = (0 until rest.size - 1 step 2).map { (rest[it].toInt() and 0xFF) or ((rest[it + 1].toInt() and 0xFF) shl 8) }
                val valid = wanted.filter { it in 1..count }
                if (valid.isEmpty()) { out.events += Event.Unexpected("resend request ${rest.hex()} for $count slices"); return }
                for (i in valid) out.writes += slice(i)
            }
            else -> out.events += Event.Unexpected("chunk acknowledgement subtype $subtype")
        }
    }

    private fun receiveSlice(index: Int, data: ByteArray, out: Output) {
        if (index > expectedSlices) { out.events += Event.Unexpected("slice $index of $expectedSlices"); return }
        slices[index] = data
        if (slices.size != expectedSlices) return
        out.writes += bytes(0, 0, 1, 0)
        var joined = ByteArray(0)
        for (i in 1..expectedSlices) joined += slices[i] ?: ByteArray(0)
        expectedSlices = 0
        slices.clear()
        deliver(joined, incomingEncrypted, out)
    }

    private fun deliver(body: ByteArray, encrypted: Boolean, out: Output) {
        if (!encrypted) { out.events += Event.Message(body); return }
        val plain = cipher?.decrypt(body)
        out.events += if (plain == null) Event.Undecryptable(body.size) else Event.Message(plain)
    }

    private fun u16(v: Int) = bytes(v and 0xFF, (v shr 8) and 0xFF)
}
