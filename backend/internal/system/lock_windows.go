//go:build windows

package system

import (
	"fmt"
)

var (
	procLockWorkStation  = user32.NewProc("LockWorkStation")
	procOpenInputDesktop = user32.NewProc("OpenInputDesktop")
	procCloseDesktop     = user32.NewProc("CloseDesktop")
)

// lockSystem locks the host workstation display.
func lockSystem() error {
	r1, _, err := procLockWorkStation.Call()
	if r1 == 0 {
		return fmt.Errorf("system: LockWorkStation failed: %w", err)
	}
	return nil
}

// isLocked checks if the input desktop is currently locked.
// OpenInputDesktop fails when the workstation is locked or running a secure desktop (UAC/Winlogon).
func isLocked() bool {
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
