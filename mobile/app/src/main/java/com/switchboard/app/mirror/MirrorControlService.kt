package com.switchboard.app.mirror

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.switchboard.app.net.MirrorInput
import com.switchboard.app.net.MirrorKind
import com.switchboard.app.net.MirrorNav
import com.switchboard.app.net.RemoteKey
import kotlin.math.abs

/**
 * Performs the desktop's mouse and keyboard on this phone.
 *
 * An accessibility service is the only way an ordinary app can touch the
 * screen on the user's behalf: [dispatchGesture] injects real touches into
 * whatever is in front, and the node tree lets text be typed into the focused
 * field. It has to be switched on by the user in system settings, which is the
 * point -- nothing can grant it silently.
 *
 * It also reports which app is in front, so a desktop window showing one app
 * can tell when the phone has moved to another.
 */
class MirrorControlService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        MirrorController.get(this).onControlChanged()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        MirrorController.get(this).onControlChanged()
        super.onDestroy()
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // The keyboard and the system UI shade change window state too, but
        // they are not "the app in front".
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod")) return
        foreground = pkg
        MirrorController.get(this).onForeground(pkg)
    }

    /** Carries out one desktop action. */
    fun perform(input: MirrorInput) {
        when (input.kind) {
            MirrorKind.TAP -> input.points.firstOrNull()?.let { stroke(listOf(it.x to it.y), TAP_MS) }
            MirrorKind.LONG_PRESS -> input.points.firstOrNull()?.let { stroke(listOf(it.x to it.y), LONG_PRESS_MS) }
            MirrorKind.SWIPE -> if (input.points.isNotEmpty()) {
                stroke(input.points.map { it.x to it.y }, input.duration.coerceIn(MIN_SWIPE_MS, MAX_SWIPE_MS))
            }
            MirrorKind.SCROLL -> scroll(input)
            MirrorKind.TEXT -> input.text?.let { typeText(it) }
            MirrorKind.KEY -> input.key?.let { pressKey(it) }
            MirrorKind.NAV -> input.nav?.let { navigate(it) }
        }
    }

    // ---- Touch ----

    private fun screenSize(): Pair<Float, Float> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return metrics.widthPixels.toFloat() to metrics.heightPixels.toFloat()
    }

    /** One finger along [points] (fractions of the screen) over [durationMs]. */
    private fun stroke(points: List<Pair<Double, Double>>, durationMs: Long) {
        val (w, h) = screenSize()
        val path = Path()
        points.forEachIndexed { i, (x, y) ->
            val px = (x.coerceIn(0.0, 1.0) * (w - 1)).toFloat()
            val py = (y.coerceIn(0.0, 1.0) * (h - 1)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /**
     * A mouse wheel, as a touch screen understands one: a short drag. Positive
     * notches scroll up, which on a touch screen is a finger moving down. The
     * drag is slow enough not to fling, so one notch moves the content about
     * the same amount every time.
     */
    private fun scroll(input: MirrorInput) {
        val origin = input.points.firstOrNull()
        val x = origin?.x ?: 0.5
        val y = origin?.y ?: 0.5
        val dx = (-input.dx * NOTCH_FRACTION).coerceIn(-0.45, 0.45)
        val dy = (input.dy * NOTCH_FRACTION).coerceIn(-0.45, 0.45)
        if (abs(dx) < 1e-3 && abs(dy) < 1e-3) return
        // Kept on screen at both ends, or the gesture is rejected outright.
        val startX = x.coerceIn(0.05 + maxOf(0.0, -dx), 0.95 - maxOf(0.0, dx))
        val startY = y.coerceIn(0.05 + maxOf(0.0, -dy), 0.95 - maxOf(0.0, dy))
        stroke(listOf(startX to startY, startX + dx to startY + dy), SCROLL_MS)
    }

    // ---- Keyboard ----

    private fun focusedField(): AccessibilityNodeInfo? =
        rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }

    /** The field's own text, not its hint, with the cursor (or selection). */
    private fun fieldText(node: AccessibilityNodeInfo): Triple<String, Int, Int> {
        val showingHint = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && node.isShowingHintText
        val text = if (showingHint) "" else node.text?.toString().orEmpty()
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0 || start > text.length || end > text.length) {
            start = text.length
            end = text.length
        }
        return Triple(text, minOf(start, end), maxOf(start, end))
    }

    private fun setText(node: AccessibilityNodeInfo, text: String, cursor: Int) {
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        })
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
        })
    }

    /** Typed text; a lone newline is the Enter key, as on a keyboard. */
    private fun typeText(insert: String) {
        if (insert == "\n" || insert == "\r") {
            pressKey(RemoteKey.ENTER)
            return
        }
        val node = focusedField() ?: return
        insertAt(node, insert)
    }

    /** Inserts at the cursor, replacing a selection, as typing would. */
    private fun insertAt(node: AccessibilityNodeInfo, insert: String) {
        val (text, start, end) = fieldText(node)
        setText(node, text.substring(0, start) + insert + text.substring(end), start + insert.length)
    }

    private fun pressKey(key: String) {
        when (key) {
            RemoteKey.ESCAPE -> performGlobalAction(GLOBAL_ACTION_BACK)
            RemoteKey.ENTER -> {
                val node = focusedField() ?: return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    node.actionList.any { it.id == android.R.id.accessibilityActionImeEnter }
                ) {
                    node.performAction(android.R.id.accessibilityActionImeEnter)
                } else if (node.isMultiLine) {
                    insertAt(node, "\n")
                }
            }
            RemoteKey.BACKSPACE, RemoteKey.DELETE -> {
                val node = focusedField() ?: return
                val (text, start, end) = fieldText(node)
                when {
                    start != end -> setText(node, text.removeRange(start, end), start)
                    key == RemoteKey.BACKSPACE && start > 0 ->
                        setText(node, text.removeRange(start - 1, start), start - 1)
                    key == RemoteKey.DELETE && end < text.length ->
                        setText(node, text.removeRange(end, end + 1), end)
                }
            }
            RemoteKey.LEFT, RemoteKey.RIGHT, RemoteKey.HOME, RemoteKey.END -> {
                val node = focusedField() ?: return
                val (text, start, end) = fieldText(node)
                val cursor = when (key) {
                    RemoteKey.LEFT -> if (start != end) start else (start - 1).coerceAtLeast(0)
                    RemoteKey.RIGHT -> if (start != end) end else (end + 1).coerceAtMost(text.length)
                    RemoteKey.HOME -> 0
                    else -> text.length
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                })
            }
        }
    }

    // ---- System ----

    private fun navigate(action: String) {
        val global = when (action) {
            MirrorNav.BACK -> GLOBAL_ACTION_BACK
            MirrorNav.HOME -> GLOBAL_ACTION_HOME
            MirrorNav.RECENTS -> GLOBAL_ACTION_RECENTS
            MirrorNav.NOTIFICATIONS -> GLOBAL_ACTION_NOTIFICATIONS
            MirrorNav.QUICK_SETTINGS -> GLOBAL_ACTION_QUICK_SETTINGS
            MirrorNav.LOCK -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_LOCK_SCREEN else return
            MirrorNav.SCREENSHOT -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) GLOBAL_ACTION_TAKE_SCREENSHOT else return
            else -> return
        }
        performGlobalAction(global)
    }

    companion object {
        private const val TAP_MS = 40L
        private const val LONG_PRESS_MS = 650L
        private const val MIN_SWIPE_MS = 40L
        private const val MAX_SWIPE_MS = 5_000L
        private const val SCROLL_MS = 220L

        /** Screen fraction one wheel notch scrolls. */
        private const val NOTCH_FRACTION = 0.09

        @Volatile
        var instance: MirrorControlService? = null
            private set

        /** The package last seen in front, for the desktop's app windows. */
        @Volatile
        var foreground: String? = null
            private set
    }
}
