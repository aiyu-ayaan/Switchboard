//go:build linux

package system

import (
	"encoding/binary"
	"errors"
	"fmt"
	"sync"
	"time"

	"switchboard/backend/internal/protocol"
)

// Linux screen capture, through the core protocol's GetImage on the root
// window. It is the oldest way to read an X screen and still the only one that
// needs no extension: MIT-SHM would save one copy, but it needs a shared
// memory segment, which is a cgo call this daemon cannot make.
//
// The cost is one copy of the frame over the X socket -- about 8 MB for a
// 1080p desktop, a few milliseconds on a local Unix socket -- which is small
// beside the JPEG encode that follows it.
//
// Like the air mouse, this reaches what X can see. Under Wayland that is only
// Xwayland windows; the compositor's own surfaces come back black, and reading
// them needs the xdg-desktop-portal ScreenCast flow and PipeWire instead.

const (
	opGetGeometry = 14
	opGetImage    = 73
	zPixmap       = 2
)

// capture is the dedicated connection screen grabs use. It is redialled after
// an error, because a stream that failed mid-reply has lost its framing and
// every later request on it would read garbage.
var capture struct {
	sync.Mutex
	conn *x11Conn
}

// rootSize caches the desktop size for absolute pointer moves, which arrive
// far more often than the size changes and must not each cost a round trip.
var rootSize struct {
	sync.Mutex
	width, height int
	at            time.Time
}

func captureConn() (*x11Conn, error) {
	if capture.conn != nil {
		return capture.conn, nil
	}
	c, err := dialX11Bare()
	if err != nil {
		return nil, err
	}
	capture.conn = c
	return c, nil
}

func screenSupported() bool {
	capture.Lock()
	defer capture.Unlock()
	_, err := captureConn()
	return err == nil
}

func captureScreen() (*ScreenFrame, error) {
	capture.Lock()
	defer capture.Unlock()

	c, err := captureConn()
	if err != nil {
		return nil, err
	}
	frame, err := c.grabRoot()
	if err != nil {
		c.conn.Close()
		capture.conn = nil
		return nil, err
	}

	rootSize.Lock()
	rootSize.width, rootSize.height, rootSize.at = frame.Width, frame.Height, time.Now()
	rootSize.Unlock()
	return frame, nil
}

// grabRoot reads the whole root window. The size is asked for every frame
// rather than taken from the connection setup, which reports the size at
// connect time: a monitor plugged in or a resolution changed through RandR
// grows the root, and a stale size would crop the new area away.
func (c *x11Conn) grabRoot() (*ScreenFrame, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	width, height, err := c.geometry(c.root)
	if err != nil {
		return nil, err
	}

	req := make([]byte, 20)
	req[0] = opGetImage
	req[1] = zPixmap
	binary.LittleEndian.PutUint16(req[2:], 5)
	binary.LittleEndian.PutUint32(req[4:], c.root)
	binary.LittleEndian.PutUint16(req[12:], uint16(width))
	binary.LittleEndian.PutUint16(req[14:], uint16(height))
	binary.LittleEndian.PutUint32(req[16:], 0xffffffff) // all planes

	reply, err := c.request(req)
	if err != nil {
		return nil, err
	}
	// Depth 24 and 32 are both four bytes a pixel in ZPixmap on every server
	// in use; 15- and 16-bit desktops are the exception this does not decode.
	if depth := reply[1]; depth != 24 && depth != 32 {
		return nil, fmt.Errorf("system: %d-bit X desktops cannot be captured", depth)
	}
	pix := reply[32:]
	stride := width * 4
	if len(pix) < stride*height {
		return nil, errors.New("system: X image reply truncated")
	}

	frame := &ScreenFrame{Width: width, Height: height, Stride: stride, Pix: pix, CursorX: -1, CursorY: -1}
	if x, y, err := c.pointerLocked(); err == nil && x >= 0 && y >= 0 && x < width && y < height {
		frame.CursorX, frame.CursorY = x, y
	}
	return frame, nil
}

// geometry reads a drawable's size. The caller holds the lock.
func (c *x11Conn) geometry(drawable uint32) (width, height int, err error) {
	req := make([]byte, 8)
	req[0] = opGetGeometry
	binary.LittleEndian.PutUint16(req[2:], 2)
	binary.LittleEndian.PutUint32(req[4:], drawable)
	reply, err := c.request(req)
	if err != nil {
		return 0, 0, err
	}
	width = int(binary.LittleEndian.Uint16(reply[16:]))
	height = int(binary.LittleEndian.Uint16(reply[18:]))
	if width == 0 || height == 0 {
		return 0, 0, errors.New("system: X root window has no size")
	}
	return width, height, nil
}

// pointerLocked is queryPointer for a caller that already holds the lock.
func (c *x11Conn) pointerLocked() (x, y int, err error) {
	req := make([]byte, 8)
	req[0] = opQueryPointer
	binary.LittleEndian.PutUint16(req[2:], 2)
	binary.LittleEndian.PutUint32(req[4:], c.root)
	reply, err := c.request(req)
	if err != nil {
		return 0, 0, err
	}
	return int(int16(binary.LittleEndian.Uint16(reply[16:]))), int(int16(binary.LittleEndian.Uint16(reply[18:]))), nil
}

// desktopSize returns the root size, asking the server only when the cached
// one is old. Streaming refreshes it every frame, so during a session this
// never costs a request.
func desktopSize(conn *x11Conn) (int, int, error) {
	rootSize.Lock()
	defer rootSize.Unlock()
	if rootSize.width > 0 && time.Since(rootSize.at) < 2*time.Second {
		return rootSize.width, rootSize.height, nil
	}
	conn.mu.Lock()
	w, h, err := conn.geometry(conn.root)
	conn.mu.Unlock()
	if err != nil {
		return 0, 0, err
	}
	rootSize.width, rootSize.height, rootSize.at = w, h, time.Now()
	return w, h, nil
}

func pointerTo(nx, ny float64) error {
	conn, err := x11Client()
	if err != nil {
		return err
	}
	w, h, err := desktopSize(conn)
	if err != nil {
		return err
	}
	x := int16(nx * float64(w-1))
	y := int16(ny * float64(h-1))
	conn.mu.Lock()
	defer conn.mu.Unlock()
	// detail 0 selects absolute motion; root None is the pointer's own screen,
	// which on every desktop in use is the only one.
	return conn.fakeInput(evMotionNotify, 0, x, y)
}

// X keysyms for the named editing keys.
var keyKeysyms = map[string]uint32{
	protocol.KeyBackspace: 0xff08,
	protocol.KeyDelete:    0xffff,
	protocol.KeyEnter:     keysymReturn,
	protocol.KeyTab:       keysymTab,
	protocol.KeyEscape:    0xff1b,
	protocol.KeyLeft:      keysymLeft,
	protocol.KeyRight:     keysymRight,
	protocol.KeyUp:        0xff52,
	protocol.KeyDown:      0xff54,
	protocol.KeyHome:      0xff50,
	protocol.KeyEnd:       0xff57,
	protocol.KeyPageUp:    0xff55,
	protocol.KeyPageDown:  0xff56,
}

func pressKey(name string) error {
	keysym, ok := keyKeysyms[name]
	if !ok {
		return fmt.Errorf("input: unknown key %q", name)
	}
	conn, err := x11Client()
	if err != nil {
		return err
	}
	conn.mu.Lock()
	defer conn.mu.Unlock()
	key, ok := conn.keycodeFor(keysym)
	if !ok {
		return fmt.Errorf("input: key %q is not on this keyboard layout", name)
	}
	if err := conn.tapKey(key.code); err != nil {
		return err
	}
	return conn.flushErrors()
}
