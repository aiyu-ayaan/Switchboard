package com.switchboard.app.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [unlockMessage] has to agree with protocol.UnlockMessage in the Go host
 * byte for byte. Nothing at runtime reports a mismatch — the signature simply
 * fails to verify and the desktop stays locked — so the shape is pinned here.
 */
class UnlockMessageTest {

    @Test
    fun `matches the layout the host reconstructs`() {
        val expected = "switchboard-unlock-v1".toByteArray() +
            byteArrayOf(0) +
            "daemon-1".toByteArray() +
            byteArrayOf(0) +
            byteArrayOf(1, 2, 3)

        assertArrayEquals(expected, unlockMessage("daemon-1", byteArrayOf(1, 2, 3)))
    }

    /**
     * The separators are what stop a proof for one desktop reading as a proof
     * for another: without them, a daemon ID and a challenge could run
     * together into the same bytes as a different pair.
     */
    @Test
    fun `field boundaries are unambiguous`() {
        assertNotEquals(
            unlockMessage("daemon", byteArrayOf(0x41, 0x42)).toList(),
            unlockMessage("daemonAB", ByteArray(0)).toList()
        )
    }
}
