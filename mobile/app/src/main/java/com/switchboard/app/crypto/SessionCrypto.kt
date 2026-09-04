package com.switchboard.app.crypto

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters

/**
 * Client half of the Switchboard handshake. This is a byte-for-byte mirror of
 * backend/internal/crypto; any change here must be made there too.
 *
 * X25519 comes from BouncyCastle rather than the platform: `KeyPairGenerator
 * .getInstance("XDH")` only exists from API 33, and Switchboard supports 26.
 */
object SessionCrypto {

    const val KEY_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128

    private const val INFO_SESSION = "switchboard-session-v1"
    private const val INFO_H2C = "switchboard-host-to-client-v1"
    private const val INFO_C2H = "switchboard-client-to-host-v1"
    private const val INFO_PROOF = "switchboard-proof-v1"

    private val random = SecureRandom()

    fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    fun decode(text: String): ByteArray = Base64.getUrlDecoder().decode(text)

    /** A long-lived or single-use X25519 keypair. */
    class KeyPair(val private: X25519PrivateKeyParameters) {
        val publicKey: ByteArray get() = private.generatePublicKey().encoded
        val seed: ByteArray get() = private.encoded

        companion object {
            fun generate(): KeyPair = KeyPair(X25519PrivateKeyParameters(SecureRandom()))

            fun fromSeed(seed: ByteArray): KeyPair =
                KeyPair(X25519PrivateKeyParameters(seed, 0))
        }
    }

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

    private fun agree(privateKey: X25519PrivateKeyParameters, peerPublic: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(privateKey)
        return ByteArray(agreement.agreementSize).also {
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), it, 0)
        }
    }

    private fun hkdf(ikm: ByteArray, salt: ByteArray?, info: String, length: Int): ByteArray {
        val generator = HKDFBytesGenerator(SHA256Digest())
        generator.init(HKDFParameters(ikm, salt, info.toByteArray()))
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    /**
     * Derives the session from the host's hello.
     *
     * Mixing the ephemeral agreement gives forward secrecy; mixing the identity
     * agreement authenticates both ends. [pairingCode] is the QR secret on first
     * pairing and null when resuming a stored trust relationship.
     */
    fun derive(
        identity: KeyPair,
        ephemeral: KeyPair,
        hostIdentityPub: ByteArray,
        hostEphemeralPub: ByteArray,
        challenge: ByteArray,
        pairingCode: ByteArray?
    ): Session {
        val ikm = agree(ephemeral.private, hostEphemeralPub) +
            agree(identity.private, hostIdentityPub)
        val salt = challenge + (pairingCode ?: ByteArray(0))

        val master = hkdf(ikm, salt, INFO_SESSION, 32)
        return Session(
            hostToClient = hkdf(master, null, INFO_H2C, 32),
            clientToHost = hkdf(master, null, INFO_C2H, 32),
            proofKey = hkdf(master, null, INFO_PROOF, 32)
        )
    }

    /**
     * An established session. The client sends on [clientToHost] and receives on
     * [hostToClient]; separate keys per direction let both sides use a plain
     * counter as the nonce without ever colliding.
     */
    class Session(
        private val hostToClient: ByteArray,
        private val clientToHost: ByteArray,
        private val proofKey: ByteArray
    ) {
        private var sendCounter = 0L
        private var receiveCounter = 0L

        /** HMAC over both identities, proving each side derived the same key. */
        fun proof(label: String, daemonId: String, clientPublicKey: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(proofKey, "HmacSHA256"))
            mac.update(label.toByteArray())
            mac.update(daemonId.toByteArray())
            mac.update(clientPublicKey)
            return mac.doFinal()
        }

        fun verifyProof(
            label: String,
            daemonId: String,
            clientPublicKey: ByteArray,
            received: ByteArray
        ): Boolean = constantTimeEquals(proof(label, daemonId, clientPublicKey), received)

        fun seal(plaintext: ByteArray): ByteArray {
            val nonce = nonceFor(sendCounter++)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(clientToHost, "AES"),
                GCMParameterSpec(TAG_BITS, nonce)
            )
            return nonce + cipher.doFinal(plaintext)
        }

        /** Rejects replayed and reordered frames by requiring a rising counter. */
        fun open(frame: ByteArray): ByteArray {
            require(frame.size > NONCE_SIZE + 16) { "frame too short" }
            val nonce = frame.copyOfRange(0, NONCE_SIZE)
            val counter = counterFrom(nonce)
            require(counter >= receiveCounter) { "replayed or reordered frame" }

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(hostToClient, "AES"),
                GCMParameterSpec(TAG_BITS, nonce)
            )
            val plaintext = cipher.doFinal(frame, NONCE_SIZE, frame.size - NONCE_SIZE)
            receiveCounter = counter + 1
            return plaintext
        }

        private fun nonceFor(counter: Long): ByteArray {
            val nonce = ByteArray(NONCE_SIZE)
            for (i in 0 until 8) {
                nonce[NONCE_SIZE - 1 - i] = ((counter shr (8 * i)) and 0xFF).toByte()
            }
            return nonce
        }

        private fun counterFrom(nonce: ByteArray): Long {
            var value = 0L
            for (i in NONCE_SIZE - 8 until NONCE_SIZE) {
                value = (value shl 8) or (nonce[i].toLong() and 0xFF)
            }
            return value
        }
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
