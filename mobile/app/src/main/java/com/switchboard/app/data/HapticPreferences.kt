package com.switchboard.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app answers a touch with a buzz.
 *
 * On by default: a remote control for a machine across the room is used
 * half-looked-at, and the tick is often the only confirmation that a tap landed
 * on the key rather than the gap beside it. Off is for the user who wants their
 * phone silent in every sense — the app still works identically, it just says
 * nothing with the motor.
 *
 * A singleton like [TransferPreferences], because the setting is read from
 * composition on every tap and re-reading `SharedPreferences` per screen would
 * pay disk for an answer that never differs.
 */
class HapticPreferences private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("switchboard_haptic_prefs", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        _enabled.value = enabled
    }

    companion object {
        private const val KEY_ENABLED = "pref_haptics_enabled"

        @Volatile
        private var instance: HapticPreferences? = null

        fun get(context: Context): HapticPreferences = instance ?: synchronized(this) {
            instance ?: HapticPreferences(context.applicationContext).also { instance = it }
        }
    }
}
