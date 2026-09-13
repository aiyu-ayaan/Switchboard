package com.switchboard.app.widget

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A widget is resized by hand, and the failure these thresholds exist to
 * prevent is silent: the bottom row simply gets sliced off, with nothing to
 * say a reading is missing. So the boundaries are pinned rather than left to
 * whatever the layout happens to do.
 */
class WidgetDensityTest {

    @Test
    fun density_stepsDownAsTheWidgetShrinks() {
        assertEquals(WidgetDensity.Comfortable, densityFor(300.dp))
        assertEquals(WidgetDensity.Comfortable, densityFor(210.dp))
        assertEquals(WidgetDensity.Compact, densityFor(209.dp))
        assertEquals(WidgetDensity.Compact, densityFor(140.dp))
        assertEquals(WidgetDensity.Tiny, densityFor(139.dp))
        assertEquals(WidgetDensity.Tiny, densityFor(0.dp))
    }

    /** The title bar is chrome; the secondary lines are data. Chrome goes last. */
    @Test
    fun density_dropsChromeOnlyWhenThereIsNoRoomLeft() {
        assertTrue(densityFor(300.dp).showsTitleBar)
        assertTrue(densityFor(300.dp).showsDetail)

        // Compact keeps its structure but loses the secondary lines.
        assertTrue(densityFor(180.dp).showsTitleBar)
        assertFalse(densityFor(180.dp).showsDetail)

        assertFalse(densityFor(100.dp).showsTitleBar)
        assertFalse(densityFor(100.dp).showsDetail)
    }

    /**
     * The stat grid has a fixed number of rows, so a second row that does not
     * fit must not be drawn at all -- half the readings shown properly beats
     * four with two of them cut in half.
     */
    @Test
    fun statGrid_dropsItsSecondRowBeforeClipping() {
        assertTrue(fitsTwoStatRows(210.dp))
        assertTrue(fitsTwoStatRows(150.dp))
        assertFalse(fitsTwoStatRows(149.dp))
    }

    @Test
    fun statFooter_needsMoreRoomThanTheSecondRow() {
        assertTrue(fitsStatFooter(190.dp))
        assertFalse(fitsStatFooter(189.dp))
        // The cards matter more than the age stamp, so the footer must give up
        // first as the widget shrinks.
        assertTrue(fitsStatFooter(200.dp) && fitsTwoStatRows(200.dp))
        assertTrue(!fitsStatFooter(160.dp) && fitsTwoStatRows(160.dp))
    }

    /**
     * The host cards scroll, so two lines that do not quite fit push content
     * down rather than off the edge; the fixed stat grid has no such escape.
     * The looser threshold is the point, so it is asserted rather than assumed.
     */
    @Test
    fun hostDetail_isLooserThanTheStatGrid() {
        assertTrue(fitsHostDetail(185.dp))
        assertFalse(fitsHostDetail(184.dp))
        assertTrue(fitsHostDetail(190.dp))
        assertFalse(densityFor(190.dp).showsDetail)
    }

    @Test
    fun statusPill_shortensOnANarrowWidget() {
        assertTrue(fitsLongStatus(250.dp))
        assertFalse(fitsLongStatus(249.dp))
    }
}
