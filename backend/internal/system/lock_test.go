package system

import (
	"runtime"
	"testing"
)

func TestLockCapability(t *testing.T) {
	c := NewController()
	defer c.Close()

	state := c.State("test-daemon")

	hasLockCap := false
	for _, cap := range state.Capabilities {
		if cap == "lock" {
			hasLockCap = true
			break
		}
	}

	if runtime.GOOS == "windows" {
		if !lockSupported() {
			t.Errorf("lockSupported() = false on windows, want true")
		}
		if !hasLockCap {
			t.Errorf("state.Capabilities missing 'lock' on windows: %v", state.Capabilities)
		}
	} else {
		if lockSupported() {
			t.Errorf("lockSupported() = true on %s, want false", runtime.GOOS)
		}
		if hasLockCap {
			t.Errorf("state.Capabilities unexpectedly includes 'lock' on %s", runtime.GOOS)
		}
	}
}
