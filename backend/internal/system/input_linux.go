//go:build linux

package system

import (
	"encoding/binary"
	"fmt"
	"sync"
	"time"

	"switchboard/backend/internal/protocol"
)

// Linux air mouse injection, the counterpart to input_windows.go.
//
// Windows has SendInput, a single call that puts events ahead of the message
// queue for whatever has focus. X11's equivalent is the XTEST extension, which
// does the same thing at the server: events enter the ordinary dispatch path,
// so they reach any client rather than only ones that accept synthetic posts.
// The protocol for it is in x11_linux.go.
//
// The boundary is different from the Windows one and worth stating. SendInput
// cannot reach a window at a higher integrity level; XTEST cannot reach
// anything that is not an X client. In a Wayland session that means the
// compositor's own surfaces and native Wayland windows are out of reach, and
// only Xwayland-hosted applications respond. Reaching those would need
// /dev/uinput, which is root-only on every distribution and is a packaging
// decision -- a udev rule -- rather than something this code can arrange.
//
// A host with no X display reports the capability as absent, which the phone
// renders as the air mouse being unavailable rather than as a broken control.

// X button numbers. 1-3 are the physical buttons; 4-7 are the four wheel
// directions, which X models as buttons rather than as an axis. One press and
// release pair is one detent.
const (
	xButtonLeft        = 1
	xButtonMiddle      = 2
	xButtonRight       = 3
	xButtonUp          = 4
	xButtonDown        = 5
	xButtonLeftScroll  = 6
	xButtonRightScroll = 7
)

func inputSupported() bool {
	_, err := x11Client()
	return err == nil
}

// residual carries the sub-unit remainder of motion and scrolling between
// frames, exactly as the Windows backend does and for the same reason: a 60 Hz
// drag sends deltas well under one pixel per frame, and truncating each one
// independently would swallow slow movement entirely -- the cursor would
// simply refuse to creep.
var residual struct {
	sync.Mutex
	moveX, moveY     float64
	scrollX, scrollY float64
}

// take splits an accumulated float into the whole units to send now and the
// remainder to carry forward.
func take(acc *float64, delta float64) int {
	*acc += delta
	whole := float64(int64(*acc)) // truncate toward zero, so sign is preserved
	*acc -= whole
	return int(whole)
}

func moveMouse(dx, dy float64) error {
	conn, err := x11Client()
	if err != nil {
		return err
	}
	residual.Lock()
	x := take(&residual.moveX, dx)
	y := take(&residual.moveY, dy)
	residual.Unlock()

	if x == 0 && y == 0 {
		return nil
	}
	return conn.moveRelative(int16(x), int16(y))
}

var xButtons = map[string]byte{
	protocol.ButtonLeft:   xButtonLeft,
	protocol.ButtonRight:  xButtonRight,
	protocol.ButtonMiddle: xButtonMiddle,
}

func mouseButton(button, action string) error {
	number, ok := xButtons[button]
	if !ok {
		return fmt.Errorf("input: unknown button %q", button)
	}
	conn, err := x11Client()
	if err != nil {
		return err
	}

	switch action {
	case protocol.ButtonDown:
		return conn.button(number, true)
	case protocol.ButtonUp:
		return conn.button(number, false)
	case protocol.ButtonClick:
		return conn.click(number)
	case protocol.ButtonDouble:
		// Both pairs go out back to back with no round trip between them, so
		// the two clicks always land inside the server's double-click
		// interval however busy the machine is.
		if err := conn.click(number); err != nil {
			return err
		}
		return conn.click(number)
	}
	return fmt.Errorf("input: unknown button action %q", action)
}

// scrollMouse turns the wheel by a number of notches.
//
// X has no scroll delta: each notch is a press and release of button 4, 5, 6
// or 7, so a fractional scroll has to accumulate until it is worth a whole
// one. The Windows backend scales by wheelDelta for the same reason, just
// against a finer unit.
func scrollMouse(dx, dy float64, ctrl bool) error {
	conn, err := x11Client()
	if err != nil {
		return err
	}
	residual.Lock()
	y := take(&residual.scrollY, dy)
	x := take(&residual.scrollX, dx)
	residual.Unlock()

	if x == 0 && y == 0 {
		return nil
	}

	// Ctrl held turns a scroll into zoom in every application that has one.
	var ctrlCode byte
	if ctrl {
		if key, ok := conn.keycodeFor(keysymControlL); ok {
			ctrlCode = key.code
		}
	}

	conn.mu.Lock()
	defer conn.mu.Unlock()

	if ctrlCode != 0 {
		if err := conn.key(ctrlCode, true); err != nil {
			return err
		}
	}
	// A large flick is several notches. They are capped so a malformed or
	// wildly scaled frame cannot make the daemon spend a second scrolling.
	if err := wheel(conn, y, xButtonUp, xButtonDown); err != nil {
		return err
	}
	if err := wheel(conn, x, xButtonRightScroll, xButtonLeftScroll); err != nil {
		return err
	}
	if ctrlCode != 0 {
		if err := conn.key(ctrlCode, false); err != nil {
			return err
		}
	}
	return conn.flushErrors()
}

// maxScrollNotches bounds one scroll frame.
const maxScrollNotches = 20

// wheel sends |notches| clicks of whichever button matches the sign. The
// caller holds the connection lock.
func wheel(conn *x11Conn, notches int, positive, negative byte) error {
	button := positive
	if notches < 0 {
		button = negative
		notches = -notches
	}
	if notches > maxScrollNotches {
		notches = maxScrollNotches
	}
	for range notches {
		if err := conn.fakeInput(evButtonPress, button, 0, 0); err != nil {
			return err
		}
		if err := conn.fakeInput(evButtonRelease, button, 0, 0); err != nil {
			return err
		}
	}
	return nil
}

// gestureChords maps each named gesture to the key combination Linux desktops
// use for it. Nothing outside this table can be injected, so the air mouse
// cannot be turned into a general keyboard by a malformed frame.
//
// The bindings differ from the Windows table because the desktops do. Workspace
// switching and in-application navigation are the two that are genuinely
// universal -- Ctrl+Alt+arrow and Alt+arrow work on GNOME, KDE, Cinnamon and
// Xfce alike. Super and Super+D are the closest thing to a common overview and
// show-desktop binding, and a desktop that has rebound them simply does
// nothing, which is the same outcome as on a Windows host with the shortcut
// disabled by policy.
var gestureChords = map[string][]uint32{
	protocol.GestureTaskView:     {keysymSuperL},
	protocol.GestureShowDesktop:  {keysymSuperL, keysymD},
	protocol.GestureDesktopLeft:  {keysymControlL, keysymAltL, keysymLeft},
	protocol.GestureDesktopRight: {keysymControlL, keysymAltL, keysymRight},
	protocol.GestureBack:         {keysymAltL, keysymLeft},
	protocol.GestureForward:      {keysymAltL, keysymRight},
}

func shellGesture(name string) error {
	keysyms, ok := gestureChords[name]
	if !ok {
		return fmt.Errorf("input: unknown gesture %q", name)
	}
	conn, err := x11Client()
	if err != nil {
		return err
	}

	codes := make([]byte, 0, len(keysyms))
	for _, keysym := range keysyms {
		key, ok := conn.keycodeFor(keysym)
		if !ok {
			return fmt.Errorf("input: gesture %q needs a key this layout has no code for", name)
		}
		codes = append(codes, key.code)
	}
	if err := conn.chord(codes); err != nil {
		return err
	}
	// The overview surfaces animate; a beat here keeps a fast double swipe
	// from being dropped mid-animation, as on Windows.
	time.Sleep(20 * time.Millisecond)
	return nil
}

// ---- text injection ----

func keyboardSupported() bool { return inputSupported() }

// inputText types a string as keystrokes.
//
// Most characters are on the user's layout somewhere and only need their
// keycode and, where the keysym sits in a shifted slot, Shift held. The rest --
// an emoji, an accented letter on a US layout, anything the phone's keyboard
// can produce and the host's cannot -- go through a borrowed keycode: an
// unused slot in the mapping is pointed at the character's keysym, pressed,
// and handed back.
//
// That borrowing is what xdotool does, and it carries xdotool's caveat.
// Clients cache the keyboard mapping and refresh it when the server announces
// a change, so a character typed immediately after the remap can arrive at an
// application that has not caught up. The pause below is the same mitigation,
// and the reason the fast path is preferred wherever the layout can do the job.
func inputText(text string) error {
	if text == "" {
		return nil
	}
	conn, err := x11Client()
	if err != nil {
		return err
	}

	conn.mu.Lock()
	defer conn.mu.Unlock()

	shiftKey, haveShift := conn.keycodeFor(keysymShiftL)
	for _, r := range text {
		keysym := runeKeysym(r)
		if keysym == 0 {
			continue
		}
		if key, ok := conn.keycodeFor(keysym); ok {
			if key.shift && haveShift {
				if err := conn.key(shiftKey.code, true); err != nil {
					return err
				}
			}
			if err := conn.tapKey(key.code); err != nil {
				return err
			}
			if key.shift && haveShift {
				if err := conn.key(shiftKey.code, false); err != nil {
					return err
				}
			}
			continue
		}
		if err := conn.typeUnmapped(keysym); err != nil {
			return err
		}
	}
	return conn.flushErrors()
}

// runeKeysym maps a rune onto an X keysym.
//
// Latin-1 is the identity mapping, which is a historical accident of X's
// design and covers the whole of ASCII. Everything above it uses the Unicode
// keysym range the spec reserves, which is the codepoint with 0x01000000 set.
// Control characters have no keysym at all and are dropped, except the two
// that a text field actually acts on.
func runeKeysym(r rune) uint32 {
	switch r {
	case '\n', '\r':
		return keysymReturn
	case '\t':
		return keysymTab
	}
	if r < 0x20 {
		return 0
	}
	if r <= 0xff {
		return uint32(r)
	}
	return uint32(r) | 0x01000000
}

// tapKey presses and releases one keycode. The caller holds the lock.
func (c *x11Conn) tapKey(code byte) error {
	if err := c.key(code, true); err != nil {
		return err
	}
	return c.key(code, false)
}

// remapSettleTime is how long the borrowed keycode is left in place before it
// is used. X clients learn about a mapping change through a MappingNotify
// event and re-read the table asynchronously; typing into the gap produces the
// character the keycode used to mean.
const remapSettleTime = 12 * time.Millisecond

// typeUnmapped types a keysym the current layout has no key for, by borrowing
// an unused keycode. The caller holds the lock.
func (c *x11Conn) typeUnmapped(keysym uint32) error {
	if c.spareKeycode == 0 {
		// A layout using every keycode leaves nowhere to put the character.
		// Skipping it loses one glyph; failing would lose the whole message.
		return nil
	}
	if err := c.setKeycode(c.spareKeycode, keysym); err != nil {
		return err
	}
	time.Sleep(remapSettleTime)
	err := c.tapKey(c.spareKeycode)
	// The slot is always handed back, including after a failed press: leaving
	// a live keysym on a borrowed keycode would change what the user's own
	// keyboard does.
	time.Sleep(remapSettleTime)
	if restoreErr := c.setKeycode(c.spareKeycode, 0); err == nil {
		err = restoreErr
	}
	return err
}

// setKeycode points one keycode at a keysym, or at NoSymbol when keysym is 0.
//
// Every slot in the keycode's row is given the same value rather than just the
// unshifted one, so the character survives being typed while Shift or a
// secondary layout group happens to be active. XKB canonicalises the row after
// the fact and may drop the trailing slots, which is fine: what has to hold is
// the first group, and that is what the round-trip test asserts.
func (c *x11Conn) setKeycode(code byte, keysym uint32) error {
	perCode := int(c.keysymsPerCode)
	if perCode == 0 {
		perCode = 1
	}
	req := make([]byte, 0, 8+perCode*4)
	req = append(req, opChangeKeyboardMap, 1)
	req = binary.LittleEndian.AppendUint16(req, uint16(2+perCode))
	req = append(req, code, byte(perCode), 0, 0)
	for range perCode {
		req = binary.LittleEndian.AppendUint32(req, keysym)
	}
	return c.send(req)
}
