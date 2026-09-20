//go:build linux

package system

import (
	"errors"
	"testing"
	"time"

	"switchboard/backend/internal/protocol"
)

// Injection is tested against the real X server, because the failure mode that
// matters cannot be reproduced any other way: XTestFakeInput expects no reply,
// so a request the server rejects -- a wrong opcode, a malformed length, a
// keycode outside the range -- produces no error at the call site and simply
// moves nothing. The only honest check is to move the pointer and read back
// where it went.

func requireX11(t *testing.T) *x11Conn {
	t.Helper()
	conn, err := x11Client()
	if err != nil {
		if errors.Is(err, errNoDisplay) {
			t.Skip("no X display")
		}
		t.Skipf("X server unreachable: %v", err)
	}
	return conn
}

func TestX11HandshakeDiscoversXTest(t *testing.T) {
	conn := requireX11(t)

	if conn.xtestOpcode == 0 {
		t.Error("XTEST major opcode is zero")
	}
	// The keycode range is the field whose offset in the setup reply is
	// easiest to get wrong, and getting it wrong makes every keysym lookup
	// silently miss. X reserves keycodes 8-255.
	if conn.minKeycode < 8 {
		t.Errorf("min keycode = %d, below the protocol's floor of 8", conn.minKeycode)
	}
	if conn.maxKeycode <= conn.minKeycode {
		t.Errorf("keycode range %d-%d is empty", conn.minKeycode, conn.maxKeycode)
	}
	if conn.keysymsPerCode == 0 {
		t.Error("keysyms per keycode is zero")
	}
	if conn.root == 0 {
		t.Error("root window is zero; the screen list was not parsed")
	}
	t.Logf("XTEST opcode %d, keycodes %d-%d, %d keysyms each, spare %d, root %#x",
		conn.xtestOpcode, conn.minKeycode, conn.maxKeycode, conn.keysymsPerCode, conn.spareKeycode, conn.root)
}

// TestGestureKeysymsResolve checks that every chord in the table can actually
// be typed on this layout. A gesture naming a keysym with no keycode is a
// button on the phone that reports an error when pressed.
func TestGestureKeysymsResolve(t *testing.T) {
	conn := requireX11(t)

	for name, keysyms := range gestureChords {
		for _, keysym := range keysyms {
			if _, ok := conn.keycodeFor(keysym); !ok {
				t.Errorf("gesture %q needs keysym %#x, which this layout has no keycode for", name, keysym)
			}
		}
	}
	// Shift and Control are needed by the text and zoom-scroll paths.
	for _, keysym := range []uint32{keysymShiftL, keysymControlL} {
		if _, ok := conn.keycodeFor(keysym); !ok {
			t.Errorf("keysym %#x has no keycode", keysym)
		}
	}
}

// TestMoveMouseActuallyMoves is the test this whole file exists for. It nudges
// the pointer, confirms it landed, and puts it back where the user left it.
func TestMoveMouseActuallyMoves(t *testing.T) {
	conn := requireX11(t)

	startX, startY, err := conn.queryPointer()
	if err != nil {
		t.Fatalf("queryPointer: %v", err)
	}
	t.Cleanup(func() {
		now, _, _ := conn.queryPointer()
		_ = conn.moveRelative(startX-now, 0)
	})

	// Away from any screen edge, so the move cannot be clamped and read as a
	// failure. A negative nudge from the right half, positive from the left.
	const nudge = 40
	delta := int16(nudge)
	if startX > 200 {
		delta = -nudge
	}

	if err := moveMouse(float64(delta), 0); err != nil {
		t.Fatalf("moveMouse: %v", err)
	}
	// XTestFakeInput is fire-and-forget; queryPointer's round trip is what
	// guarantees the server has processed it by the time it answers.
	gotX, gotY, err := conn.queryPointer()
	if err != nil {
		t.Fatalf("queryPointer after move: %v", err)
	}
	if gotX != startX+delta {
		t.Errorf("pointer x = %d after a %d nudge from %d, want %d", gotX, delta, startX, startX+delta)
	}
	if gotY != startY {
		t.Errorf("pointer y moved from %d to %d on a horizontal nudge", startY, gotY)
	}

	if err := moveMouse(float64(-delta), 0); err != nil {
		t.Fatalf("moveMouse back: %v", err)
	}
	if backX, _, err := conn.queryPointer(); err == nil && backX != startX {
		t.Errorf("pointer did not return to %d, ended at %d", startX, backX)
	}
}

// TestMoveMouseAccumulatesSubPixelDeltas covers the residual carry. A phone
// sending a slow drag at 60 Hz delivers deltas well under one pixel, and
// truncating each independently would make the cursor refuse to creep at all.
func TestMoveMouseAccumulatesSubPixelDeltas(t *testing.T) {
	conn := requireX11(t)

	residual.Lock()
	residual.moveX, residual.moveY = 0, 0
	residual.Unlock()

	startX, _, err := conn.queryPointer()
	if err != nil {
		t.Fatalf("queryPointer: %v", err)
	}
	t.Cleanup(func() {
		now, _, _ := conn.queryPointer()
		_ = conn.moveRelative(startX-now, 0)
	})

	// Ten frames of a fifth of a pixel is two pixels of real movement.
	for range 10 {
		if err := moveMouse(0.2, 0); err != nil {
			t.Fatalf("moveMouse: %v", err)
		}
	}
	got, _, err := conn.queryPointer()
	if err != nil {
		t.Fatalf("queryPointer: %v", err)
	}
	if got == startX {
		t.Error("ten sub-pixel frames moved the pointer nowhere: the residual is being truncated per frame")
	}
	if moved := got - startX; moved != 2 {
		t.Errorf("pointer moved %d px, want 2 from 10 frames of 0.2", moved)
	}
}

// TestMouseButtonRejectsUnknownInput pins the guard that keeps the air mouse
// from becoming a general injection channel for a malformed frame.
func TestMouseButtonRejectsUnknownInput(t *testing.T) {
	requireX11(t)

	if err := mouseButton("thumb", protocol.ButtonClick); err == nil {
		t.Error("an unknown button was accepted")
	}
	if err := mouseButton(protocol.ButtonLeft, "wiggle"); err == nil {
		t.Error("an unknown button action was accepted")
	}
	if err := shellGesture("launchNukes"); err == nil {
		t.Error("an unknown gesture was accepted")
	}
}

// TestRuneKeysym covers the rune-to-keysym mapping, which decides whether a
// character goes through the fast layout path or the borrowed keycode.
func TestRuneKeysym(t *testing.T) {
	cases := []struct {
		name string
		r    rune
		want uint32
	}{
		{"ascii letter is its own keysym", 'a', 0x61},
		{"space", ' ', 0x20},
		{"latin-1 is the identity mapping", 'é', 0xe9},
		{"beyond latin-1 uses the unicode range", '→', 0x01002192},
		{"emoji", '🙂', 0x0101F642},
		{"newline becomes return", '\n', keysymReturn},
		{"carriage return becomes return", '\r', keysymReturn},
		{"tab", '\t', keysymTab},
		{"other control characters are dropped", '\x07', 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := runeKeysym(tc.r); got != tc.want {
				t.Errorf("runeKeysym(%q) = %#x, want %#x", tc.r, got, tc.want)
			}
		})
	}
}

// TestInputTextEmptyIsANoOp covers the case the wire protocol tests send: an
// empty string must succeed without touching the server, not fail as an
// unsupported control.
func TestInputTextEmptyIsANoOp(t *testing.T) {
	requireX11(t)

	if err := inputText(""); err != nil {
		t.Errorf("inputText(\"\") = %v, want nil", err)
	}
}

// TestScrollRejectsRunawayFrames pins the notch cap. X models the wheel as
// button clicks, so a wildly scaled frame would otherwise have the daemon
// spend real time sending thousands of them.
func TestScrollRejectsRunawayFrames(t *testing.T) {
	requireX11(t)

	residual.Lock()
	residual.scrollX, residual.scrollY = 0, 0
	residual.Unlock()

	start := time.Now()
	if err := scrollMouse(0, 100000, false); err != nil {
		t.Fatalf("scrollMouse: %v", err)
	}
	if elapsed := time.Since(start); elapsed > 500*time.Millisecond {
		t.Errorf("a runaway scroll frame took %v; the notch cap is not holding", elapsed)
	}
}

// TestInputCapability pins the wiring: on a host with a display, State must
// advertise the air mouse, because the phone hides the trackpad screen
// entirely when the capability is missing.
func TestInputCapability(t *testing.T) {
	requireX11(t)

	c := NewController()
	defer c.Close()

	have := map[string]bool{}
	for _, cap := range c.State("test-daemon").Capabilities {
		have[cap] = true
	}
	for _, want := range []string{"input", "keyboard"} {
		if !have[want] {
			t.Errorf("capability %q missing", want)
		}
	}
}

// TestSetKeycodeRoundTrips verifies the ChangeKeyboardMapping encoding against
// the server. It is the one request in the injection path with no reply to
// check and no visible effect of its own: a malformed one is answered with a
// protocol error raised against whatever is asked next, so without reading the
// mapping back a wrong length or count would look like a character that simply
// failed to type.
func TestSetKeycodeRoundTrips(t *testing.T) {
	conn := requireX11(t)
	if conn.spareKeycode == 0 {
		t.Skip("this layout uses every keycode; nothing is free to borrow")
	}

	const rightArrow = 0x01002192 // '→', which no PC layout has a key for
	restore := func() {
		conn.mu.Lock()
		_ = conn.setKeycode(conn.spareKeycode, 0)
		conn.mu.Unlock()
		_ = conn.flushErrors()
	}
	t.Cleanup(restore)

	conn.mu.Lock()
	err := conn.setKeycode(conn.spareKeycode, rightArrow)
	conn.mu.Unlock()
	if err != nil {
		t.Fatalf("setKeycode: %v", err)
	}
	time.Sleep(remapSettleTime)

	syms, err := conn.readKeycodeSyms(conn.spareKeycode)
	if err != nil {
		t.Fatalf("reading the remapped keycode: %v", err)
	}
	if len(syms) == 0 {
		t.Fatal("the remapped keycode reports no keysyms")
	}
	t.Logf("remapped keycode %d reads back as %#x", conn.spareKeycode, syms)

	// Only the first group matters: those are the slots reached with no
	// modifier and with Shift, which is how an injected key is pressed. XKB
	// canonicalises the rest of the row, so asserting on every slot would be
	// asserting on the server's normalisation rather than on this encoding.
	if syms[0] != rightArrow {
		t.Errorf("unshifted slot = %#x, want %#x", syms[0], rightArrow)
	}
	if len(syms) > 1 && syms[1] != rightArrow {
		t.Errorf("shifted slot = %#x, want %#x: the character would vanish when typed with Shift held", syms[1], rightArrow)
	}

	restore()
	time.Sleep(remapSettleTime)
	syms, err = conn.readKeycodeSyms(conn.spareKeycode)
	if err != nil {
		t.Fatalf("reading the restored keycode: %v", err)
	}
	for slot, sym := range syms {
		if sym != 0 {
			t.Errorf("slot %d = %#x after restore, want NoSymbol: a live keysym left on a borrowed keycode changes what the user's own keyboard does", slot, sym)
		}
	}
}

// TestInjectionSurvivesAMappingChange is the regression test for the desync
// that wedged this backend outright.
//
// X multiplexes replies, errors and events onto one stream, and MappingNotify
// is sent to every client whether it asked for events or not. Changing a
// keycode to type an unmapped character therefore puts an event in front of
// the next reply. Reading it as one makes the connection block on a length
// taken from an event body -- and because that connection carries every
// pointer event too, the air mouse stops dead at the first typed emoji and
// never recovers.
func TestInjectionSurvivesAMappingChange(t *testing.T) {
	conn := requireX11(t)
	if conn.spareKeycode == 0 {
		t.Skip("this layout uses every keycode; nothing is free to borrow")
	}

	startX, _, err := conn.queryPointer()
	if err != nil {
		t.Fatalf("queryPointer: %v", err)
	}
	t.Cleanup(func() {
		now, _, _ := conn.queryPointer()
		_ = conn.moveRelative(startX-now, 0)
	})

	// Three remaps, so the stream carries several announcements rather than
	// one that might be swallowed by luck.
	for range 3 {
		conn.mu.Lock()
		err := conn.setKeycode(conn.spareKeycode, 0x01002192)
		conn.mu.Unlock()
		if err != nil {
			t.Fatalf("setKeycode: %v", err)
		}
		conn.mu.Lock()
		err = conn.setKeycode(conn.spareKeycode, 0)
		conn.mu.Unlock()
		if err != nil {
			t.Fatalf("restoring the keycode: %v", err)
		}
	}

	// The pointer must still move, and the reply must still come back. Before
	// events were skipped this call never returned.
	delta := int16(20)
	if startX > 200 {
		delta = -20
	}
	if err := moveMouse(float64(delta), 0); err != nil {
		t.Fatalf("moveMouse after a mapping change: %v", err)
	}
	got, _, err := conn.queryPointer()
	if err != nil {
		t.Fatalf("queryPointer after a mapping change: %v", err)
	}
	if got != startX+delta {
		t.Errorf("pointer x = %d, want %d: injection stopped landing after the mapping change", got, startX+delta)
	}
}
