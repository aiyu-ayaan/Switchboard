package com.switchboard.app.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/**
 * The app's sense of touch.
 *
 * Material 3 gives haptics a vocabulary rather than one buzz: a light tick
 * acknowledges a tap, a toggle has a direction, and an outcome that succeeded
 * does not feel like one that was refused. This is that vocabulary, in the
 * platform's own constants.
 *
 * Compose's `HapticFeedbackType` covers the same ground, but only the newest
 * values map to the M3 set and the older ones silently collapse onto a long
 * press. `View.performHapticFeedback` is what Compose calls underneath anyway,
 * and going straight to it is what lets the fallbacks below be chosen rather
 * than inherited — this app runs as far back as API 26, where half the M3
 * constants do not exist yet.
 *
 * The user's own switch is checked here rather than at each call site, so
 * turning haptics off is one decision in one place rather than a condition
 * every future button has to remember.
 */
@Immutable
class Haptics(private val view: View?, private val enabled: Boolean) {

    private fun perform(constant: Int) {
        if (!enabled) return
        // The system setting still has the last word: performHapticFeedback
        // respects it, and respects a device with no motor to speak of.
        view?.performHapticFeedback(constant)
    }

    /** A tap landed on something that acts. The common case, deliberately light. */
    fun tap() = perform(HapticFeedbackConstants.CONTEXT_CLICK)

    /** A switch, checkbox or selection that now has a direction to report. */
    fun toggle(on: Boolean) = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (on) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.TOGGLE_OFF
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
    )

    /** Something finished and worked: paired, sent, connected. */
    fun confirm() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.CONFIRM
        } else {
            HapticFeedbackConstants.VIRTUAL_KEY
        }
    )

    /** Something was refused or failed, and should not feel like success. */
    fun reject() = perform(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
    )

    /** A press held long enough to mean something else. */
    fun longPress() = perform(HapticFeedbackConstants.LONG_PRESS)

    companion object {
        /**
         * What anything outside the app's own composition gets: a preview, a
         * screenshot test, a composable rendered before the provider is in
         * place. Silent rather than crashing.
         */
        val None = Haptics(view = null, enabled = false)
    }
}

/**
 * Silent by default, so a missing provider costs feedback and nothing else.
 * [com.switchboard.app.MainActivity] provides the real one around the whole app.
 */
val LocalHaptics = staticCompositionLocalOf { Haptics.None }

/** Binds the setting to the window the app is actually drawn in. */
@Composable
fun rememberHaptics(enabled: Boolean): Haptics {
    val view = LocalView.current
    return remember(view, enabled) { Haptics(view, enabled) }
}
