package system

import (
	"image"
	"math"

	"switchboard/backend/internal/protocol"
)

// ScreenFrame is one grab of the host's whole desktop.
//
// Pixels are 32-bit BGRX, top row first, which is what both X11's ZPixmap at
// depth 24 and a Windows 32-bit DIB section produce natively. Keeping the
// platforms' own layout means neither backend runs a per-pixel conversion; the
// one pass that has to touch every pixel anyway -- the downscale before
// encoding -- does the channel swap for free.
type ScreenFrame struct {
	Width, Height int
	Stride        int
	Pix           []byte

	// DesktopWidth and DesktopHeight are the whole desktop's size, which a
	// region capture needs reported beside it: a touch on one monitor's
	// picture becomes a pointer position on the desktop as a whole.
	DesktopWidth, DesktopHeight int

	// Cursor is the pointer position in frame pixels, or -1 when it is off the
	// captured area. Neither capture path includes the cursor in the pixels,
	// so it is reported beside them for the viewer to draw.
	CursorX, CursorY int
}

// ScreenSupported reports whether this host can capture its desktop.
func (c *Controller) ScreenSupported() bool { return screenSupported() }

// CaptureScreen grabs part of the desktop: region, in desktop pixels, or the
// whole of it -- every monitor as one picture -- when region is empty. A
// region is clipped to the desktop, and only it is read, so sharing one
// monitor of three costs a third of the capture.
func (c *Controller) CaptureScreen(region image.Rectangle) (*ScreenFrame, error) {
	return captureScreen(region)
}

// ScreenDisplays lists the monitors, in desktop pixels. A host that cannot
// tell its monitors apart reports the whole desktop as one.
func (c *Controller) ScreenDisplays() ([]protocol.ScreenDisplay, error) { return screenDisplays() }

// PointerTo moves the pointer to an absolute position given as fractions of
// the captured desktop, so a tap on the phone's picture of the screen lands on
// the same spot whatever size that picture was drawn at.
func (c *Controller) PointerTo(x, y float64) error {
	if math.IsNaN(x) || math.IsNaN(y) {
		return nil
	}
	return pointerTo(math.Max(0, math.Min(1, x)), math.Max(0, math.Min(1, y)))
}

// PressKey taps one named editing key. The names are a fixed table (see
// protocol.Key*), for the same reason the shell gesture table is: a phone
// typing into a remote screen needs Backspace and Enter, and nothing about
// that should turn the channel into a general keyboard.
func (c *Controller) PressKey(name string) error { return pressKey(name) }
