//go:build linux

package system

import (
	"image"
	"testing"
)

// Capture and absolute motion are tested against the real X server, for the
// same reason injection is: GetImage's reply layout and XTEST's absolute mode
// are exactly the details a mock would get right by construction.

func TestCaptureScreenReadsWholeRoot(t *testing.T) {
	requireX11(t)
	frame, err := captureScreen(image.Rectangle{})
	if err != nil {
		t.Fatalf("capture: %v", err)
	}
	if frame.Width <= 0 || frame.Height <= 0 {
		t.Fatalf("empty frame %dx%d", frame.Width, frame.Height)
	}
	if want := frame.Stride * frame.Height; len(frame.Pix) < want {
		t.Fatalf("pixels: got %d bytes, want at least %d", len(frame.Pix), want)
	}

	// A second grab on the same dedicated connection must line up too: a
	// reply drained short would leave the stream desynchronised and show up
	// only here.
	again, err := captureScreen(image.Rectangle{})
	if err != nil {
		t.Fatalf("second capture: %v", err)
	}
	if again.Width != frame.Width || again.Height != frame.Height {
		t.Fatalf("size changed between grabs: %dx%d then %dx%d",
			frame.Width, frame.Height, again.Width, again.Height)
	}
}

func TestPointerToLandsOnFraction(t *testing.T) {
	conn := requireX11(t)
	startX, startY, err := conn.queryPointer()
	if err != nil {
		t.Fatalf("query: %v", err)
	}
	defer pointerAt(conn, int(startX), int(startY))

	w, h, err := desktopSize(conn)
	if err != nil {
		t.Fatalf("size: %v", err)
	}
	if err := pointerTo(0.25, 0.5); err != nil {
		t.Fatalf("pointerTo: %v", err)
	}
	x, y, err := conn.queryPointer()
	if err != nil {
		t.Fatalf("query: %v", err)
	}
	wantX, wantY := int(0.25*float64(w-1)), int(0.5*float64(h-1))
	if abs(int(x)-wantX) > 1 || abs(int(y)-wantY) > 1 {
		t.Fatalf("pointer at %d,%d, want %d,%d", x, y, wantX, wantY)
	}

	frame, err := captureScreen(image.Rectangle{})
	if err != nil {
		t.Fatalf("capture: %v", err)
	}
	if abs(frame.CursorX-wantX) > 1 || abs(frame.CursorY-wantY) > 1 {
		t.Fatalf("frame cursor at %d,%d, want %d,%d", frame.CursorX, frame.CursorY, wantX, wantY)
	}
}

func TestPressKeyRejectsUnknownNames(t *testing.T) {
	requireX11(t)
	if err := pressKey("f13"); err == nil {
		t.Fatal("an unlisted key was accepted")
	}
}

func pointerAt(conn *x11Conn, x, y int) {
	conn.mu.Lock()
	defer conn.mu.Unlock()
	conn.fakeInput(evMotionNotify, 0, int16(x), int16(y))
}

func abs(v int) int {
	if v < 0 {
		return -v
	}
	return v
}

func TestScreenDisplaysCoverTheDesktop(t *testing.T) {
	requireX11(t)
	displays, err := screenDisplays()
	if err != nil {
		t.Fatalf("displays: %v", err)
	}
	if len(displays) == 0 {
		t.Fatal("no displays")
	}
	whole, err := captureScreen(image.Rectangle{})
	if err != nil {
		t.Fatalf("capture: %v", err)
	}
	for _, d := range displays {
		if d.ID == "" || d.Width <= 0 || d.Height <= 0 {
			t.Fatalf("malformed display %+v", d)
		}
		if d.X < 0 || d.Y < 0 || d.X+d.Width > whole.Width || d.Y+d.Height > whole.Height {
			t.Fatalf("display %+v falls outside the %dx%d desktop", d, whole.Width, whole.Height)
		}
		t.Logf("display %+v", d)
	}

	// Capturing one display reads exactly its rectangle.
	d := displays[0]
	part, err := captureScreen(image.Rect(d.X, d.Y, d.X+d.Width, d.Y+d.Height))
	if err != nil {
		t.Fatalf("region capture: %v", err)
	}
	if part.Width != d.Width || part.Height != d.Height {
		t.Fatalf("region %dx%d, want %dx%d", part.Width, part.Height, d.Width, d.Height)
	}
}
