//go:build windows

package system

import (
	"fmt"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	kernel32 = windows.NewLazySystemDLL("kernel32.dll")

	procOpenClipboard    = user32.NewProc("OpenClipboard")
	procCloseClipboard   = user32.NewProc("CloseClipboard")
	procEmptyClipboard   = user32.NewProc("EmptyClipboard")
	procSetClipboardData = user32.NewProc("SetClipboardData")

	procGlobalAlloc  = kernel32.NewProc("GlobalAlloc")
	procGlobalFree   = kernel32.NewProc("GlobalFree")
	procGlobalLock   = kernel32.NewProc("GlobalLock")
	procGlobalUnlock = kernel32.NewProc("GlobalUnlock")
)

const (
	gmemMoveable  = 0x0002
	cfUnicodeText = 13
)

func clipboardSupported() bool {
	return true
}

func setClipboard(text string) error {
	u16, err := windows.UTF16FromString(text)
	if err != nil {
		return fmt.Errorf("clipboard: invalid text: %w", err)
	}

	bytesLen := len(u16) * 2
	hMem, _, err := procGlobalAlloc.Call(uintptr(gmemMoveable), uintptr(bytesLen))
	if hMem == 0 {
		return fmt.Errorf("clipboard: GlobalAlloc failed: %w", err)
	}

	ptr, _, err := procGlobalLock.Call(hMem)
	if ptr == 0 {
		procGlobalFree.Call(hMem)
		return fmt.Errorf("clipboard: GlobalLock failed: %w", err)
	}

	dest := unsafe.Slice((*uint16)(unsafe.Pointer(ptr)), len(u16))
	copy(dest, u16)
	procGlobalUnlock.Call(hMem)

	var openErr error
	opened := false
	for i := 0; i < 10; i++ {
		r1, _, callErr := procOpenClipboard.Call(0)
		if r1 != 0 {
			opened = true
			break
		}
		openErr = callErr
		time.Sleep(10 * time.Millisecond)
	}
	if !opened {
		procGlobalFree.Call(hMem)
		return fmt.Errorf("clipboard: OpenClipboard failed: %w", openErr)
	}
	defer procCloseClipboard.Call()

	if r1, _, callErr := procEmptyClipboard.Call(); r1 == 0 {
		procGlobalFree.Call(hMem)
		return fmt.Errorf("clipboard: EmptyClipboard failed: %w", callErr)
	}

	r, _, callErr := procSetClipboardData.Call(uintptr(cfUnicodeText), hMem)
	if r == 0 {
		procGlobalFree.Call(hMem)
		return fmt.Errorf("clipboard: SetClipboardData failed: %w", callErr)
	}

	return nil
}
