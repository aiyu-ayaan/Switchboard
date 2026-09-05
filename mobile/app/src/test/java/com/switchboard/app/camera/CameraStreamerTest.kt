package com.switchboard.app.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure parts of the streaming hot path: the NV21 geometry that replaced
 * per-frame JPEG decode-and-recompress, and the auto-exposure range picker
 * that decides whether the sensor actually runs at the rate the user asked
 * for. Everything else in [CameraStreamer] needs a camera.
 */
class CameraStreamerTest {

    // 4x2 luma, one row of two V,U chroma pairs.
    private val fixture = byteArrayOf(
        1, 2, 3, 4,
        5, 6, 7, 8,
        10, 11, 12, 13
    )

    @Test
    fun `rotate 90 matches a hand-worked fixture`() {
        assertArrayEquals(
            byteArrayOf(
                5, 1,
                6, 2,
                7, 3,
                8, 4,
                10, 11, 12, 13
            ),
            rotateNv21(fixture, 4, 2, 90)
        )
    }

    @Test
    fun `rotate 180 matches a hand-worked fixture`() {
        assertArrayEquals(
            byteArrayOf(
                8, 7, 6, 5,
                4, 3, 2, 1,
                12, 13, 10, 11
            ),
            rotateNv21(fixture, 4, 2, 180)
        )
    }

    @Test
    fun `four 90 degree turns are the identity`() {
        val w = 8
        val h = 4
        val src = ByteArray(w * h * 3 / 2) { (it * 7 + 3).toByte() }
        var buf = src
        var bw = w
        var bh = h
        repeat(4) {
            buf = rotateNv21(buf, bw, bh, 90)
            val swap = bw; bw = bh; bh = swap
        }
        assertEquals(w, bw)
        assertArrayEquals(src, buf)
    }

    @Test
    fun `90 then 270 is the identity`() {
        val src = ByteArray(6 * 4 * 3 / 2) { (it * 5 + 1).toByte() }
        assertArrayEquals(src, rotateNv21(rotateNv21(src, 6, 4, 90), 4, 6, 270))
    }

    @Test
    fun `mirroring twice is the identity`() {
        val src = ByteArray(8 * 4 * 3 / 2) { (it * 3 + 9).toByte() }
        assertArrayEquals(src, mirrorNv21(mirrorNv21(src, 8, 4), 8, 4))
    }

    @Test
    fun `mirror keeps chroma pairs together`() {
        assertArrayEquals(
            byteArrayOf(
                4, 3, 2, 1,
                8, 7, 6, 5,
                12, 13, 10, 11
            ),
            mirrorNv21(fixture, 4, 2)
        )
    }

    @Test
    fun `a non right angle is left alone`() {
        assertArrayEquals(fixture, rotateNv21(fixture, 4, 2, 45))
        assertArrayEquals(fixture, rotateNv21(fixture, 4, 2, 0))
    }

    // ---- AE target range ----

    /** What a typical phone advertises. */
    private val typical = listOf(15 to 15, 7 to 30, 15 to 30, 30 to 30, 30 to 60, 60 to 60)

    @Test
    fun `60 fps takes the range that cannot drop below 60`() {
        assertEquals(60 to 60, pickFpsRange(typical, 60))
    }

    @Test
    fun `30 fps prefers the fixed range over the variable one`() {
        assertEquals(30 to 30, pickFpsRange(typical, 30))
    }

    @Test
    fun `an unsupported rate falls back to the fastest range below it`() {
        assertEquals(30 to 30, pickFpsRange(typical, 45))
    }

    @Test
    fun `a rate below everything on offer takes the closest`() {
        assertEquals(15 to 15, pickFpsRange(listOf(15 to 15, 30 to 30), 5))
    }

    @Test
    fun `a device that lists nothing gets no request at all`() {
        assertEquals(null, pickFpsRange(emptyList(), 60))
    }
}
