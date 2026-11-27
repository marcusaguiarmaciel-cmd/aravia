package app.aravia.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPrivateKeySpec
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * First pairing ("bind") in local mode: the phone and the band agree on the 16-byte auth key
 * without the vendor app or its server (docs/BIND.md). Pure JVM, no I/O: plaintext protobuf
 * commands in, commands and results out.
 */
object MiBind {
    const val COMMAND_TYPE = 1L
    const val ID_START = 17L
    const val ID_VERIFY = 18L
    const val ID_CONFIRM = 19L
    const val ID_RESULT = 25L

    const val VERIFY_MODE_SERVER = 1L
    const val VERIFY_MODE_LOCAL = 2L
    /** OOB modes: 0 none, 1 numeric comparison, 2 dynamic code, 3 button on the band. */
    const val OOB_BUTTON = 3L
    val SUPPORTED_OOB = setOf(0L, 2L, 3L)

    const val ERROR_ALREADY_BOUND = 1L
    const val ERROR_CANCEL = 6L
    const val ERROR_VERIFY = 7L

    data class Config(
        val userID: String,
        val appDeviceID: String,
        val deviceName: String,
        val dynamicCode: String = "",
    ) {
        /** Both ids: 1 to 64 UTF-8 bytes, no NUL. */
        val isValid: Boolean
            get() = listOf(userID, appDeviceID).all { s ->
                val b = s.toByteArray(Charsets.UTF_8)
                b.size in 1..64 && b.none { it == 0.toByte() }
            }
    }

    /** The band's answer to step 1. mac, serialNumber and did identify the band: never log them. */
    data class BandInfo(
        val verifyMode: Long,
        val oobMode: Long,
        val serialNumber: String,
        val mac: String,
        val model: String,
        val did: String,
    )

    sealed class Failure {
        object AlreadyBound : Failure() { override fun toString() = "alreadyBound" }
        object ServerBindRequired : Failure() { override fun toString() = "serverBindRequired" }
        data class UnsupportedVerifyMode(val mode: Long) : Failure() { override fun toString() = "unsupportedVerifyMode($mode)" }
        data class UnsupportedOOB(val mode: Long) : Failure() { override fun toString() = "unsupportedOOB($mode)" }
        object RejectedOnBand : Failure() { override fun toString() = "rejectedOnBand" }
        object BandProofMismatch : Failure() { override fun toString() = "bandProofMismatch" }
        data class Band(val code: Long, val step: Long) : Failure() { override fun toString() = "band(code: $code, step: $step)" }
        data class UnexpectedReply(val step: Long) : Failure() { override fun toString() = "unexpectedReply(step: $step)" }
    }

    class Bound(
        val authKey: ByteArray,
        val keys: MiAuth.SessionKeys,
        val appDeviceID: String,
        val info: BandInfo,
    )

    sealed class Step {
        class Send(val data: ByteArray, val confirmOnBand: Boolean) : Step()
        class BoundResult(val bound: Bound) : Step()
        class Failed(val failure: Failure, val tellBand: ByteArray? = null) : Step()
        object Ignored : Step()
    }

    // Commands (docs/BIND.md section 2)

    private fun command(id: Long, field: Int, body: PbWriter): ByteArray =
        envelope(id, pb { message(field, body) })

    private fun envelope(id: Long, account: PbWriter): ByteArray =
        pb { varint(1, COMMAND_TYPE); varint(2, id); message(3, account) }.data

    /** Step 1: BindStartV2{check_dynamic_code false, MD5(userID), deviceName if non-empty}. */
    fun startCommand(userID: String, deviceName: String): ByteArray {
        val md5 = MessageDigest.getInstance("MD5").digest(userID.toByteArray(Charsets.UTF_8))
        return command(ID_START, 11, pb {
            bool(1, false)
            bytes(2, md5)
            if (deviceName.isNotEmpty()) string(3, deviceName)
        })
    }

    /** Step 3: AppVerify{appDeviceID, public key as 64 bytes X ‖ Y}. */
    fun appVerifyCommand(appDeviceID: String, publicKey: ByteArray): ByteArray =
        command(ID_VERIFY, 17, pb { string(1, appDeviceID); bytes(2, publicKey) })

    /** Step 5: AppConfirm{appRandom, appProof}. */
    fun appConfirmCommand(appRandom: ByteArray, appSign: ByteArray): ByteArray =
        command(ID_CONFIRM, 19, pb { bytes(1, appRandom); bytes(2, appSign) })

    val resultNonce = ByteArray(12) { (0x10 + it).toByte() }
    val resultAAD = "bind-data".toByteArray(Charsets.UTF_8)

    /** Step 7: BindResultV2{AES-CCM(ResultInfo{userID, CompanionDevice})}. */
    fun resultCommand(encryptionKey: ByteArray, userID: String, deviceName: String): ByteArray {
        val companion = pb { varint(1, 1); string(3, deviceName); varint(4, 0xFFFF_FFFFL) }
        val info = pb { string(1, userID); message(2, companion) }
        val sealed = MiCrypto.ccmEncrypt(encryptionKey, resultNonce, info.data, resultAAD)
        return command(ID_RESULT, 29, pb { bytes(1, sealed) })
    }

    fun errorCommand(id: Long, code: Long): ByteArray = envelope(id, pb { varint(3, code) })

    // Crypto (docs/BIND.md section 3)

    fun proof(sharedSecret: ByteArray, random: ByteArray, dynamicCode: String = ""): ByteArray =
        MiCrypto.hmacSha256(sharedSecret, random + dynamicCode.toByteArray(Charsets.UTF_8))

    fun keyBlock(sharedSecret: ByteArray, appRandom: ByteArray, deviceRandom: ByteArray): ByteArray =
        MiCrypto.hkdf(appRandom + deviceRandom, sharedSecret, "miwear-bind".toByteArray(Charsets.UTF_8), 64)

    fun bandInfo(account: PbMessage): BandInfo? {
        val info = account.message(12) ?: return null
        val verifyMode = info.varints[1] ?: return null
        val oobMode = info.varints[5] ?: return null
        fun text(f: Int) = info.blobs[f]?.toString(Charsets.UTF_8) ?: ""
        return BandInfo(verifyMode, oobMode, text(2), text(3), text(4), text(7))
    }

    // P-256 helpers (pure JCA, no extra dependencies)

    object P256 {
        val params: ECParameterSpec by lazy {
            AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
                .getParameterSpec(ECParameterSpec::class.java)
        }
        private val prime: BigInteger get() = (params.curve.field as ECFieldFp).p
        private val curveA: BigInteger get() = params.curve.a
        private val curveB: BigInteger get() = params.curve.b

        data class Pair(val privateKey: ECPrivateKey, val publicKeyRaw: ByteArray)

        fun generate(): Pair {
            val kp: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(params, SecureRandom()) }.generateKeyPair()
            return Pair(kp.private as ECPrivateKey, rawPublic(kp.public as ECPublicKey))
        }

        /** Deterministic keypair from a 32-byte big-endian private scalar (for protocol vector tests). */
        fun fromScalar(scalar: ByteArray): Pair {
            require(scalar.size == 32)
            val d = BigInteger(1, scalar)
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(ECPrivateKeySpec(d, params)) as ECPrivateKey
            val q = scalarMultiply(params.generator, d)
            return Pair(priv, to32(q.affineX) + to32(q.affineY))
        }

        fun rawPublic(pub: ECPublicKey): ByteArray = to32(pub.w.affineX) + to32(pub.w.affineY)

        /** Parses 64-byte X ‖ Y into an ECPublicKey and validates it lies on secp256r1; null otherwise. */
        fun sharedSecret(privateKey: PrivateKey, rawPeerPublic: ByteArray): ByteArray? {
            if (rawPeerPublic.size != 64) return null
            val x = BigInteger(1, rawPeerPublic.copyOfRange(0, 32))
            val y = BigInteger(1, rawPeerPublic.copyOfRange(32, 64))
            if (!isOnCurve(x, y)) return null
            return runCatching {
                val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
                KeyAgreement.getInstance("ECDH").run { init(privateKey); doPhase(pub, true); generateSecret() }
            }.getOrNull()
        }

        private fun isOnCurve(x: BigInteger, y: BigInteger): Boolean {
            val p = prime
            if (x.signum() <= 0 || x >= p || y.signum() <= 0 || y >= p) return false
            val lhs = y.modPow(BigInteger.valueOf(2), p)
            val rhs = x.modPow(BigInteger.valueOf(3), p).add(curveA.multiply(x)).add(curveB).mod(p)
            return lhs == rhs
        }

        private fun to32(n: BigInteger): ByteArray {
            val raw = n.toByteArray()
            return when {
                raw.size == 32 -> raw
                raw.size > 32 -> raw.copyOfRange(raw.size - 32, raw.size)
                else -> ByteArray(32 - raw.size) + raw
            }
        }

        // Affine point multiplication over F_p (only used by deterministic test key derivation).
        private fun scalarMultiply(g: ECPoint, k: BigInteger): ECPoint {
            var result: ECPoint = ECPoint.POINT_INFINITY
            var addend = g
            for (bit in 0 until k.bitLength()) {
                if (k.testBit(bit)) result = add(result, addend)
                addend = add(addend, addend)
            }
            return result
        }

        private fun add(p1: ECPoint, p2: ECPoint): ECPoint {
            if (p1 == ECPoint.POINT_INFINITY) return p2
            if (p2 == ECPoint.POINT_INFINITY) return p1
            val p = prime
            val x1 = p1.affineX; val y1 = p1.affineY; val x2 = p2.affineX; val y2 = p2.affineY
            if (x1 == x2 && y1.add(y2).mod(p) == BigInteger.ZERO) return ECPoint.POINT_INFINITY
            val lambda = if (x1 == x2 && y1 == y2) {
                x1.multiply(x1).multiply(BigInteger.valueOf(3)).add(curveA).mod(p)
                    .multiply(y1.shiftLeft(1).modInverse(p)).mod(p)
            } else {
                y2.subtract(y1).mod(p).multiply(x2.subtract(x1).mod(p).modInverse(p)).mod(p)
            }
            val x3 = lambda.multiply(lambda).subtract(x1).subtract(x2).mod(p)
            val y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(p)
            return ECPoint(x3, y3)
        }
    }

    // State machine

    class Machine private constructor(
        val config: Config,
        private val keyPair: P256.Pair,
        private val appRandom: ByteArray,
    ) {
        private enum class Stage { IDLE, INFO, VERIFY, CONFIRM, RESULT, DONE }

        private var stage = Stage.IDLE
        private var block = ByteArray(0)
        var info: BandInfo? = null; private set

        fun start(): ByteArray {
            stage = Stage.INFO
            return startCommand(config.userID, config.deviceName)
        }

        fun receive(command: ByteArray): Step {
            val expected = mapOf(Stage.INFO to ID_START, Stage.VERIFY to ID_VERIFY, Stage.CONFIRM to ID_CONFIRM, Stage.RESULT to ID_RESULT)
            val waitingFor = expected[stage] ?: return Step.Ignored
            val message = PbMessage.parse(command) ?: return Step.Ignored
            if (message.varints[1] != COMMAND_TYPE) return Step.Ignored
            val id = message.varints[2] ?: return Step.Ignored
            if (id !in expected.values) return Step.Ignored
            val account = message.message(3)

            val code = account?.varints?.get(3)
            if (code != null && !(code == 0L && id == ID_RESULT && stage == Stage.RESULT)) {
                return when (code) {
                    ERROR_ALREADY_BOUND -> fail(Failure.AlreadyBound)
                    ERROR_CANCEL -> fail(Failure.RejectedOnBand)
                    else -> fail(Failure.Band(code, id))
                }
            }
            if (id != waitingFor) return Step.Ignored
            val waitsForButton = info?.oobMode == OOB_BUTTON

            return when (stage) {
                Stage.INFO -> {
                    val got = account?.let { bandInfo(it) } ?: return fail(Failure.UnexpectedReply(id))
                    info = got
                    if (got.verifyMode != VERIFY_MODE_LOCAL) {
                        return fail(if (got.verifyMode == VERIFY_MODE_SERVER) Failure.ServerBindRequired else Failure.UnsupportedVerifyMode(got.verifyMode))
                    }
                    if (got.oobMode !in SUPPORTED_OOB) return fail(Failure.UnsupportedOOB(got.oobMode))
                    stage = Stage.VERIFY
                    Step.Send(appVerifyCommand(config.appDeviceID, keyPair.publicKeyRaw), confirmOnBand = got.oobMode == OOB_BUTTON)
                }
                Stage.VERIFY -> {
                    val verify = account?.message(18) ?: return fail(Failure.UnexpectedReply(id))
                    val key = verify.blobs[1]; val random = verify.blobs[2]; val sign = verify.blobs[3]
                    if (key == null || random == null || sign == null || key.size != 64 || random.size != 16 || sign.size != 32) {
                        return fail(Failure.BandProofMismatch, tellBand = errorCommand(ID_CONFIRM, ERROR_VERIFY))
                    }
                    val secret = P256.sharedSecret(keyPair.privateKey, key)
                    if (secret == null || !proof(secret, random, config.dynamicCode).contentEquals(sign)) {
                        return fail(Failure.BandProofMismatch, tellBand = errorCommand(ID_CONFIRM, ERROR_VERIFY))
                    }
                    block = keyBlock(secret, appRandom, random)
                    stage = Stage.CONFIRM
                    val appSign = proof(secret, appRandom, config.dynamicCode)
                    Step.Send(appConfirmCommand(appRandom, appSign), confirmOnBand = waitsForButton)
                }
                Stage.CONFIRM -> {
                    val confirmed = account?.message(20)?.varints?.get(1) ?: return fail(Failure.UnexpectedReply(id))
                    if (confirmed == 0L) return fail(Failure.RejectedOnBand)
                    stage = Stage.RESULT
                    Step.Send(resultCommand(block.copyOfRange(16, 32), config.userID, config.deviceName), confirmOnBand = false)
                }
                Stage.RESULT -> {
                    val got = info ?: return fail(Failure.UnexpectedReply(id))
                    stage = Stage.DONE
                    val keys = MiAuth.SessionKeys(
                        block.copyOfRange(0, 16), block.copyOfRange(16, 32),
                        block.copyOfRange(32, 36), block.copyOfRange(36, 40),
                    )
                    Step.BoundResult(Bound(block.copyOfRange(40, 56), keys, config.appDeviceID, got))
                }
                Stage.IDLE, Stage.DONE -> Step.Ignored
            }
        }

        private fun fail(failure: Failure, tellBand: ByteArray? = null): Step {
            stage = Stage.DONE
            return Step.Failed(failure, tellBand)
        }

        companion object {
            fun create(
                config: Config,
                keyPair: P256.Pair = P256.generate(),
                appRandom: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) },
            ): Machine? {
                if (!config.isValid || appRandom.size != 16) return null
                return Machine(config, keyPair, appRandom)
            }
        }
    }
}
