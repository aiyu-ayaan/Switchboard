package system

import (
	"runtime"
	"testing"
)

func TestKeyboardAndClipboardSupported(t *testing.T) {
	if runtime.GOOS == "windows" {
		if !keyboardSupported() {
			t.Error("keyboardSupported() = false on windows, want true")
		}
		if !clipboardSupported() {
			t.Error("clipboardSupported() = false on windows, want true")
		}
	} else {
		if keyboardSupported() {
			t.Error("keyboardSupported() = true on non-windows, want false")
		}
		if clipboardSupported() {
			t.Error("clipboardSupported() = true on non-windows, want false")
		}
	}
}

func TestInputTextEmpty(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("windows only")
	}
	if err := inputText(""); err != nil {
		t.Fatalf("inputText(\"\") failed: %v", err)
	}
}

func TestSetClipboard(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("windows only")
	}
	testStr := "switchboard-test-clipboard-12345"
	if err := setClipboard(testStr); err != nil {
		t.Fatalf("setClipboard failed: %v", err)
	}
}
