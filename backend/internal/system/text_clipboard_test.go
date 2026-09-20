package system

import (
	"runtime"
	"testing"
)

// TestKeyboardAndClipboardSupported pins which hosts claim which capability.
//
// The two used to travel together because only Windows had either. They no
// longer do: Linux types through XTEST (input_linux.go) but has no clipboard
// backend, since an X selection lives in the owning client and needs the
// daemon to stay running and answer for it.
func TestKeyboardAndClipboardSupported(t *testing.T) {
	switch runtime.GOOS {
	case "windows":
		if !keyboardSupported() {
			t.Error("keyboardSupported() = false on windows, want true")
		}
		if !clipboardSupported() {
			t.Error("clipboardSupported() = false on windows, want true")
		}
	case "linux":
		// Typing needs a display. A daemon on a headless box or at a text
		// console has none, and must report the capability as absent rather
		// than offering a keyboard that errors on every keystroke.
		if keyboardSupported() != inputSupported() {
			t.Errorf("keyboardSupported() = %v but inputSupported() = %v: the two share the XTEST connection",
				keyboardSupported(), inputSupported())
		}
		if clipboardSupported() {
			t.Error("clipboardSupported() = true on linux, but there is no backend")
		}
	default:
		if keyboardSupported() {
			t.Errorf("keyboardSupported() = true on %s, want false", runtime.GOOS)
		}
		if clipboardSupported() {
			t.Errorf("clipboardSupported() = true on %s, want false", runtime.GOOS)
		}
	}
}

func TestInputTextEmpty(t *testing.T) {
	if !keyboardSupported() {
		t.Skipf("no keyboard injection on %s", runtime.GOOS)
	}
	if err := inputText(""); err != nil {
		t.Fatalf("inputText(\"\") failed: %v", err)
	}
}

func TestSetClipboard(t *testing.T) {
	if !clipboardSupported() {
		t.Skipf("no clipboard backend on %s", runtime.GOOS)
	}
	testStr := "switchboard-test-clipboard-12345"
	if err := setClipboard(testStr); err != nil {
		t.Fatalf("setClipboard failed: %v", err)
	}
}
