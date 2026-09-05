//go:build windows

package system

import (
	"fmt"
	"sync"
	"time"
	"unsafe"

	"switchboard/backend/internal/protocol"
)

// Air mouse injection goes through user32!SendInput, the same path the OS uses
// for a real device: the events enter ahead of the message queue, so they work
// in any focused window rather than only in ones that accept synthetic posts.
//
// SendInput cannot reach a window running at a higher integrity level than the
// daemon. An elevated app under the cursor will ignore the pointer until the
// daemon itself is elevated, which is a Windows security boundary and not
// something to work around.
var procSendInput = user32.NewProc("SendInput")

const (
	inputMouse    = 0
	inputKeyboard = 1

	mouseEventMove       = 0x0001
	mouseEventLeftDown   = 0x0002
	mouseEventLeftUp     = 0x0004
	mouseEventRightDown  = 0x0008
	mouseEventRightUp    = 0x0010
	mouseEventMiddleDown = 0x0020
	mouseEventMiddleUp   = 0x0040
	mouseEventWheel      = 0x0800
	mouseEventHWheel     = 0x1000

	// wheelDelta is one detent of a physical wheel, the unit mouseData counts
	// in. Fractions of it are legal and are what a smooth two-finger scroll
	// sends.
	wheelDelta = 120

	vkControl = 0x11
	vkMenu    = 0x12 // Alt
	vkLWin    = 0x5B
	vkTab     = 0x09
	vkLeft    = 0x25
	vkRight   = 0x27
	vkD       = 0x44
)

// rawInput mirrors the Windows INPUT union. The payload is held as opaque
// bytes and typed on write, because Go has no unions and the mouse and
// keyboard variants differ in size.
type rawInput struct {
	kind uint32
	_    uint32 // union alignment padding on 64-bit
	data [32]byte
}

type mouseInput struct {
	dx, dy    int32
	mouseData uint32
	flags     uint32
	time      uint32
	extraInfo uintptr
}

type keyboardInput struct {
	vk, scan  uint16
	flags     uint32
	time      uint32
	extraInfo uintptr
}

func newMouseInput(dx, dy int32, mouseData, flags uint32) rawInput {
	in := rawInput{kind: inputMouse}
	m := (*mouseInput)(unsafe.Pointer(&in.data[0]))
	m.dx, m.dy, m.mouseData, m.flags = dx, dy, mouseData, flags
	return in
}

func newKeyInput(vk uint16, up bool) rawInput {
	in := rawInput{kind: inputKeyboard}
	k := (*keyboardInput)(unsafe.Pointer(&in.data[0]))
	k.vk = vk
	if up {
		k.flags = keyEventKeyUp
	}
	return in
}

// sendInputs submits a batch atomically. One call per batch matters: SendInput
// guarantees no other thread's input is interleaved within a single call, so a
// modifier and its key cannot be split by a keystroke from the real keyboard.
func sendInputs(events ...rawInput) error {
	if len(events) == 0 {
		return nil
	}
	sent, _, err := procSendInput.Call(
		uintptr(len(events)),
		uintptr(unsafe.Pointer(&events[0])),
		unsafe.Sizeof(rawInput{}),
	)
	if int(sent) != len(events) {
		return fmt.Errorf("input: SendInput accepted %d of %d events: %w", sent, len(events), err)
	}
	return nil
}

func inputSupported() bool { return true }

// residual carries the sub-unit remainder of motion and scrolling between
// frames. A 60 Hz drag sends deltas well under one pixel per frame, and
// truncating each one independently would swallow slow movement entirely —
// the cursor would simply refuse to creep.
var residual struct {
	sync.Mutex
	moveX, moveY     float64
	scrollX, scrollY float64
}

// take splits an accumulated float into the whole units to send now and the
// remainder to carry forward.
func take(acc *float64, delta float64) int32 {
	*acc += delta
	whole := float64(int64(*acc)) // truncate toward zero, so sign is preserved
	*acc -= whole
	return int32(whole)
}

func moveMouse(dx, dy float64) error {
	residual.Lock()
	x := take(&residual.moveX, dx)
	y := take(&residual.moveY, dy)
	residual.Unlock()

	if x == 0 && y == 0 {
		return nil
	}
	return sendInputs(newMouseInput(x, y, 0, mouseEventMove))
}

var buttonFlags = map[string][2]uint32{
	protocol.ButtonLeft:   {mouseEventLeftDown, mouseEventLeftUp},
	protocol.ButtonRight:  {mouseEventRightDown, mouseEventRightUp},
	protocol.ButtonMiddle: {mouseEventMiddleDown, mouseEventMiddleUp},
}

func mouseButton(button, action string) error {
	flags, ok := buttonFlags[button]
	if !ok {
		return fmt.Errorf("input: unknown button %q", button)
	}
	down, up := flags[0], flags[1]

	switch action {
	case protocol.ButtonDown:
		return sendInputs(newMouseInput(0, 0, 0, down))
	case protocol.ButtonUp:
		return sendInputs(newMouseInput(0, 0, 0, up))
	case protocol.ButtonClick:
		return sendInputs(
			newMouseInput(0, 0, 0, down),
			newMouseInput(0, 0, 0, up),
		)
	case protocol.ButtonDouble:
		// Sent as one batch so the pair always lands inside the system's
		// double-click interval, however busy the machine is.
		return sendInputs(
			newMouseInput(0, 0, 0, down),
			newMouseInput(0, 0, 0, up),
			newMouseInput(0, 0, 0, down),
			newMouseInput(0, 0, 0, up),
		)
	}
	return fmt.Errorf("input: unknown button action %q", action)
}

func scrollMouse(dx, dy float64, ctrl bool) error {
	residual.Lock()
	y := take(&residual.scrollY, dy*wheelDelta)
	x := take(&residual.scrollX, dx*wheelDelta)
	residual.Unlock()

	if x == 0 && y == 0 {
		return nil
	}

	events := make([]rawInput, 0, 4)
	if ctrl {
		events = append(events, newKeyInput(vkControl, false))
	}
	if y != 0 {
		events = append(events, newMouseInput(0, 0, uint32(y), mouseEventWheel))
	}
	if x != 0 {
		events = append(events, newMouseInput(0, 0, uint32(x), mouseEventHWheel))
	}
	if ctrl {
		events = append(events, newKeyInput(vkControl, true))
	}
	return sendInputs(events...)
}

// gestureKeys maps each named gesture to the modifier chord Windows uses for
// it. Nothing outside this table can be injected, so the air mouse cannot be
// turned into a general keyboard by a malformed frame.
var gestureKeys = map[string][]uint16{
	protocol.GestureTaskView:     {vkLWin, vkTab},
	protocol.GestureShowDesktop:  {vkLWin, vkD},
	protocol.GestureDesktopLeft:  {vkLWin, vkControl, vkLeft},
	protocol.GestureDesktopRight: {vkLWin, vkControl, vkRight},
	protocol.GestureBack:         {vkMenu, vkLeft},
	protocol.GestureForward:      {vkMenu, vkRight},
}

func shellGesture(name string) error {
	keys, ok := gestureKeys[name]
	if !ok {
		return fmt.Errorf("input: unknown gesture %q", name)
	}
	events := make([]rawInput, 0, len(keys)*2)
	for _, vk := range keys {
		events = append(events, newKeyInput(vk, false))
	}
	// Released in reverse so the modifiers outlive the key they modify, which
	// is what the shell watches for.
	for i := len(keys) - 1; i >= 0; i-- {
		events = append(events, newKeyInput(keys[i], true))
	}
	if err := sendInputs(events...); err != nil {
		return err
	}
	// Win+Tab opens an animated shell surface; giving it a beat before the
	// next gesture keeps a fast double-swipe from being dropped mid-animation.
	time.Sleep(20 * time.Millisecond)
	return nil
}
