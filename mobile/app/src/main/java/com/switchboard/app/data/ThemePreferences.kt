package com.switchboard.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode {
    SYSTEM,
    DARK,
    LIGHT
}

data class ThemeConfig(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false
)

class ThemePreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("switchboard_theme_prefs", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<ThemeConfig> = _config.asStateFlow()

    private fun loadConfig(): ThemeConfig {
        val modeStr = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
        val mode = try {
            ThemeMode.valueOf(modeStr)
        } catch (_: IllegalArgumentException) {
            ThemeMode.SYSTEM
        }
        val dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, false)
        return ThemeConfig(mode = mode, dynamicColor = dynamicColor)
    }

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _config.value = _config.value.copy(mode = mode)
    }

    fun setDynamicColor(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, enabled).apply()
        _config.value = _config.value.copy(dynamicColor = enabled)
    }

    companion object {
        private const val KEY_THEME_MODE = "pref_theme_mode"
        private const val KEY_DYNAMIC_COLOR = "pref_dynamic_color"
    }
}
