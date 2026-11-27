package app.aravia.protocol

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MiCrypto {
    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal(message) }

    /** HKDF-SHA256 (RFC 5869). */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = java.io.ByteArrayOutputStream()
        var t = ByteArray(0)
        var i = 1
        while (out.size() < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(i.toByte()))
            out.write(t)
            i++
        }
        return out.toByteArray().copyOf(length)
    }

    /** salt = phoneNonce ‖ watchNonce, IKM = auth key, info = "miwear-auth", 64 bytes. */
    fun sessionKeyMaterial(authKey: ByteArray, phoneNonce: ByteArray, watchNonce: ByteArray): ByteArray =
        hkdf(phoneNonce + watchNonce, authKey, "miwear-auth".toByteArray(), 64)

    fun aesBlock(key: ByteArray, block: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(block) }

    private const val TAG = 4
    private const val L = 3

    /** AES-128-CCM (RFC 3610), 12-byte nonce, 4-byte tag. Returns ciphertext ‖ tag. */
    fun ccmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        require(nonce.size == 12)
        return keystream(key, nonce, plaintext) + tag(key, nonce, aad, plaintext)
    }

    /** Null when the input is shorter than the tag or the tag doesn't match. */
    fun ccmDecrypt(key: ByteArray, nonce: ByteArray, ciphertextAndTag: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        require(nonce.size == 12)
        if (ciphertextAndTag.size < TAG) return null
        val n = ciphertextAndTag.size - TAG
        val plaintext = keystream(key, nonce, ciphertextAndTag.copyOfRange(0, n))
        val t = tag(key, nonce, aad, plaintext)
        var diff = 0
        for (j in 0 until TAG) diff = diff or (t[j].toInt() xor ciphertextAndTag[n + j].toInt())
        return if (diff == 0) plaintext else null
    }

    private fun counterBlock(nonce: ByteArray, counter: Int) =
        byteArrayOf((L - 1).toByte()) + nonce + bytes((counter shr 16) and 0xFF, (counter shr 8) and 0xFF, counter and 0xFF)

    private fun tag(key: ByteArray, nonce: ByteArray, aad: ByteArray, p: ByteArray): ByteArray {
        require(aad.size < 0xFF00)
        val flags = (if (aad.isEmpty()) 0 else 0x40) or (((TAG - 2) / 2) shl 3) or (L - 1)
        val b0 = byteArrayOf(flags.toByte()) + nonce + bytes((p.size shr 16) and 0xFF, (p.size shr 8) and 0xFF, p.size and 0xFF)
        var mac = aesBlock(key, b0)
        val a = if (aad.isEmpty()) ByteArray(0) else bytes(aad.size shr 8, aad.size and 0xFF) + aad
        for (part in listOf(a, p)) {
            var start = 0
            while (start < part.size) {
                val block = mac.copyOf()
                for (j in 0 until minOf(16, part.size - start)) block[j] = (block[j].toInt() xor part[start + j].toInt()).toByte()
                mac = aesBlock(key, block)
                start += 16
            }
        }
        val s0 = aesBlock(key, counterBlock(nonce, 0))
        return ByteArray(TAG) { (mac[it].toInt() xor s0[it].toInt()).toByte() }
    }

    private fun keystream(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val out = ByteArray(input.size)
        var start = 0
        var n = 1
        while (start < input.size) {
            val s = aesBlock(key, counterBlock(nonce, n))
            for (j in 0 until minOf(16, input.size - start)) out[start + j] = (input[start + j].toInt() xor s[j].toInt()).toByte()
            start += 16
            n++
        }
        return out
    }

    /** AES-128-CTR with the key as the initial counter block (V2 channel; restarts per message). */
    fun ctr(key: ByteArray, input: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(key))
            doFinal(input)
        }
}
