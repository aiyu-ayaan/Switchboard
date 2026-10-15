//go:build windows

package system

import (
	"errors"
	"fmt"
	"sync"
	"unsafe"

	"golang.org/x/sys/windows"

	"switchboard/backend/internal/protocol"
)

// Windows screen capture through GDI: BitBlt from the screen DC into a 32-bit
// DIB section spanning the virtual screen, so every monitor arrives as one
// picture in the same coordinates SendInput's virtual-desk mode uses.
//
// Desktop Duplication would be faster, but it is a COM and D3D11 surface this
// daemon would have to drive without cgo, and at the frame rates a JPEG
// stream sustains BitBlt is not the bottleneck. CAPTUREBLT is set so layered
// windows (tooltips, menus, most modern app chrome) are included.

var (
	gdi32 = windows.NewLazySystemDLL("gdi32.dll")

	procGetDC                         = user32.NewProc("GetDC")
	procReleaseDC                     = user32.NewProc("ReleaseDC")
	procGetSystemMetrics              = user32.NewProc("GetSystemMetrics")
	procGetCursorPos                  = user32.NewProc("GetCursorPos")
	procSetProcessDpiAwarenessContext = user32.NewProc("SetProcessDpiAwarenessContext")
	procSetProcessDPIAware            = user32.NewProc("SetProcessDPIAware")

	procCreateCompatibleDC = gdi32.NewProc("CreateCompatibleDC")
	procCreateDIBSection   = gdi32.NewProc("CreateDIBSection")
	procSelectObject       = gdi32.NewProc("SelectObject")
	procBitBlt             = gdi32.NewProc("BitBlt")
	procDeleteObject       = gdi32.NewProc("DeleteObject")
	procDeleteDC           = gdi32.NewProc("DeleteDC")
)

const (
	smXVirtualScreen  = 76
	smYVirtualScreen  = 77
	smCxVirtualScreen = 78
	smCyVirtualScreen = 79

	srcCopy    = 0x00CC0020
	captureBlt = 0x40000000

	mouseEventAbsolute    = 0x8000
	mouseEventVirtualDesk = 0x4000

	keyEventExtended = 0x0001
)

// dpiAwarePerMonitorV2 is DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2, a
// pseudo-handle of -4.
var dpiAwarePerMonitorV2 = ^uintptr(3)

type bitmapInfoHeader struct {
	size          uint32
	width         int32
	height        int32
	planes        uint16
	bitCount      uint16
	compression   uint32
	sizeImage     uint32
	xPelsPerMeter int32
	yPelsPerMeter int32
	clrUsed       uint32
	clrImportant  uint32
}

type point struct{ x, y int32 }

// grabber keeps its DC and DIB section between frames. Creating them costs
// more than the copy itself, and the size only changes when a monitor does.
var grabber struct {
	sync.Mutex
	dpiOnce sync.Once

	memDC  uintptr
	bitmap uintptr
	// bits is the DIB section's pixel memory. It is owned by GDI, not the Go
	// heap, so holding it as a pointer is safe and keeps vet's uintptr checks
	// meaningful.
	bits   unsafe.Pointer
	width  int
	height int
}

// beDPIAware makes the process see physical pixels. An unaware process on a
// 150% display is handed a virtual screen two-thirds the real size, and
// BitBlt would capture a scaled, blurred copy of it -- and absolute pointer
// moves computed from that size would miss by the same factor.
func beDPIAware() {
	if procSetProcessDpiAwarenessContext.Find() == nil {
		if r, _, _ := procSetProcessDpiAwarenessContext.Call(dpiAwarePerMonitorV2); r != 0 {
			return
		}
	}
	procSetProcessDPIAware.Call()
}

func virtualScreen() (x, y, w, h int) {
	metric := func(i uintptr) int {
		r, _, _ := procGetSystemMetrics.Call(i)
		return int(int32(r))
	}
	return metric(smXVirtualScreen), metric(smYVirtualScreen),
		metric(smCxVirtualScreen), metric(smCyVirtualScreen)
}

func screenSupported() bool { return procBitBlt.Find() == nil }

func releaseGrabberLocked() {
	if grabber.bitmap != 0 {
		procDeleteObject.Call(grabber.bitmap)
	}
	if grabber.memDC != 0 {
		procDeleteDC.Call(grabber.memDC)
	}
	grabber.bitmap, grabber.memDC, grabber.bits = 0, 0, nil
	grabber.width, grabber.height = 0, 0
}

func captureScreen() (*ScreenFrame, error) {
	grabber.Lock()
	defer grabber.Unlock()
	grabber.dpiOnce.Do(beDPIAware)

	vx, vy, vw, vh := virtualScreen()
	if vw <= 0 || vh <= 0 {
		return nil, errors.New("system: no desktop to capture")
	}

	screenDC, _, _ := procGetDC.Call(0)
	if screenDC == 0 {
		return nil, errors.New("system: GetDC failed")
	}
	defer procReleaseDC.Call(0, screenDC)

	if grabber.width != vw || grabber.height != vh || grabber.memDC == 0 {
		releaseGrabberLocked()
		memDC, _, _ := procCreateCompatibleDC.Call(screenDC)
		if memDC == 0 {
			return nil, errors.New("system: CreateCompatibleDC failed")
		}
		header := bitmapInfoHeader{
			size:     uint32(unsafe.Sizeof(bitmapInfoHeader{})),
			width:    int32(vw),
			height:   -int32(vh), // negative: top row first
			planes:   1,
			bitCount: 32,
		}
		var bits unsafe.Pointer
		bitmap, _, _ := procCreateDIBSection.Call(memDC, uintptr(unsafe.Pointer(&header)), 0,
			uintptr(unsafe.Pointer(&bits)), 0, 0)
		if bitmap == 0 || bits == nil {
			procDeleteDC.Call(memDC)
			return nil, errors.New("system: CreateDIBSection failed")
		}
		procSelectObject.Call(memDC, bitmap)
		grabber.memDC, grabber.bitmap, grabber.bits = memDC, bitmap, bits
		grabber.width, grabber.height = vw, vh
	}

	if r, _, err := procBitBlt.Call(grabber.memDC, 0, 0, uintptr(vw), uintptr(vh),
		screenDC, uintptr(int32(vx)), uintptr(int32(vy)), srcCopy|captureBlt); r == 0 {
		// The secure desktop (UAC prompt, lock screen) refuses the copy.
		return nil, fmt.Errorf("system: BitBlt: %w", err)
	}

	stride := vw * 4
	// Copied out: the DIB section is reused for the next frame while this one
	// may still be encoding on another goroutine.
	pix := make([]byte, stride*vh)
	copy(pix, unsafe.Slice((*byte)(grabber.bits), len(pix)))

	frame := &ScreenFrame{Width: vw, Height: vh, Stride: stride, Pix: pix, CursorX: -1, CursorY: -1}
	var cursor point
	if r, _, _ := procGetCursorPos.Call(uintptr(unsafe.Pointer(&cursor))); r != 0 {
		cx, cy := int(cursor.x)-vx, int(cursor.y)-vy
		if cx >= 0 && cy >= 0 && cx < vw && cy < vh {
			frame.CursorX, frame.CursorY = cx, cy
		}
	}
	return frame, nil
}

func pointerTo(nx, ny float64) error {
	grabber.dpiOnce.Do(beDPIAware)
	// Virtual-desk absolute coordinates run 0..65535 across the whole virtual
	// screen, the same span the capture covers, so the fraction maps straight
	// across without knowing where any monitor sits.
	x := int32(nx * 65535)
	y := int32(ny * 65535)
	return sendInputs(newMouseInput(x, y, 0, mouseEventMove|mouseEventAbsolute|mouseEventVirtualDesk))
}

// Virtual-key codes for the named editing keys, and whether each is an
// extended key -- the navigation cluster is, and without the flag a host with
// Num Lock on reads the arrows as keypad digits.
var keyVKs = map[string]struct {
	vk       uint16
	extended bool
}{
	protocol.KeyBackspace: {0x08, false},
	protocol.KeyDelete:    {0x2E, true},
	protocol.KeyEnter:     {0x0D, false},
	protocol.KeyTab:       {vkTab, false},
	protocol.KeyEscape:    {0x1B, false},
	protocol.KeyLeft:      {vkLeft, true},
	protocol.KeyRight:     {vkRight, true},
	protocol.KeyUp:        {0x26, true},
	protocol.KeyDown:      {0x28, true},
	protocol.KeyHome:      {0x24, true},
	protocol.KeyEnd:       {0x23, true},
	protocol.KeyPageUp:    {0x21, true},
	protocol.KeyPageDown:  {0x22, true},
}

func pressKey(name string) error {
	key, ok := keyVKs[name]
	if !ok {
		return fmt.Errorf("input: unknown key %q", name)
	}
	down, up := newKeyInput(key.vk, false), newKeyInput(key.vk, true)
	if key.extended {
		(*keyboardInput)(unsafe.Pointer(&down.data[0])).flags |= keyEventExtended
		(*keyboardInput)(unsafe.Pointer(&up.data[0])).flags |= keyEventExtended
	}
	return sendInputs(down, up)
}
