//go:build windows

package system

import (
	"testing"
)

func TestPowerSupported(t *testing.T) {
	if !powerSupported() {
		t.Fatal("powerSupported() = false on windows, want true")
	}
}

func TestPowerActionInvalid(t *testing.T) {
	if err := powerAction("invalid_action", 0); err == nil {
		t.Fatal("expected error for invalid power action")
	}
}
