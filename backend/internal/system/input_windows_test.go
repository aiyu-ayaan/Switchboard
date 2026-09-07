//go:build windows

package system

import (
	"math"
	"testing"

	"switchboard/backend/internal/protocol"
)

// The accumulator is the whole reason a slow drag works. Truncating each frame
// on its own would swallow every delta below one pixel, so this pins that the
// remainder survives across frames and that nothing is invented or lost.
func TestResidualAccumulatesSubPixelMotion(t *testing.T) {
	var acc float64
	total := int32(0)
	for i := 0; i < 100; i++ {
		total += take(&acc, 0.3)
	}
	// Sent plus carried must equal what was asked for: nothing invented, and
	// nothing dropped. The exact split depends on float rounding, so 29 with a
	// remainder just under 1 is as correct as 30 with nothing left.
	if delivered := float64(total) + acc; math.Abs(delivered-30) > 1e-9 {
		t.Fatalf("100 frames of 0.3px delivered %v px, want 30", delivered)
	}
	if math.Abs(acc) >= 1 {
		t.Fatalf("carried remainder %v is a whole pixel or more", acc)
	}
}

func TestResidualPreservesSign(t *testing.T) {
	var acc float64
	if got := take(&acc, -2.7); got != -2 {
		t.Fatalf("take(-2.7) = %d, want -2 (truncate toward zero)", got)
	}
	if got := take(&acc, -0.4); got != -1 {
		t.Fatalf("the carried -0.7 plus -0.4 should complete a pixel, got %d", got)
	}
}

func TestUnknownButtonAndGestureAreRefused(t *testing.T) {
	if err := mouseButton("thumb", protocol.ButtonClick); err == nil {
		t.Fatal("an unknown button was accepted")
	}
	if err := mouseButton(protocol.ButtonLeft, "wiggle"); err == nil {
		t.Fatal("an unknown button action was accepted")
	}
	// The gesture table is the entire keyboard surface the air mouse exposes;
	// anything outside it must not reach SendInput.
	if err := shellGesture("formatDisk"); err == nil {
		t.Fatal("an unnamed gesture was accepted")
	}
}

// Live injection against the real machine. The moves cancel out, so the cursor
// ends where it started and the test costs the user nothing.
func TestSendInputAcceptsMotion(t *testing.T) {
	if err := moveMouse(12, 12); err != nil {
		t.Skipf("SendInput not permitted in current session: %v", err)
	}
	if err := moveMouse(-12, -12); err != nil {
		t.Fatalf("move back: %v", err)
	}
}
