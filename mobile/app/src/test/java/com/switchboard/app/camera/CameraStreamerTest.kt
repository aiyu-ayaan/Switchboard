package com.switchboard.app.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.switchboard.app.net.CameraQuality
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

/**
 * Auto framing's geometry. It used to be a switch wired to nothing; these pin
 * the properties that make the crop usable rather than merely present — the
 * aspect ratio the sensor demands, and staying inside the sensor at all.
 */
class AutoFramingTest {

    private val sensor = Box(0, 0, 4000, 3000)

    private fun assertSensorAspect(crop: Box) {
        val sensorAspect = sensor.width.toDouble() / sensor.height
        val cropAspect = crop.width.toDouble() / crop.height
        assertEquals(sensorAspect, cropAspect, 0.02)
    }

    @Test
    fun `no faces means no crop, so the last framing is held`() {
        assertNull(subjectCrop(emptyList(), sensor))
    }

    // A crop of a different shape is not letterboxed by the camera: it is
    // cropped again to fit the output, which reframes somewhere other than
    // where it was asked to.
    @Test
    fun `the crop keeps the sensor's aspect ratio`() {
        assertSensorAspect(subjectCrop(listOf(Box(1900, 1400, 2100, 1600)), sensor)!!)
    }

    @Test
    fun `a centred face is framed around its centre`() {
        val crop = subjectCrop(listOf(Box(1900, 1400, 2100, 1600)), sensor)!!
        assertEquals(sensor.centreX, crop.centreX, 20f)
        assertEquals(sensor.centreY, crop.centreY, 20f)
    }

    // A subject at the edge should end up off-centre in a crop that still fits,
    // not pushed outside the sensor.
    @Test
    fun `a face at the edge slides the crop instead of leaving the sensor`() {
        val crop = subjectCrop(listOf(Box(0, 0, 200, 200)), sensor)!!
        assertTrue("crop starts before the sensor: $crop", crop.left >= sensor.left)
        assertTrue("crop starts above the sensor: $crop", crop.top >= sensor.top)
        assertTrue("crop overruns the sensor: $crop", crop.right <= sensor.right)
        assertTrue("crop overruns the sensor: $crop", crop.bottom <= sensor.bottom)
        assertSensorAspect(crop)
    }

    @Test
    fun `several faces are all inside the crop`() {
        val faces = listOf(Box(400, 1200, 600, 1400), Box(3200, 1500, 3400, 1700))
        val crop = subjectCrop(faces, sensor)!!
        faces.forEach {
            assertTrue(
                "face $it outside crop $crop",
                it.left >= crop.left && it.right <= crop.right &&
                    it.top >= crop.top && it.bottom <= crop.bottom
            )
        }
    }

    // A face large enough to demand more than the sensor must not produce a
    // crop region the camera would reject.
    @Test
    fun `a face filling the frame cannot ask for more than the sensor`() {
        val crop = subjectCrop(listOf(Box(200, 200, 3800, 2800)), sensor)!!
        assertEquals(sensor, crop)
    }

    @Test
    fun `easing moves toward the target without arriving in one step`() {
        val from = Box(0, 0, 1000, 750)
        val to = Box(1000, 750, 2000, 1500)
        val eased = ease(from, to)
        assertTrue("no movement: $eased", eased.left > from.left)
        assertTrue("overshot: $eased", eased.left < to.left)
    }

    @Test
    fun `a jitter below the deadband is not worth a capture request`() {
        val from = Box(1000, 750, 3000, 2250)
        assertFalse(movedEnough(from, from.copy(left = from.left + 4), sensor))
        assertTrue(movedEnough(from, from.copy(left = from.left + 400), sensor))
    }
}

/**
 * The bitrate handed to the hardware encoder, which is the whole of the
 * bandwidth story now that the video track carries the picture.
 */
class VideoBitrateTest {

    @Test
    fun `scales with pixels, rate and preset`() {
        val balanced1080p30 = videoBitrate(1920, 1080, 30, CameraQuality.BALANCED)
        assertTrue(balanced1080p30 > videoBitrate(1280, 720, 30, CameraQuality.BALANCED))
        assertTrue(balanced1080p30 < videoBitrate(1920, 1080, 60, CameraQuality.BALANCED))
        assertTrue(balanced1080p30 < videoBitrate(1920, 1080, 30, CameraQuality.FULL))
    }

    @Test
    fun `stays inside the link's means at both ends`() {
        // A postage stamp still needs enough bits not to turn to blocks...
        assertEquals(1_000_000, videoBitrate(160, 120, 5, CameraQuality.LOW))
        // ...and no preset may ask for more than the Wi-Fi link can carry
        // alongside everything else on the session.
        assertEquals(24_000_000, videoBitrate(3840, 2160, 60, CameraQuality.FULL))
    }
}
