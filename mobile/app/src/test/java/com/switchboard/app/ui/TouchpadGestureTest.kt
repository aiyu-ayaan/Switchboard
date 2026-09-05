package com.switchboard.app.ui

import androidx.compose.ui.geometry.Offset
import com.switchboard.app.net.ShellGesture
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure half of the touchpad recogniser. The event loop itself needs a real
 * pointer stream, but the arithmetic it leans on — how a stroke is classified,
 * how partial notches are held back, how fast the cursor is allowed to get —
 * is exactly where a wrong sign or a rounding slip produces a pad that scrolls
 * backwards or a cursor that will not creep.
 */
class TouchpadGestureTest {

    @Test
    fun `partial notches are held back until they complete`() {
        // A slow two-finger scroll accumulates well under one notch per frame.
        // Truncating each frame independently would report nothing at all.
        var acc = 0.0
        var sent = 0.0
        repeat(10) {
            acc += 0.3
            val n = whole(acc)
            acc -= n
            sent += n
        }
        // Sent plus carried must equal what the fingers asked for. The split
        // between them depends on float rounding, so 2 notches with a
        // remainder just under 1 is as correct as 3 with nothing left.
        assertEquals(3.0, sent + acc, 1e-9)
        assert(abs(acc) < 1.0) { "a whole notch was left unsent: $acc" }
    }

    @Test
    fun `notch truncation keeps the sign`() {
        assertEquals(-2.0, whole(-2.9), 0.0)
        assertEquals(2.0, whole(2.9), 0.0)
        assertEquals(0.0, whole(-0.9), 0.0)
    }

    @Test
    fun `three finger swipes map to shell gestures`() {
        assertEquals(ShellGesture.TASK_VIEW, shellSwipe(0f, -200f))
        assertEquals(ShellGesture.SHOW_DESKTOP, shellSwipe(0f, 200f))
        // Pushing the desktops left brings the one on the right into view.
        assertEquals(ShellGesture.DESKTOP_RIGHT, shellSwipe(-200f, 0f))
        assertEquals(ShellGesture.DESKTOP_LEFT, shellSwipe(200f, 0f))
    }

    @Test
    fun `a short three finger stroke is a tap, not a swipe`() {
        assertNull(shellSwipe(12f, -9f))
    }

    @Test
    fun `the dominant axis wins a diagonal swipe`() {
        // Otherwise a sloppy upward swipe would switch desktops instead of
        // opening Task View.
        assertEquals(ShellGesture.TASK_VIEW, shellSwipe(140f, -200f))
    }

    @Test
    fun `acceleration amplifies a flick without touching a careful move`() {
        val slow = accelerate(Offset(0.4f, 0f))
        val fast = accelerate(Offset(60f, 0f))
        assert(slow < 1.5) { "a careful move should stay near 1:1, was $slow" }
        assert(fast > slow * 1.5) { "a flick should be amplified, was $fast vs $slow" }
        // Capped, or a fast stroke throws the cursor off the far edge.
        assert(abs(accelerate(Offset(500f, 0f)) - accelerate(Offset(200f, 0f))) < 1e-6) {
            "acceleration must saturate"
        }
    }
}
