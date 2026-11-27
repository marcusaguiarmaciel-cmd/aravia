package app.aravia.protocol

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Opens the QR that Aravia on iPhone shows under Relógio › Sobre › "Levar para o Android"
 * (docs/KEY-TRANSFER.md): "ARAVIA-KEY1:" + base64url(salt16 ‖ nonce12 ‖ ciphertext ‖ tag16),
 * PBKDF2-HMAC-SHA256 of the six digits (150 000 rounds, 32 bytes), AES-256-GCM with AAD "ARAVIA-KEY1".
 */
object KeyTransfer {
    const val PREFIX = "ARAVIA-KEY1:"
    private const val ITERATIONS = 150_000

    sealed class Result {
        class Opened(val json: String) : Result()
        object NotAravia : Result()
        object WrongCode : Result()
    }

    fun isAravia(text: String) = text.trim().startsWith(PREFIX)

    fun open(text: String, code: String): Result {
        val t = text.trim()
        if (!t.startsWith(PREFIX)) return Result.NotAravia
        val raw = runCatching { Base64.getUrlDecoder().decode(t.removePrefix(PREFIX)) }.getOrNull() ?: return Result.NotAravia
        if (raw.size < 16 + 12 + 16) return Result.NotAravia
        val salt = raw.copyOfRange(0, 16)
        val nonce = raw.copyOfRange(16, 28)
        val sealed = raw.copyOfRange(28, raw.size)
        val spec = PBEKeySpec(code.toCharArray(), salt, ITERATIONS, 256)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        val plain = runCatching {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD("ARAVIA-KEY1".toByteArray())
                doFinal(sealed)
            }
        }.getOrNull() ?: return Result.WrongCode
        return Result.Opened(String(plain, Charsets.UTF_8))
    }
}
