package com.switchboard.app.transfer

import com.switchboard.app.net.CHUNK_SIZE
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferMathTest {

    @Test
    fun chunkLength_isFullUntilTheTail() {
        val size = CHUNK_SIZE * 2L + 17
        assertEquals(CHUNK_SIZE, TransferMath.chunkLength(size, 0))
        assertEquals(CHUNK_SIZE, TransferMath.chunkLength(size, CHUNK_SIZE.toLong()))
        assertEquals(17, TransferMath.chunkLength(size, CHUNK_SIZE * 2L))
    }

    @Test
    fun chunkLength_stopsAtOrPastTheEnd() {
        assertEquals(0, TransferMath.chunkLength(100, 100))
        assertEquals(0, TransferMath.chunkLength(100, 101))
        assertEquals(0, TransferMath.chunkLength(100, -1))
    }

    @Test
    fun chunkCount_roundsUp() {
        assertEquals(0, TransferMath.chunkCount(0))
        assertEquals(1, TransferMath.chunkCount(1))
        assertEquals(1, TransferMath.chunkCount(CHUNK_SIZE.toLong()))
        assertEquals(2, TransferMath.chunkCount(CHUNK_SIZE + 1L))
    }

    @Test
    fun resumeOffset_acceptsAPartialFile() {
        assertEquals(4096, TransferMath.resumeOffset(4096, 8192))
        assertEquals(8192, TransferMath.resumeOffset(8192, 8192))
    }

    @Test
    fun resumeOffset_restartsWhenThePeerOverstatesWhatItHolds() {
        assertEquals(0, TransferMath.resumeOffset(9000, 8192))
        assertEquals(0, TransferMath.resumeOffset(-1, 8192))
        assertEquals(0, TransferMath.resumeOffset(0, 8192))
    }

    @Test
    fun sha256Hex_matchesTheKnownVectorForAbc() {
        val digest = TransferMath.sha256Hex(ByteArrayInputStream("abc".toByteArray()))
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digest)
    }

    @Test
    fun sha256Hex_readsBeyondASingleChunk() {
        val bytes = ByteArray(CHUNK_SIZE + 1234) { (it % 251).toByte() }
        val once = TransferMath.sha256Hex(ByteArrayInputStream(bytes))
        val again = TransferMath.sha256Hex(ByteArrayInputStream(bytes))
        assertEquals(once, again)
        assertEquals(64, once.length)
    }

    @Test
    fun digestMatches_isCaseInsensitiveAndSkippedWhenAbsent() {
        assertTrue(TransferMath.digestMatches("ABCD", "abcd"))
        assertTrue(TransferMath.digestMatches("", "abcd"))
        assertFalse(TransferMath.digestMatches("abcd", "abce"))
        assertFalse(TransferMath.digestMatches("abcd", ""))
    }

    @Test
    fun bytesPerSec_guardsTheFirstTick() {
        assertEquals(0, TransferMath.bytesPerSec(1000, 0))
        assertEquals(0, TransferMath.bytesPerSec(0, 1000))
        assertEquals(1000, TransferMath.bytesPerSec(1000, 1000))
        assertEquals(2000, TransferMath.bytesPerSec(1000, 500))
    }

    @Test
    fun formatRate_convertsBytesToBitsForTheNetworkUnit() {
        assertEquals("1.0 MB/s", TransferMath.formatRate(1_000_000, RateUnit.BYTES))
        assertEquals("8.0 Mb/s", TransferMath.formatRate(1_000_000, RateUnit.BITS))
        assertEquals("0.0 MB/s", TransferMath.formatRate(0, RateUnit.BYTES))
    }

    @Test
    fun formatBytes_picksTheReadableUnit() {
        assertEquals("512 B", TransferMath.formatBytes(512))
        assertEquals("1.5 KB", TransferMath.formatBytes(1_500))
        assertEquals("2.0 MB", TransferMath.formatBytes(2_000_000))
        assertEquals("3.0 GB", TransferMath.formatBytes(3_000_000_000))
    }
}
