package com.switchboard.app.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Kotlin handshake to the Go daemon byte-for-byte.
 *
 * Expected values are produced by `TestPrintInteropVector` in
 * backend/internal/crypto. If either key schedule drifts, these assertions fail
 * here rather than surfacing on a socket as an opaque decryption error. After a
 * deliberate protocol change, re-run that Go test and update both sides.
 */
class SessionCryptoInteropTest {

    private companion object {
        const val HOST_IDENTITY_PUB =
            "07a37cbc142093c8b755dc1b10e86cb426374ad16aa853ed0bdfc0b2b86d1c7c"
        const val HOST_EPHEMERAL_PUB =
            "e05b1ae322024838095ffdefd865468ddb77b14b2dd275175ea0ab4294fa6d7a"
        const val CLIENT_IDENTITY_PUB =
            "7d9c24316539825c1896e57f28197746793ce60cbee3ad47da9d07b85fa55e2a"
        const val CLIENT_PROOF =
            "33407b42d3743f7764b02c43d8706ccf92e322b06457ddcc52f2311957d02bde"
        const val HOST_PROOF =
            "7a0dd87c4d7226f75ae1539c8bcc3e80d47946f69624131446dbf07b0b9e80c0"
        const val CLIENT_FRAME =
            "000000000000000000000000d7f2897a9d855d33d8dd60c8e5b7e9435e280d31" +
                "ae08bf94cecd6952592a0b53f18f87"
        const val HOST_FRAME =
            "0000000000000000000000007c5c4d26f42f734840a29771f1e6c1ddcd28cd72" +
                "3ef6be981edf0cf3a878"
        const val DAEMON_ID = "daemon-fixed"
    }

    /** Deterministic stand-ins for the seeds the Go vector uses. */
    private fun seed(start: Int) = ByteArray(32) { i -> (start + i).toByte() }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun unhex(text: String) =
        ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val hostIdentity = SessionCrypto.KeyPair.fromSeed(seed(1))
    private val hostEphemeral = SessionCrypto.KeyPair.fromSeed(seed(50))
    private val clientIdentity = SessionCrypto.KeyPair.fromSeed(seed(100))
    private val clientEphemeral = SessionCrypto.KeyPair.fromSeed(seed(150))
    private val challenge = seed(200)
    private val pairingCode = "pairing-code".toByteArray()

    private fun session() = SessionCrypto.derive(
        identity = clientIdentity,
        ephemeral = clientEphemeral,
        hostIdentityPub = hostIdentity.publicKey,
        hostEphemeralPub = hostEphemeral.publicKey,
        challenge = challenge,
        pairingCode = pairingCode
    )

    @Test
    fun `X25519 public keys match Go`() {
        assertEquals(HOST_IDENTITY_PUB, hex(hostIdentity.publicKey))
        assertEquals(HOST_EPHEMERAL_PUB, hex(hostEphemeral.publicKey))
        assertEquals(CLIENT_IDENTITY_PUB, hex(clientIdentity.publicKey))
    }

    @Test
    fun `client proof matches Go`() {
        assertEquals(
            CLIENT_PROOF,
            hex(session().proof("client", DAEMON_ID, clientIdentity.publicKey))
        )
    }

    @Test
    fun `host proof is accepted`() {
        // This is exactly the check SwitchboardClient runs before trusting a
        // host: the daemon must prove it derived the same session.
        assertTrue(
            session().verifyProof(
                label = "host",
                daemonId = DAEMON_ID,
                clientPublicKey = clientIdentity.publicKey,
                received = unhex(HOST_PROOF)
            )
        )
    }

    @Test
    fun `an impostor host proof is rejected`() {
        val forged = unhex(HOST_PROOF).also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertTrue(
            "a wrong host proof must not be accepted",
            !session().verifyProof("host", DAEMON_ID, clientIdentity.publicKey, forged)
        )
    }

    @Test
    fun `outbound frame is byte-identical to Go`() {
        assertEquals(CLIENT_FRAME, hex(session().seal("switchboard-interop".toByteArray())))
    }

    @Test
    fun `a frame sealed by the Go host decrypts here`() {
        assertArrayEquals(
            "host-to-client".toByteArray(),
            session().open(unhex(HOST_FRAME))
        )
    }

    @Test
    fun `a tampered host frame is rejected`() {
        val frame = unhex(HOST_FRAME).also {
            it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte()
        }
        assertTrue(
            "a tampered frame must not decrypt",
            runCatching { session().open(frame) }.isFailure
        )
    }

    @Test
    fun `a replayed frame is rejected`() {
        val session = session()
        session.open(unhex(HOST_FRAME))
        assertTrue(
            "a replayed frame must not be accepted twice",
            runCatching { session.open(unhex(HOST_FRAME)) }.isFailure
        )
    }

    @Test
    fun `a wrong pairing code cannot reach the same session`() {
        val wrong = SessionCrypto.derive(
            identity = clientIdentity,
            ephemeral = clientEphemeral,
            hostIdentityPub = hostIdentity.publicKey,
            hostEphemeralPub = hostEphemeral.publicKey,
            challenge = challenge,
            pairingCode = "guessed".toByteArray()
        )
        assertNotEquals(
            CLIENT_PROOF,
            hex(wrong.proof("client", DAEMON_ID, clientIdentity.publicKey))
        )
    }
}
