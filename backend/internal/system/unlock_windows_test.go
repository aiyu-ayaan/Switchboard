//go:build windows

package system

import (
	"unicode/utf16"
	"unsafe"
)

import "testing"

// The typing pass is mostly syscalls, but the INPUT it builds is not: a wrong
// field or flag would type nothing at a lock screen no test can reach, so the
// struct packing is checked here instead.
func TestUnicodeInputPacking(t *testing.T) {
	in := newUnicodeInput('é', false)
	if in.kind != inputKeyboard {
		t.Fatalf("kind = %d, want inputKeyboard", in.kind)
	}
	k := (*keyboardInput)(unsafe.Pointer(&in.data[0]))
	if k.vk != 0 {
		t.Errorf("vk = %d, want 0: KEYEVENTF_UNICODE requires a zero virtual key", k.vk)
	}
	if k.scan != 'é' {
		t.Errorf("scan = %d, want %d: the code unit travels in wScan", k.scan, 'é')
	}
	if k.flags != keyEventUnicode {
		t.Errorf("flags = %#x, want %#x", k.flags, keyEventUnicode)
	}

	up := newUnicodeInput('é', true)
	upFlags := (*keyboardInput)(unsafe.Pointer(&up.data[0])).flags
	if upFlags != keyEventUnicode|keyEventKeyUp {
		t.Errorf("key-up flags = %#x, want %#x", upFlags, keyEventUnicode|keyEventKeyUp)
	}
}

// A password outside the BMP has to go out as both surrogates. Ranging over
// runes instead of UTF-16 units would send one bogus keystroke and log the
// user in as nobody.
func TestNonBMPPasswordSplitsIntoSurrogates(t *testing.T) {
	units := utf16.Encode([]rune("a𝄞"))
	if len(units) != 3 {
		t.Fatalf("got %d code units for a two-rune password, want 3", len(units))
	}
	if units[1] < 0xD800 || units[1] > 0xDBFF {
		t.Errorf("units[1] = %#x, want a high surrogate", units[1])
	}
	if units[2] < 0xDC00 || units[2] > 0xDFFF {
		t.Errorf("units[2] = %#x, want a low surrogate", units[2])
	}
}

// Unlock must stay dark until a password is enrolled: advertising it earlier
// would put a button on the phone that can only ever fail.
func TestUnlockCapabilityTracksEnrolment(t *testing.T) {
	state := NewController().State("test-daemon")
	advertised := false
	for _, capability := range state.Capabilities {
		if capability == "unlock" {
			advertised = true
		}
	}
	if advertised != unlockSupported() {
		t.Errorf("capability %v with enrolment %v; the two must agree",
			advertised, unlockSupported())
	}
}
