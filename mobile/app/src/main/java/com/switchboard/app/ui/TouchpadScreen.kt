package com.switchboard.app.ui

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mouse
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.SwipeVertical
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.switchboard.app.net.ButtonAction
import com.switchboard.app.net.MouseButton
import com.switchboard.app.net.ShellGesture
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * Everything the touchpad can ask the desktop to do.
 *
 * The recogniser resolves gestures on this device and calls these; no touch
 * detail reaches the wire. That is what keeps the desktop a dumb injector and
 * lets the feel of the pad be retuned without a protocol change.
 */
class TouchpadActions(
    val onMove: (dx: Double, dy: Double) -> Unit,
    val onButton: (button: String, action: String) -> Unit,
    val onScroll: (dx: Double, dy: Double, ctrl: Boolean) -> Unit,
    val onGesture: (name: String) -> Unit
)

/**
 * Feel of the pad, in Android pixels and milliseconds. None of it crosses the
 * wire: the desktop is told about notches and clicks, never about fingers.
 */
private object Pad {
    /** Base gain from finger travel to cursor travel. */
    const val SENSITIVITY = 1.35

    /**
     * Pointer acceleration. A phone-sized pad cannot cross a 4K desktop at 1:1
     * without several strokes, so a fast flick is amplified while a slow,
     * careful move is left alone — which is the only way fine positioning and
     * long journeys can share one small surface.
     */
    const val ACCEL_PER_PX = 0.055
    const val ACCEL_MAX = 3.0

    /** Movement below this is a tap, not a drag. */
    const val TAP_SLOP_PX = 14f
    const val TAP_TIMEOUT_MS = 220L
    const val DOUBLE_TAP_WINDOW_MS = 280L

    /** Hold a still finger this long to latch the button for a drag. */
    const val LONG_PRESS_MS = 420L

    /** Finger pixels per wheel notch. */
    const val SCROLL_PX_PER_NOTCH = 48.0

    /** Pinch spread change per zoom notch. */
    const val PINCH_PX_PER_NOTCH = 90.0

    /**
     * A two-finger stroke is either a scroll or a pinch, never both. Committing
     * once past this threshold stops a slightly uneven scroll from also zooming.
     */
    const val TWO_FINGER_DECIDE_PX = 18f

    /** Travel that turns a three-finger stroke into a shell swipe. */
    const val SWIPE_PX = 90f
}

@Composable
fun TouchpadScreen(actions: TouchpadActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        TouchpadSurface(
            actions = actions,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        )
        ButtonBar(actions)
        GestureLegend()
    }
}

/**
 * The pad itself.
 *
 * The recogniser reads the pointer event stream directly rather than composing
 * Compose's `detectTapGestures` / `detectTransformGestures` helpers, because
 * those cannot share one surface: each consumes the events the next needs, and
 * a touchpad has to tell a one-finger drag, a two-finger scroll, a pinch and a
 * three-finger swipe apart from the same initial contact.
 */
@Composable
private fun TouchpadSurface(actions: TouchpadActions, modifier: Modifier = Modifier) {
    val view = LocalView.current
    val current by rememberUpdatedState(actions)

    // Carried across gestures, which is what makes a double tap and a
    // tap-and-a-half possible: each is one gesture reinterpreted in the light
    // of the one just before it.
    val history = remember { TapHistory() }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture { recogniseGesture(view, history, current) }
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.28f)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Touchpad",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
    }
}

/** What the previous gesture left behind, for double-tap and tap-and-a-half. */
private class TapHistory {
    var lastTapEndedAt = 0L
}

private enum class TwoFinger { UNDECIDED, SCROLL, PINCH }

/**
 * Runs one gesture, from first contact to last release.
 *
 * The finger count is tracked as a high-water mark rather than a current
 * value: fingers rarely land or lift together, and a gesture that reclassified
 * itself halfway through would fire a stray click every time a second finger
 * arrived a frame late.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.recogniseGesture(
    view: View,
    history: TapHistory,
    actions: TouchpadActions
) {
    val first = awaitFirstDown(requireUnconsumed = false)
    first.consume()

    val startedAt = System.currentTimeMillis()

    // A gesture opening inside the double-tap window continues the tap before
    // it: released quickly it is a double click, held or dragged instead it is
    // a tap-and-a-half, which is how a touchpad drags with one finger.
    val continuesTap = startedAt - history.lastTapEndedAt <= Pad.DOUBLE_TAP_WINDOW_MS

    var maxPointers = 1
    var travel = 0f
    var buttonHeld = false

    var mode = TwoFinger.UNDECIDED
    var lastSpread = -1f
    var spreadBase = 0f
    var twoTravel = 0f
    var scrollX = 0.0
    var scrollY = 0.0
    var zoom = 0.0

    var swipeX = 0f
    var swipeY = 0f

    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        if (pressed.isEmpty()) break

        maxPointers = maxOf(maxPointers, pressed.size)
        val delta = centroidDelta(pressed)
        travel += delta.getDistance()
        pressed.forEach { it.consume() }

        when {
            maxPointers == 1 -> {
                val elapsed = System.currentTimeMillis() - startedAt
                // Two ways into a drag: the second half of a tap-and-a-half, or
                // a plain long press. Both latch the button so the finger can
                // then move freely.
                if (!buttonHeld &&
                    ((continuesTap && (travel > Pad.TAP_SLOP_PX || elapsed > Pad.TAP_TIMEOUT_MS)) ||
                        (travel <= Pad.TAP_SLOP_PX && elapsed > Pad.LONG_PRESS_MS))
                ) {
                    buttonHeld = true
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    actions.onButton(MouseButton.LEFT, ButtonAction.DOWN)
                }
                if (delta != Offset.Zero) {
                    val gain = accelerate(delta)
                    actions.onMove((delta.x * gain).toDouble(), (delta.y * gain).toDouble())
                }
            }

            maxPointers == 2 -> {
                val distance = spread(pressed)
                if (lastSpread < 0f) {
                    // First frame with both fingers down. It sets the baseline
                    // and nothing else: the jump from one centroid to two is
                    // not motion the user made.
                    lastSpread = distance
                    spreadBase = distance
                    twoTravel = 0f
                } else {
                    twoTravel += delta.getDistance()
                    if (mode == TwoFinger.UNDECIDED) {
                        mode = when {
                            abs(distance - spreadBase) > Pad.TWO_FINGER_DECIDE_PX -> TwoFinger.PINCH
                            twoTravel > Pad.TWO_FINGER_DECIDE_PX -> TwoFinger.SCROLL
                            else -> TwoFinger.UNDECIDED
                        }
                    }
                    when (mode) {
                        TwoFinger.SCROLL -> {
                            // Content follows the fingers, so dragging down
                            // scrolls the page down — a negative wheel on the
                            // wire. The desktop is never told which way the
                            // user prefers; that choice is made here.
                            scrollX -= delta.x / Pad.SCROLL_PX_PER_NOTCH
                            scrollY -= delta.y / Pad.SCROLL_PX_PER_NOTCH
                            val nx = whole(scrollX)
                            val ny = whole(scrollY)
                            if (nx != 0.0 || ny != 0.0) {
                                scrollX -= nx
                                scrollY -= ny
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                actions.onScroll(nx, ny, false)
                            }
                        }
                        TwoFinger.PINCH -> {
                            zoom += (distance - lastSpread) / Pad.PINCH_PX_PER_NOTCH
                            val notches = whole(zoom)
                            if (notches != 0.0) {
                                zoom -= notches
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                actions.onScroll(0.0, notches, true)
                            }
                        }
                        TwoFinger.UNDECIDED -> Unit
                    }
                    lastSpread = distance
                }
            }

            else -> {
                swipeX += delta.x
                swipeY += delta.y
            }
        }
    }

    // ---- Release ----

    if (buttonHeld) {
        actions.onButton(MouseButton.LEFT, ButtonAction.UP)
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        history.lastTapEndedAt = 0L
        return
    }

    val held = System.currentTimeMillis() - startedAt
    val wasTap = travel <= Pad.TAP_SLOP_PX * maxPointers && held <= Pad.TAP_TIMEOUT_MS

    if (maxPointers >= 3) {
        val swipe = shellSwipe(swipeX, swipeY)
        when {
            swipe != null -> {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                actions.onGesture(swipe)
            }
            wasTap -> {
                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                actions.onButton(MouseButton.MIDDLE, ButtonAction.CLICK)
            }
        }
        history.lastTapEndedAt = 0L
        return
    }

    if (!wasTap) {
        history.lastTapEndedAt = 0L
        return
    }

    when (maxPointers) {
        1 -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            if (continuesTap) {
                actions.onButton(MouseButton.LEFT, ButtonAction.DOUBLE)
                // Cleared, so a third tap opens a fresh pair instead of firing
                // a second double click.
                history.lastTapEndedAt = 0L
            } else {
                actions.onButton(MouseButton.LEFT, ButtonAction.CLICK)
                history.lastTapEndedAt = System.currentTimeMillis()
            }
        }
        2 -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
            actions.onButton(MouseButton.RIGHT, ButtonAction.CLICK)
            history.lastTapEndedAt = 0L
        }
    }
}

/** Averaged motion, so a second finger does not double the reported travel. */
internal fun centroidDelta(pressed: List<PointerInputChange>): Offset {
    if (pressed.isEmpty()) return Offset.Zero
    var sum = Offset.Zero
    pressed.forEach { sum += it.positionChange() }
    return sum / pressed.size.toFloat()
}

internal fun spread(pressed: List<PointerInputChange>): Float {
    if (pressed.size < 2) return 0f
    val a = pressed[0].position
    val b = pressed[1].position
    return hypot(a.x - b.x, a.y - b.y)
}

/**
 * Speed-dependent gain. Without it a phone-sized pad either cannot cross a
 * large desktop or cannot land on a checkbox; with it, both work.
 */
internal fun accelerate(delta: Offset): Double {
    val gain = 1.0 + delta.getDistance() * Pad.ACCEL_PER_PX
    return Pad.SENSITIVITY * min(gain, Pad.ACCEL_MAX)
}

/** The whole notches to send now, truncated toward zero so the sign survives. */
internal fun whole(value: Double): Double = value.toInt().toDouble()

/**
 * Classifies a three-finger stroke. Sideways swipes are inverted on purpose:
 * pushing the desktops left brings the one on the right into view, which is
 * what every trackpad does.
 */
internal fun shellSwipe(dx: Float, dy: Float): String? = when {
    abs(dy) > abs(dx) && dy < -Pad.SWIPE_PX -> ShellGesture.TASK_VIEW
    abs(dy) > abs(dx) && dy > Pad.SWIPE_PX -> ShellGesture.SHOW_DESKTOP
    abs(dx) > abs(dy) && dx < -Pad.SWIPE_PX -> ShellGesture.DESKTOP_RIGHT
    abs(dx) > abs(dy) && dx > Pad.SWIPE_PX -> ShellGesture.DESKTOP_LEFT
    else -> null
}

/**
 * Physical buttons below the pad.
 *
 * Redundant with tapping, deliberately: dragging while a button is held is far
 * easier with a thumb on a real button than with a tap-and-a-half, and a right
 * click that needs two fingers is awkward one-handed.
 */
@Composable
private fun ButtonBar(actions: TouchpadActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        PadButton("Left", MouseButton.LEFT, actions, Modifier.weight(2f))
        PadButton("Middle", MouseButton.MIDDLE, actions, Modifier.weight(1f))
        PadButton("Right", MouseButton.RIGHT, actions, Modifier.weight(2f))
    }
}

@Composable
private fun PadButton(
    label: String,
    button: String,
    actions: TouchpadActions,
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    Surface(
        modifier = modifier.fillMaxHeight(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    actions.onButton(button, ButtonAction.CLICK)
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun GestureLegend() {
    SectionCard {
        Text(
            "Gestures",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        LegendRow(Icons.Filled.TouchApp, "Tap to click · two fingers right · three middle")
        LegendRow(Icons.Filled.Mouse, "Double tap then drag, or hold, to move without a button")
        LegendRow(Icons.Filled.SwipeVertical, "Two fingers to scroll · pinch to zoom")
        LegendRow(Icons.Filled.OpenWith, "Three fingers: up for Task View, down for the desktop, sideways to switch")
    }
}

@Composable
private fun LegendRow(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
