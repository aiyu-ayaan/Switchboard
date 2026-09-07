//go:build windows

package system

import (
	"encoding/binary"
	"fmt"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	procLockWorkStation  = user32.NewProc("LockWorkStation")
	procOpenInputDesktop = user32.NewProc("OpenInputDesktop")
	procCloseDesktop     = user32.NewProc("CloseDesktop")

	wtsapi32                 = windows.NewLazySystemDLL("wtsapi32.dll")
	procWTSQuerySessionInfoW = wtsapi32.NewProc("WTSQuerySessionInformationW")
	procWTSFreeMemory        = wtsapi32.NewProc("WTSFreeMemory")
)

const (
	// wtsSessionInfoEx is WTS_INFO_CLASS.WTSSessionInfoEx, the only class that
	// reports lock state.
	wtsSessionInfoEx = 25
	// wtsCurrentSession asks about the caller's own session, which is the
	// console session the daemon runs in.
	wtsCurrentSession = ^uint32(0)

	// sessionFlagsOffset locates WTSINFOEX_LEVEL1.SessionFlags inside WTSINFOEXW.
	// The struct is { DWORD Level; union { WTSINFOEX_LEVEL1_W } Data; }, and the
	// LARGE_INTEGERs inside Level1 align the union to 8, so Data starts at 8 and
	// SessionFlags sits 8 further in, past SessionId and SessionState.
	sessionFlagsOffset = 16

	// wtsSessionStateLock is WTS_SESSIONSTATE_LOCK.
	wtsSessionStateLock = 0
)

// lockSystem locks the host workstation display.
func lockSystem() error {
	r1, _, err := procLockWorkStation.Call()
	if r1 == 0 {
		return fmt.Errorf("system: LockWorkStation failed: %w", err)
	}
	return nil
}

// isLocked reports whether the console session is locked.
//
// It asks the terminal-services session directly rather than probing the input
// desktop. Pressing Win+L does not hand the desktop to Winlogon straight away:
// LockApp — the clock and wallpaper — runs as the user on the *interactive*
// desktop, and only the credential UI behind it lives on the secure desktop. A
// desktop probe therefore reports "unlocked" for the whole first screen, which
// is exactly when a phone is asked to open the machine.
//
// The session flag is set the moment the session locks, so it covers both
// stages. It also stays put during a UAC prompt, which the desktop probe read
// as a lock.
func isLocked() bool {
	var buf *byte
	var size uint32
	r1, _, _ := procWTSQuerySessionInfoW.Call(
		0, // WTS_CURRENT_SERVER_HANDLE
		uintptr(wtsCurrentSession),
		wtsSessionInfoEx,
		uintptr(unsafe.Pointer(&buf)),
		uintptr(unsafe.Pointer(&size)),
	)
	if r1 == 0 || buf == nil || size < sessionFlagsOffset+4 {
		return isLockedByDesktop()
	}
	defer procWTSFreeMemory.Call(uintptr(unsafe.Pointer(buf)))

	info := unsafe.Slice(buf, size)
	flags := int32(binary.LittleEndian.Uint32(info[sessionFlagsOffset:]))
	return flags == wtsSessionStateLock
}

// isLockedByDesktop is the fallback: OpenInputDesktop fails while the secure
// desktop is up. It misses the lock screen's first stage, so it is only used
// when the session query is unavailable.
func isLockedByDesktop() bool {
	hDesk, _, _ := procOpenInputDesktop.Call(0, 0, 0x0100) // DESKTOP_SWITCHDESKTOP
	if hDesk == 0 {
		return true
	}
	procCloseDesktop.Call(hDesk)
	return false
}

func lockSupported() bool {
	return true
}
