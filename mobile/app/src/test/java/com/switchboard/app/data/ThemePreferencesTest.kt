package com.switchboard.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThemePreferencesTest {
    @Test
    fun defaultConfig_isSystemAndNoDynamicColor() {
        val config = ThemeConfig(mode = ThemeMode.SYSTEM, dynamicColor = false)
        assertEquals(ThemeMode.SYSTEM, config.mode)
        assertFalse(config.dynamicColor)
    }

    @Test
    fun themeMode_enumValues_matchExpected() {
        val names = ThemeMode.entries.map { it.name }
        assertEquals(listOf("SYSTEM", "DARK", "LIGHT"), names)
    }
}
