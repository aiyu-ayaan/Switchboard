package com.switchboard.app.data

import android.content.Context
import android.content.SharedPreferences
import com.switchboard.app.transfer.RateUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [saveDirectory] is a Storage Access Framework tree URI whose permission was
 * persisted at pick time. It is empty until the user chooses one, and an
 * incoming file is refused rather than guessed at a location the user cannot
 * find.
 */
data class TransferConfig(
    val saveDirectory: String = "",
    val rateUnit: RateUnit = RateUnit.BYTES
)

class TransferPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("switchboard_transfer_prefs", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<TransferConfig> = _config.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_SAVE_DIR || key == KEY_RATE_UNIT) {
            _config.value = loadConfig()
        }
    }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    val saveDirectory: String
        get() = prefs.getString(KEY_SAVE_DIR, "").orEmpty().ifEmpty { _config.value.saveDirectory }

    val rateUnit: RateUnit
        get() = _config.value.rateUnit

    fun loadConfig(): TransferConfig {
        val unit = try {
            RateUnit.valueOf(prefs.getString(KEY_RATE_UNIT, RateUnit.BYTES.name) ?: RateUnit.BYTES.name)
        } catch (_: IllegalArgumentException) {
            RateUnit.BYTES
        }
        return TransferConfig(
            saveDirectory = prefs.getString(KEY_SAVE_DIR, "").orEmpty(),
            rateUnit = unit
        )
    }

    fun setSaveDirectory(treeUri: String) {
        prefs.edit().putString(KEY_SAVE_DIR, treeUri).commit()
        _config.value = _config.value.copy(saveDirectory = treeUri)
    }

    fun setRateUnit(unit: RateUnit) {
        prefs.edit().putString(KEY_RATE_UNIT, unit.name).commit()
        _config.value = _config.value.copy(rateUnit = unit)
    }

    companion object {
        private const val KEY_SAVE_DIR = "pref_save_directory"
        private const val KEY_RATE_UNIT = "pref_rate_unit"

        @Volatile
        private var instance: TransferPreferences? = null

        fun get(context: Context): TransferPreferences = instance ?: synchronized(this) {
            instance ?: TransferPreferences(context.applicationContext).also { instance = it }
        }
    }
}
