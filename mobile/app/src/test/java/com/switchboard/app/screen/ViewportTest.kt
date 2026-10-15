package com.switchboard.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ViewportTest {

    // A 16:9 desktop on a phone held sideways, slightly taller than 16:9.
    private val viewW = 2400f
    private val viewH = 1080f
    private val imageW = 1600f
    private val imageH = 900f

    @Test
    fun fitsAndCentresTheDesktop() {
        val f = Viewport().frame(viewW, viewH, imageW, imageH)
        assertEquals(1080f, f.height, 0.01f)
        assertEquals(1920f, f.width, 0.01f)
        assertEquals(240f, f.left, 0.01f)
        assertEquals(0f, f.top, 0.01f)
    }

    @Test
    fun mapsTapsToDesktopFractions() {
        val vp = Viewport()
        val centre = vp.toImage(1200f, 540f, viewW, viewH, imageW, imageH)
        assertNotNull(centre)
        assertEquals(0.5f, centre!!.first, 0.001f)
        assertEquals(0.5f, centre.second, 0.001f)

        val topLeft = vp.toImage(240f, 0f, viewW, viewH, imageW, imageH)!!
        assertEquals(0f, topLeft.first, 0.001f)
        assertEquals(0f, topLeft.second, 0.001f)
    }

    @Test
    fun ignoresTapsOnTheLetterbox() {
        assertNull(Viewport().toImage(100f, 540f, viewW, viewH, imageW, imageH))
        val clamped = Viewport().toImageClamped(100f, 540f, viewW, viewH, imageW, imageH)
        assertEquals(0f, clamped.first, 0.001f)
    }

    @Test
    fun zoomKeepsTheFocusPointUnderTheFingers() {
        val focusX = 900f
        val focusY = 300f
        val before = Viewport().toImage(focusX, focusY, viewW, viewH, imageW, imageH)!!
        val zoomed = Viewport().zoomedBy(2.5f, focusX, focusY, viewW, viewH, imageW, imageH)
        assertEquals(2.5f, zoomed.zoom, 0.001f)
        val after = zoomed.toImage(focusX, focusY, viewW, viewH, imageW, imageH)!!
        assertEquals(before.first, after.first, 0.002f)
        assertEquals(before.second, after.second, 0.002f)
    }

    @Test
    fun zoomIsBounded() {
        val out = Viewport().zoomedBy(0.2f, 0f, 0f, viewW, viewH, imageW, imageH)
        assertEquals(Viewport.MIN_ZOOM, out.zoom, 0.001f)
        val inn = Viewport().zoomedBy(100f, 0f, 0f, viewW, viewH, imageW, imageH)
        assertEquals(Viewport.MAX_ZOOM, inn.zoom, 0.001f)
    }

    @Test
    fun panningStopsAtTheDesktopEdge() {
        val zoomed = Viewport(zoom = 2f)
        val far = zoomed.pannedBy(100_000f, 100_000f, viewW, viewH, imageW, imageH)
        val f = far.frame(viewW, viewH, imageW, imageH)
        assertEquals(0f, f.left, 0.01f)
        assertEquals(0f, f.top, 0.01f)

        // Unzoomed, the picture is narrower than the view and cannot move.
        val still = Viewport().pannedBy(500f, 0f, viewW, viewH, imageW, imageH)
        assertEquals(0f, still.panX, 0.001f)
    }
}
