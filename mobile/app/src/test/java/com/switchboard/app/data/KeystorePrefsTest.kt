package com.switchboard.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Covers the pure half of [KeystorePrefs]: the iv ‖ ciphertext envelope and its
 * base64 round-trip. The AndroidKeyStore half cannot run on the JVM -- there is
 * no "AndroidKeyStore" provider off-device -- so key generation, key
 * invalidation and real encrypt/decrypt need an instrumented test on hardware.
 */
class KeystorePrefsTest {

    private val iv = ByteArray(12) { it.toByte() }
    private val ciphertext = ByteArray(40) { (100 - it).toByte() }

    @Test
    fun packThenUnpack_roundTrips() {
        val (gotIv, gotCt) = KeystorePrefs.unpack(KeystorePrefs.pack(iv, ciphertext))
        assertArrayEquals(iv, gotIv)
        assertArrayEquals(ciphertext, gotCt)
    }

    @Test
    fun pack_rejectsWrongSizedIv() {
        assertThrows(IllegalArgumentException::class.java) {
            KeystorePrefs.pack(ByteArray(16), ciphertext)
        }
    }

    @Test
    fun unpack_rejectsBlobTooShortForIvAndTag() {
        // 12-byte iv + 16-byte GCM tag is the floor; 28 bytes carries no payload.
        val blob = KeystorePrefs.pack(iv, ByteArray(16))
        assertThrows(IllegalArgumentException::class.java) { KeystorePrefs.unpack(blob) }
    }

    @Test
    fun unpack_rejectsTruncatedBlob() {
        val full = KeystorePrefs.pack(iv, ciphertext)
        val truncated = java.util.Base64.getEncoder().encodeToString(
            java.util.Base64.getDecoder().decode(full).copyOfRange(0, 20)
        )
        assertThrows(IllegalArgumentException::class.java) { KeystorePrefs.unpack(truncated) }
    }

    @Test
    fun unpack_rejectsNonBase64() {
        assertThrows(IllegalArgumentException::class.java) { KeystorePrefs.unpack("not base64!!") }
    }
}
