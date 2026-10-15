//go:build linux

package system

import (
	"encoding/binary"
	"errors"
	"fmt"
	"image"
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
	opGetAtomName = 17
	opGetGeometry = 14
	opGetImage    = 73
	zPixmap       = 2

	randrQueryVersion = 0
	randrGetMonitors  = 42
)

// capture is the dedicated connection screen grabs use. It is redialled after
// an error, because a stream that failed mid-reply has lost its framing and
// every later request on it would read garbage.
var capture struct {
	sync.Mutex
	conn *x11Conn

	// randr is the RandR major opcode on conn, or zero when the server has
	// no RandR 1.5 and monitors cannot be told apart.
	randr        byte
	randrChecked bool
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
	capture.randr, capture.randrChecked = 0, false
	return c, nil
}

func dropCaptureConn() {
	if capture.conn != nil {
		capture.conn.conn.Close()
	}
	capture.conn = nil
}

func screenSupported() bool {
	capture.Lock()
	defer capture.Unlock()
	_, err := captureConn()
	return err == nil
}

func captureScreen(region image.Rectangle) (*ScreenFrame, error) {
	capture.Lock()
	defer capture.Unlock()

	c, err := captureConn()
	if err != nil {
		return nil, err
	}
	frame, rootW, rootH, err := c.grabRoot(region)
	if err != nil {
		dropCaptureConn()
		return nil, err
	}

	rootSize.Lock()
	rootSize.width, rootSize.height, rootSize.at = rootW, rootH, time.Now()
	rootSize.Unlock()
	return frame, nil
}

// grabRoot reads a region of the root window, or all of it. The size is asked
// for every frame rather than taken from the connection setup, which reports
// the size at connect time: a monitor plugged in or a resolution changed
// through RandR grows the root, and a stale size would crop the new area away.
func (c *x11Conn) grabRoot(region image.Rectangle) (*ScreenFrame, int, int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	rootW, rootH, err := c.geometry(c.root)
	if err != nil {
		return nil, 0, 0, err
	}
	area := image.Rect(0, 0, rootW, rootH)
	if !region.Empty() {
		area = region.Intersect(area)
		if area.Empty() {
			return nil, 0, 0, errors.New("system: the chosen display is no longer on the desktop")
		}
	}
	width, height := area.Dx(), area.Dy()

	req := make([]byte, 20)
	req[0] = opGetImage
	req[1] = zPixmap
	binary.LittleEndian.PutUint16(req[2:], 5)
	binary.LittleEndian.PutUint32(req[4:], c.root)
	binary.LittleEndian.PutUint16(req[8:], uint16(area.Min.X))
	binary.LittleEndian.PutUint16(req[10:], uint16(area.Min.Y))
	binary.LittleEndian.PutUint16(req[12:], uint16(width))
	binary.LittleEndian.PutUint16(req[14:], uint16(height))
	binary.LittleEndian.PutUint32(req[16:], 0xffffffff) // all planes

	reply, err := c.request(req)
	if err != nil {
		return nil, 0, 0, err
	}
	// Depth 24 and 32 are both four bytes a pixel in ZPixmap on every server
	// in use; 15- and 16-bit desktops are the exception this does not decode.
	if depth := reply[1]; depth != 24 && depth != 32 {
		return nil, 0, 0, fmt.Errorf("system: %d-bit X desktops cannot be captured", depth)
	}
	pix := reply[32:]
	stride := width * 4
	if len(pix) < stride*height {
		return nil, 0, 0, errors.New("system: X image reply truncated")
	}

	frame := &ScreenFrame{
		Width: width, Height: height, Stride: stride, Pix: pix,
		DesktopWidth: rootW, DesktopHeight: rootH,
		CursorX: -1, CursorY: -1,
	}
	if x, y, err := c.pointerLocked(); err == nil {
		x, y = x-area.Min.X, y-area.Min.Y
		if x >= 0 && y >= 0 && x < width && y < height {
			frame.CursorX, frame.CursorY = x, y
		}
	}
	return frame, rootW, rootH, nil
}

// screenDisplays lists monitors through RandR 1.5's GetMonitors, which is
// what desktop environments themselves read: it reports logical monitors with
// the primary flag, rather than the CRTCs and outputs underneath them.
func screenDisplays() ([]protocol.ScreenDisplay, error) {
	capture.Lock()
	defer capture.Unlock()

	c, err := captureConn()
	if err != nil {
		return nil, err
	}
	displays, err := c.monitors()
	if err != nil {
		dropCaptureConn()
		return nil, err
	}
	return displays, nil
}

func (c *x11Conn) monitors() ([]protocol.ScreenDisplay, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	rootW, rootH, err := c.geometry(c.root)
	if err != nil {
		return nil, err
	}
	whole := []protocol.ScreenDisplay{{
		ID: "screen", Name: "Desktop", Width: rootW, Height: rootH, Primary: true,
	}}

	if !capture.randrChecked {
		capture.randrChecked = true
		capture.randr = c.randrOpcode()
	}
	if capture.randr == 0 {
		return whole, nil
	}

	req := make([]byte, 12)
	req[0] = capture.randr
	req[1] = randrGetMonitors
	binary.LittleEndian.PutUint16(req[2:], 3)
	binary.LittleEndian.PutUint32(req[4:], c.root)
	req[8] = 1 // active monitors only
	reply, err := c.request(req)
	if err != nil {
		return nil, err
	}

	count := int(binary.LittleEndian.Uint32(reply[12:]))
	displays := make([]protocol.ScreenDisplay, 0, count)
	offset := 32
	for range count {
		if offset+24 > len(reply) {
			break
		}
		info := reply[offset:]
		nameAtom := binary.LittleEndian.Uint32(info[0:])
		outputs := int(binary.LittleEndian.Uint16(info[6:]))
		d := protocol.ScreenDisplay{
			Primary: info[4] != 0,
			X:       int(int16(binary.LittleEndian.Uint16(info[8:]))),
			Y:       int(int16(binary.LittleEndian.Uint16(info[10:]))),
			Width:   int(binary.LittleEndian.Uint16(info[12:])),
			Height:  int(binary.LittleEndian.Uint16(info[14:])),
		}
		d.Name = c.atomNameLocked(nameAtom)
		if d.Name == "" {
			d.Name = fmt.Sprintf("Display %d", len(displays)+1)
		}
		d.ID = d.Name
		displays = append(displays, d)
		offset += 24 + outputs*4
	}
	if len(displays) == 0 {
		return whole, nil
	}
	return displays, nil
}

// randrOpcode finds RandR and declares this client speaks 1.5. Declaring the
// version is not a formality: the server answers requests newer than what a
// client claimed with BadRequest. Zero means no usable RandR.
func (c *x11Conn) randrOpcode() byte {
	const name = "RANDR"
	req := make([]byte, 0, 8+len(name)+pad(len(name)))
	req = append(req, opQueryExtension, 0)
	req = binary.LittleEndian.AppendUint16(req, uint16(2+(len(name)+pad(len(name)))/4))
	req = binary.LittleEndian.AppendUint16(req, uint16(len(name)))
	req = append(req, 0, 0)
	req = append(req, name...)
	req = append(req, make([]byte, pad(len(name)))...)
	reply, err := c.request(req)
	if err != nil || reply[8] == 0 {
		return 0
	}
	opcode := reply[9]

	version := make([]byte, 12)
	version[0] = opcode
	version[1] = randrQueryVersion
	binary.LittleEndian.PutUint16(version[2:], 3)
	binary.LittleEndian.PutUint32(version[4:], 1)
	binary.LittleEndian.PutUint32(version[8:], 5)
	reply, err = c.request(version)
	if err != nil {
		return 0
	}
	major := binary.LittleEndian.Uint32(reply[8:])
	minor := binary.LittleEndian.Uint32(reply[12:])
	if major < 1 || (major == 1 && minor < 5) {
		return 0
	}
	return opcode
}

// atomNameLocked reads an atom's name: a monitor's is its output, "DP-0".
func (c *x11Conn) atomNameLocked(atom uint32) string {
	if atom == 0 {
		return ""
	}
	req := make([]byte, 8)
	req[0] = opGetAtomName
	binary.LittleEndian.PutUint16(req[2:], 2)
	binary.LittleEndian.PutUint32(req[4:], atom)
	reply, err := c.request(req)
	if err != nil || len(reply) < 32 {
		return ""
	}
	n := int(binary.LittleEndian.Uint16(reply[8:]))
	if 32+n > len(reply) {
		return ""
	}
	return string(reply[32 : 32+n])
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
