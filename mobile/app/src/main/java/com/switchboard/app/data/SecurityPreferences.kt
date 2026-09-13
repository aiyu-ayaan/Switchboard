package com.switchboard.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Preferences governing security and authentication checks before critical actions
 * such as locking the workstation.
 */
class SecurityPreferences private constructor(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("switchboard_security_prefs", Context.MODE_PRIVATE)

    private val _warnNoDeviceLock = MutableStateFlow(prefs.getBoolean(KEY_WARN_NO_DEVICE_LOCK, true))
    val warnNoDeviceLock: StateFlow<Boolean> = _warnNoDeviceLock.asStateFlow()

    private val _requireAuthToLock = MutableStateFlow(prefs.getBoolean(KEY_REQUIRE_AUTH_TO_LOCK, true))
    val requireAuthToLock: StateFlow<Boolean> = _requireAuthToLock.asStateFlow()

    fun setWarnNoDeviceLock(warn: Boolean) {
        prefs.edit().putBoolean(KEY_WARN_NO_DEVICE_LOCK, warn).apply()
        _warnNoDeviceLock.value = warn
    }

    fun setRequireAuthToLock(require: Boolean) {
        prefs.edit().putBoolean(KEY_REQUIRE_AUTH_TO_LOCK, require).apply()
        _requireAuthToLock.value = require
    }

    companion object {
        private const val KEY_WARN_NO_DEVICE_LOCK = "pref_warn_no_device_lock"
        private const val KEY_REQUIRE_AUTH_TO_LOCK = "pref_require_auth_to_lock"

        @Volatile
        private var instance: SecurityPreferences? = null

        fun get(context: Context): SecurityPreferences = instance ?: synchronized(this) {
            instance ?: SecurityPreferences(context.applicationContext).also { instance = it }
        }
    }
}
