//go:build linux

package system

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// A minimal X11 client, carrying just enough of the protocol to drive the
// XTEST extension: connect, authenticate, resolve keysyms to keycodes, and
// inject pointer and keyboard events.
//
// This is written out rather than pulled from a library because the library
// would be an X11 binding -- either cgo against libX11, which the daemon
// cannot use since it builds with CGO_ENABLED=0, or a full pure-Go protocol
// implementation whose window, graphics and extension machinery is several
// thousand lines this code will never call. What is actually needed is three
// requests. The wire format for them has not changed since X11R6 and will not.
//
// Everything is little-endian: the connection setup declares the client's byte
// order and every subsequent field follows it, so on the amd64 and arm64 hosts
// this runs on nothing is ever byte-swapped.

const (
	// Core protocol opcodes.
	opQueryExtension     = 98
	opGetKeyboardMapping = 101
	opChangeKeyboardMap  = 100
	opGetModifierMapping = 119
	opQueryPointer       = 38

	// XTEST minor opcode for XTestFakeInput.
	xtestFakeInput = 2

	// X event types, as XTestFakeInput's `type` field takes them.
	evKeyPress      = 2
	evKeyRelease    = 3
	evButtonPress   = 4
	evButtonRelease = 5
	evMotionNotify  = 6
)

// X11 keysyms for the keys the gesture table and text injection need.
const (
	keysymSuperL   = 0xffeb
	keysymTab      = 0xff09
	keysymControlL = 0xffe3
	keysymAltL     = 0xffe9
	keysymShiftL   = 0xffe1
	keysymLeft     = 0xff51
	keysymRight    = 0xff53
	keysymD        = 0x0064 // lowercase 'd'
	keysymReturn   = 0xff0d
	keysymSpace    = 0x0020
)

// x11Key is where a keysym lives on the current layout: which physical
// keycode produces it, and whether Shift has to be held for it.
type x11Key struct {
	code  byte
	shift bool
}

// x11Conn is an authenticated connection to the X server.
//
// It is held open for the life of the daemon. A pointer drag sends events at
// the phone's frame rate, and re-authenticating per event would cost a socket
// connect and a round trip for every pixel of motion.
type x11Conn struct {
	mu   sync.Mutex
	conn net.Conn

	// xtestOpcode is the major opcode the server assigned XTEST, discovered
	// once at connect. The extension is not guaranteed to be present, though
	// every X server shipped in twenty years has it.
	xtestOpcode byte

	// root is the first screen's root window, needed only by queryPointer.
	root uint32

	// seq counts requests sent, mirroring the server's own numbering so a
	// reply can be matched to the request that asked for it.
	seq uint16

	minKeycode, maxKeycode byte
	keysymsPerCode         byte

	// keycodes maps keysym to the keycode that produces it under the user's
	// current layout, built once from the server's mapping table. A Dvorak or
	// AZERTY user has entirely different numbers here, which is exactly why it
	// is read rather than assumed.
	keycodes map[uint32]x11Key

	// spareKeycode is an unused slot in the keyboard mapping, borrowed to type
	// characters the layout has no key for. Zero when the mapping is full.
	spareKeycode byte
}

var (
	x11Once sync.Once
	x11     *x11Conn
	x11Err  error
)

// errNoDisplay reports a host with no reachable X server: a Wayland session
// without Xwayland, a machine at a text console, a daemon started over ssh.
var errNoDisplay = errors.New("system: no X display available")

// x11Client returns the shared connection, dialling it once.
func x11Client() (*x11Conn, error) {
	x11Once.Do(func() { x11, x11Err = dialX11() })
	return x11, x11Err
}

// dialX11 connects, authenticates and discovers XTEST.
func dialX11() (*x11Conn, error) {
	display := os.Getenv("DISPLAY")
	if display == "" {
		return nil, errNoDisplay
	}
	host, number, screen, err := parseDisplay(display)
	if err != nil {
		return nil, err
	}
	_ = screen // XTest events are injected relative to the pointer's own screen.

	conn, err := dialXServer(host, number)
	if err != nil {
		return nil, fmt.Errorf("system: connecting to X display %s: %w", display, err)
	}

	name, data := xAuthority(host, number)
	c := &x11Conn{conn: conn}
	if err := c.handshake(name, data); err != nil {
		conn.Close()
		return nil, err
	}
	if err := c.queryXTest(); err != nil {
		conn.Close()
		return nil, err
	}
	if err := c.loadKeyboardMapping(); err != nil {
		conn.Close()
		return nil, err
	}
	return c, nil
}

// parseDisplay splits a DISPLAY value into host, display number and screen.
// The forms in use are ":0", ":0.0", "unix/:0" and "host:0.0".
func parseDisplay(display string) (host string, number, screen int, err error) {
	display = strings.TrimPrefix(display, "unix/")
	host, rest, ok := strings.Cut(display, ":")
	if !ok {
		return "", 0, 0, fmt.Errorf("system: malformed DISPLAY %q", display)
	}
	numberPart, screenPart, hasScreen := strings.Cut(rest, ".")
	number, err = strconv.Atoi(numberPart)
	if err != nil {
		return "", 0, 0, fmt.Errorf("system: malformed DISPLAY %q", display)
	}
	if hasScreen {
		screen, _ = strconv.Atoi(screenPart)
	}
	return host, number, screen, nil
}

// dialXServer opens the transport. An empty host means the local abstract or
// filesystem socket, which is what a desktop session always uses; a named host
// falls back to TCP on the conventional port.
func dialXServer(host string, number int) (net.Conn, error) {
	if host == "" || host == "localhost" {
		path := filepath.Join("/tmp/.X11-unix", "X"+strconv.Itoa(number))
		if conn, err := net.Dial("unix", path); err == nil {
			return conn, nil
		}
		// Linux abstract sockets, which is where a server started without a
		// writable /tmp listens. The leading NUL is the abstract namespace.
		if conn, err := net.Dial("unix", "\x00"+path); err == nil {
			return conn, nil
		}
		if host == "" {
			return nil, errNoDisplay
		}
	}
	return net.DialTimeout("tcp", net.JoinHostPort(host, strconv.Itoa(6000+number)), 2*time.Second)
}

// xAuthority finds the MIT-MAGIC-COOKIE-1 for this display.
//
// Without it the server answers the setup request with a refusal, and on a
// desktop session it always exists: the display manager writes it before
// starting the session. A missing or unreadable file is not fatal here, since
// a server started with -nolisten and no auth accepts an empty cookie.
func xAuthority(host string, number int) (name string, data []byte) {
	path := os.Getenv("XAUTHORITY")
	if path == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", nil
		}
		path = filepath.Join(home, ".Xauthority")
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", nil
	}

	hostname, _ := os.Hostname()
	want := strconv.Itoa(number)

	// The file is a flat sequence of records, each a run of big-endian
	// length-prefixed fields. There is no header and no index.
	var best struct {
		name string
		data []byte
	}
	for offset := 0; offset+2 <= len(raw); {
		// family, then address, number, name and data, each 2-byte length
		// prefixed.
		offset += 2
		fields := make([][]byte, 0, 4)
		ok := true
		for range 4 {
			if offset+2 > len(raw) {
				ok = false
				break
			}
			size := int(binary.BigEndian.Uint16(raw[offset:]))
			offset += 2
			if offset+size > len(raw) {
				ok = false
				break
			}
			fields = append(fields, raw[offset:offset+size])
			offset += size
		}
		if !ok {
			break
		}
		address, entryNumber, entryName, entryData := fields[0], fields[1], fields[2], fields[3]
		if string(entryNumber) != want || string(entryName) != "MIT-MAGIC-COOKIE-1" {
			continue
		}
		// A file can hold cookies for several machines. The one whose address
		// is this host is the right one; anything else is a usable fallback
		// only because a single-entry file is the common case.
		if string(address) == hostname || string(address) == host {
			return string(entryName), entryData
		}
		if best.name == "" {
			best.name, best.data = string(entryName), entryData
		}
	}
	return best.name, best.data
}

// pad rounds a length up to the 4-byte boundary the protocol requires.
func pad(n int) int { return (4 - n%4) % 4 }

// handshake performs the connection setup exchange.
func (c *x11Conn) handshake(authName string, authData []byte) error {
	nameLen, dataLen := len(authName), len(authData)
	req := make([]byte, 0, 12+nameLen+pad(nameLen)+dataLen+pad(dataLen))
	req = append(req, 'l', 0) // little-endian, one pad byte
	req = binary.LittleEndian.AppendUint16(req, 11)
	req = binary.LittleEndian.AppendUint16(req, 0)
	req = binary.LittleEndian.AppendUint16(req, uint16(nameLen))
	req = binary.LittleEndian.AppendUint16(req, uint16(dataLen))
	req = append(req, 0, 0)
	req = append(req, authName...)
	req = append(req, make([]byte, pad(nameLen))...)
	req = append(req, authData...)
	req = append(req, make([]byte, pad(dataLen))...)

	if _, err := c.conn.Write(req); err != nil {
		return fmt.Errorf("system: X setup: %w", err)
	}

	header := make([]byte, 8)
	if _, err := io.ReadFull(c.conn, header); err != nil {
		return fmt.Errorf("system: X setup reply: %w", err)
	}
	// The trailing length is in 4-byte units and covers everything after this
	// header, so the body must be drained whether or not it is parsed --
	// otherwise the first real request reads the tail of the setup.
	body := make([]byte, int(binary.LittleEndian.Uint16(header[6:]))*4)
	if _, err := io.ReadFull(c.conn, body); err != nil {
		return fmt.Errorf("system: X setup body: %w", err)
	}

	switch header[0] {
	case 1: // success
	case 2:
		return errors.New("system: X server demanded further authentication")
	default:
		reason := strings.TrimRight(string(body[:min(int(header[1]), len(body))]), "\x00")
		return fmt.Errorf("system: X server refused the connection: %s", reason)
	}

	// Within the success body: release(4), id-base(4), id-mask(4),
	// motion-buffer(4), vendor-len(2), max-request(2), screen-count(1),
	// format-count(1), image-order(1), bitmap-order(1), scanline-unit(1),
	// scanline-pad(1), min-keycode(1), max-keycode(1), unused(4). Then the
	// vendor string, the pixmap formats and the screens.
	if len(body) < 32 {
		return errors.New("system: X setup reply truncated")
	}
	vendorLen := int(binary.LittleEndian.Uint16(body[16:]))
	screenCount := int(body[20])
	formatCount := int(body[21])
	c.minKeycode = body[26]
	c.maxKeycode = body[27]

	// The root window of the first screen. XTest itself does not need it --
	// relative motion follows the pointer's own screen -- but querying the
	// pointer does, and that is the only way to confirm injection landed.
	if screenCount > 0 {
		offset := 32 + vendorLen + pad(vendorLen) + 8*formatCount
		if offset+4 <= len(body) {
			c.root = binary.LittleEndian.Uint32(body[offset:])
		}
	}
	return nil
}

// queryPointer reads the pointer's position on the root window.
//
// Nothing in the injection path needs it. It exists so that injection can be
// verified: moving the cursor and reading back where it landed is the only
// check that distinguishes a request the server accepted from one it silently
// discarded, and a fake input that goes nowhere produces no error at all.
func (c *x11Conn) queryPointer() (x, y int16, err error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	req := make([]byte, 8)
	req[0] = opQueryPointer
	binary.LittleEndian.PutUint16(req[2:], 2)
	binary.LittleEndian.PutUint32(req[4:], c.root)

	reply, err := c.request(req)
	if err != nil {
		return 0, 0, err
	}
	return int16(binary.LittleEndian.Uint16(reply[16:])), int16(binary.LittleEndian.Uint16(reply[18:])), nil
}

// x11Timeout bounds a single request. A desynchronised stream would otherwise
// block the caller forever, and the caller is on the path of every pointer
// event the phone sends.
const x11Timeout = 3 * time.Second

// send writes one request and advances the sequence counter.
//
// The server numbers every request it receives, starting at 1, and stamps
// each reply, error and event with the number it belongs to. Counting them
// here is what lets request below tell its own reply from the wreckage of an
// earlier reply-less one.
func (c *x11Conn) send(req []byte) error {
	if _, err := c.conn.Write(req); err != nil {
		return err
	}
	c.seq++
	return nil
}

// request writes one request and returns its reply.
//
// The complication is that X multiplexes three kinds of message onto one
// stream: replies, errors and events, distinguished by the first byte -- 0 is
// an error, 1 a reply, anything else an event. Events arrive unsolicited. The
// injection path never selects any, but two are sent to every client whether
// it asked or not, and MappingNotify is one of them: changing a keycode to
// type an unmapped character makes the server announce it to everyone,
// including the client that did it.
//
// Reading the next 32 bytes and calling them the reply therefore works right
// up until the first remapped character, at which point the announcement is
// parsed as a reply, its length field is read out of an event's body, and the
// connection blocks waiting for a few hundred kilobytes that will never come.
// That wedges every later pointer event behind it. Events are skipped here
// instead, and the sequence number is matched so a reply is never handed to
// the wrong caller.
//
// The lock is held by the caller.
func (c *x11Conn) request(req []byte) ([]byte, error) {
	if err := c.conn.SetDeadline(time.Now().Add(x11Timeout)); err != nil {
		return nil, err
	}
	defer c.conn.SetDeadline(time.Time{})

	if err := c.send(req); err != nil {
		return nil, err
	}
	want := c.seq

	// An error stamped with an earlier sequence belongs to one of the
	// reply-less injection requests. It is held rather than returned
	// immediately, so this request's own reply is still drained off the
	// stream before the failure is reported.
	var earlier error

	for {
		msg := make([]byte, 32)
		if _, err := io.ReadFull(c.conn, msg); err != nil {
			return nil, err
		}
		seq := binary.LittleEndian.Uint16(msg[2:])

		switch msg[0] {
		case 0: // error
			err := fmt.Errorf("system: X error code %d on request %d", msg[1], seq)
			if seq == want {
				return nil, err
			}
			if earlier == nil {
				earlier = err
			}
		case 1: // reply
			// A reply longer than 32 bytes states the remainder in 4-byte
			// units, and must be drained even when it is not ours.
			var tail []byte
			if extra := int(binary.LittleEndian.Uint32(msg[4:])) * 4; extra > 0 {
				tail = make([]byte, extra)
				if _, err := io.ReadFull(c.conn, tail); err != nil {
					return nil, err
				}
			}
			if seq != want {
				continue
			}
			if earlier != nil {
				return nil, earlier
			}
			return append(msg, tail...), nil
		default: // event, unsolicited
		}
	}
}

// queryXTest discovers the extension's major opcode.
func (c *x11Conn) queryXTest() error {
	const name = "XTEST"
	req := make([]byte, 0, 8+len(name)+pad(len(name)))
	req = append(req, opQueryExtension, 0)
	req = binary.LittleEndian.AppendUint16(req, uint16(2+(len(name)+pad(len(name)))/4))
	req = binary.LittleEndian.AppendUint16(req, uint16(len(name)))
	req = append(req, 0, 0)
	req = append(req, name...)
	req = append(req, make([]byte, pad(len(name)))...)

	reply, err := c.request(req)
	if err != nil {
		return fmt.Errorf("system: querying XTEST: %w", err)
	}
	if reply[8] == 0 {
		return errors.New("system: X server has no XTEST extension")
	}
	c.xtestOpcode = reply[9]
	return nil
}

// loadKeyboardMapping reads the server's keysym table and indexes it.
//
// Keycodes are hardware scancodes and the mapping from them to keysyms is the
// user's layout. Hard-coding "Tab is 23" happens to be right on a US PC layout
// and wrong the moment someone switches to Dvorak, so the table is read and
// searched instead.
func (c *x11Conn) loadKeyboardMapping() error {
	count := int(c.maxKeycode) - int(c.minKeycode) + 1
	if count <= 0 {
		return errors.New("system: X server reported an empty keycode range")
	}

	req := make([]byte, 0, 8)
	req = append(req, opGetKeyboardMapping, 0)
	req = binary.LittleEndian.AppendUint16(req, 2)
	req = append(req, c.minKeycode, byte(count), 0, 0)

	reply, err := c.request(req)
	if err != nil {
		return fmt.Errorf("system: reading the keyboard mapping: %w", err)
	}
	perCode := reply[1]
	if perCode == 0 {
		return errors.New("system: X server reported no keysyms per keycode")
	}
	c.keysymsPerCode = perCode

	c.keycodes = make(map[uint32]x11Key, count*2)
	syms := reply[32:]
	for i := range count {
		code := byte(int(c.minKeycode) + i)
		unused := true
		for slot := range int(perCode) {
			offset := (i*int(perCode) + slot) * 4
			if offset+4 > len(syms) {
				break
			}
			keysym := binary.LittleEndian.Uint32(syms[offset:])
			if keysym == 0 {
				continue
			}
			unused = false
			// First mapping wins: the same keysym can appear on several
			// keycodes -- both shift keys, the numeric keypad -- and the
			// lowest is the one a user would have pressed.
			if _, seen := c.keycodes[keysym]; !seen {
				// Within a group the slots alternate unshifted, shifted, so an
				// odd slot is a character that needs Shift held. Typing "A"
				// without it produces "a".
				c.keycodes[keysym] = x11Key{code: code, shift: slot%2 == 1}
			}
		}
		// A keycode with no keysyms at all is free to borrow for characters
		// the layout cannot otherwise produce.
		if unused && c.spareKeycode == 0 {
			c.spareKeycode = code
		}
	}
	return nil
}

// keycodeFor resolves a keysym to the keycode that produces it, if any.
func (c *x11Conn) keycodeFor(keysym uint32) (x11Key, bool) {
	key, ok := c.keycodes[keysym]
	return key, ok
}

// fakeInput sends one XTestFakeInput request.
//
// It expects no reply, which is what makes pointer motion cheap enough to send
// at a phone's frame rate: the request goes out and the caller does not wait.
// The trade-off is that an error surfaces as a protocol error on some later
// request rather than here, which for injection is the right way round.
func (c *x11Conn) fakeInput(eventType, detail byte, x, y int16) error {
	// xXTestFakeInputReq is 36 bytes: header(4), type, detail, pad(2),
	// time(4), root(4), pad(8), rootX(2), rootY(2), pad(7), deviceid(1).
	req := make([]byte, 36)
	req[0] = c.xtestOpcode
	req[1] = xtestFakeInput
	binary.LittleEndian.PutUint16(req[2:], 9) // length in 4-byte units
	req[4] = eventType
	req[5] = detail
	// time 0 means "immediately"; root 0 (None) means the screen the pointer
	// is already on, which is what relative motion has to follow across a
	// multi-monitor desktop.
	binary.LittleEndian.PutUint16(req[24:], uint16(x))
	binary.LittleEndian.PutUint16(req[26:], uint16(y))

	return c.send(req)
}

// flushErrors drains any protocol errors the server queued for the
// reply-less injection requests, so they surface promptly instead of being
// mistaken for the reply to whatever is asked next.
//
// GetModifierMapping is used as the probe because it is small, always
// available, and touches nothing.
func (c *x11Conn) flushErrors() error {
	req := make([]byte, 4)
	req[0] = opGetModifierMapping
	binary.LittleEndian.PutUint16(req[2:], 1)
	_, err := c.request(req)
	return err
}

// moveRelative nudges the pointer by a delta in pixels. detail 1 selects
// relative motion, which is what an air mouse sends: the phone has no idea
// where the host's cursor is and should not need one.
func (c *x11Conn) moveRelative(dx, dy int16) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.fakeInput(evMotionNotify, 1, dx, dy)
}

// button presses or releases an X button number.
func (c *x11Conn) button(number byte, press bool) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	eventType := byte(evButtonRelease)
	if press {
		eventType = evButtonPress
	}
	return c.fakeInput(eventType, number, 0, 0)
}

// click sends a press and release pair.
func (c *x11Conn) click(number byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if err := c.fakeInput(evButtonPress, number, 0, 0); err != nil {
		return err
	}
	return c.fakeInput(evButtonRelease, number, 0, 0)
}

// key presses or releases a keycode.
func (c *x11Conn) key(code byte, press bool) error {
	eventType := byte(evKeyRelease)
	if press {
		eventType = evKeyPress
	}
	return c.fakeInput(eventType, code, 0, 0)
}

// chord presses keys in order and releases them in reverse, so modifiers
// outlive the key they modify -- which is what a window manager watches for.
func (c *x11Conn) chord(codes []byte) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, code := range codes {
		if err := c.key(code, true); err != nil {
			return err
		}
	}
	for i := len(codes) - 1; i >= 0; i-- {
		if err := c.key(codes[i], false); err != nil {
			return err
		}
	}
	return c.flushErrors()
}

// readKeycodeSyms reads the keysyms currently bound to one keycode.
//
// The injection path never needs it: the mapping is indexed once at connect.
// It exists to verify a ChangeKeyboardMapping landed, which is the one request
// here whose encoding cannot be checked any other way -- a malformed one is
// answered with a protocol error that surfaces later, against some unrelated
// request, if it surfaces at all.
func (c *x11Conn) readKeycodeSyms(code byte) ([]uint32, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	req := make([]byte, 8)
	req[0] = opGetKeyboardMapping
	binary.LittleEndian.PutUint16(req[2:], 2)
	req[4] = code
	req[5] = 1

	reply, err := c.request(req)
	if err != nil {
		return nil, err
	}
	syms := make([]uint32, 0, reply[1])
	for slot := range int(reply[1]) {
		offset := 32 + slot*4
		if offset+4 > len(reply) {
			break
		}
		syms = append(syms, binary.LittleEndian.Uint32(reply[offset:]))
	}
	return syms, nil
}
