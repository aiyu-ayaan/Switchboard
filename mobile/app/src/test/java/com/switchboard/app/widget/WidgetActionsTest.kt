package com.switchboard.app.widget

import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.unit.dp
import org.junit.Test

class WidgetActionsTest {

    /**
     * A widget placed before this key existed, or one whose configuration was
     * never completed, has no stored selection. Rendering nothing there would
     * leave a row of desktop names with no controls at all.
     */
    @Test
    fun absentSelection_fallsBackToTheDefaults() {
        val chosen = SwitchboardWidget.selectedActionIds(emptyPreferences()).map { it.id }
        assertEquals(DEFAULT_WIDGET_ACTION_IDS, chosen)
    }

    /**
     * The selection is stored as a set, so the order it renders in has to come
     * from the catalogue -- otherwise two widgets with the same buttons could
     * lay them out differently.
     */
    @Test
    fun selection_rendersInCatalogueOrder() {
        val prefs = mutablePreferencesOf(
            SwitchboardWidget.ACTIONS_KEY to setOf("lock", "media_prev", "volume_up")
        )
        val chosen = SwitchboardWidget.selectedActionIds(prefs).map { it.id }
        assertEquals(listOf("media_prev", "volume_up", "lock"), chosen)
    }

    /** An unknown id is a control a later version removed; it must not crash. */
    @Test
    fun unknownIds_areDropped() {
        val prefs = mutablePreferencesOf(
            SwitchboardWidget.ACTIONS_KEY to setOf("lock", "teleport")
        )
        assertEquals(listOf("lock"), SwitchboardWidget.selectedActionIds(prefs).map { it.id })
    }

    /** An empty selection is a deliberate one: names only, no buttons. */
    @Test
    fun emptySelection_isHonoured() {
        val prefs = mutablePreferencesOf(SwitchboardWidget.ACTIONS_KEY to emptySet<String>())
        assertTrue(SwitchboardWidget.selectedActionIds(prefs).isEmpty())
    }

    @Test
    fun volumeSteps_stayInRange() {
        assertEquals(45, steppedVolume(50, -5))
        assertEquals(0, steppedVolume(2, -5))
        assertEquals(100, steppedVolume(98, 5))
    }

    /**
     * The button row wraps rather than shrinking below Material's 48dp target,
     * and the rows it wraps into are balanced -- six buttons in a width that
     * holds four become 3+3, not 4+2.
     */
    @Test
    fun buttonRows_wrapAndBalance() {
        // Wide enough for everything: one row.
        assertEquals(6, SwitchboardWidget.buttonsPerRow(320.dp, 6))
        // Holds four; six buttons split evenly rather than 4 + 2.
        assertEquals(3, SwitchboardWidget.buttonsPerRow(200.dp, 6))
        // Holds two; five buttons need three rows of at most two.
        assertEquals(2, SwitchboardWidget.buttonsPerRow(100.dp, 5))
        // Never zero, however cramped.
        assertEquals(1, SwitchboardWidget.buttonsPerRow(0.dp, 3))
        assertEquals(1, SwitchboardWidget.buttonsPerRow(200.dp, 0))
    }

    /** Defaults have to name real controls, or a fresh widget renders blanks. */
    @Test
    fun defaults_allExistInTheCatalogue() {
        DEFAULT_WIDGET_ACTION_IDS.forEach { id ->
            assertTrue("unknown default action: $id", widgetActionById(id) != null)
        }
    }
}
