//go:build windows

package system

import (
	"fmt"
)

var procLockWorkStation = user32.NewProc("LockWorkStation")

// lockSystem locks the host workstation display.
func lockSystem() error {
	r1, _, err := procLockWorkStation.Call()
	if r1 == 0 {
		return fmt.Errorf("system: LockWorkStation failed: %w", err)
	}
	return nil
}

func lockSupported() bool {
	return true
}
