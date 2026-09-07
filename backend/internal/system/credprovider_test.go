//go:build windows

package system

// The Go side and the credential provider DLL agree on two strings by
// convention alone: the CLSID that registration writes and COM looks up, and
// the name of the event the daemon sets. Neither side can detect a mismatch at
// runtime -- registration would succeed against a CLSID nothing implements,
// and SetEvent would signal an event nothing waits on -- so the failure would
// be "unlock silently does nothing", which is the worst kind to debug.
//
// These tests read the C++ and check it still says what Go thinks it says.

import (
	"fmt"
	"os"
	"regexp"
	"strings"
	"testing"
)

const (
	dllMainPath = "../../../native/credprovider/dllmain.cpp"
	headerPath  = "../../../native/credprovider/switchboard_cp.h"
)

// TestCredProviderCLSIDMatchesDLL rebuilds the GUID from the C++ initialiser
// and compares it with the string registration writes.
func TestCredProviderCLSIDMatchesDLL(t *testing.T) {
	source := readSource(t, dllMainPath)

	// const CLSID CLSID_SwitchboardProvider =
	//     { 0x599ba444, 0x2560, 0x4520, { 0xab, 0x9e, ... } };
	re := regexp.MustCompile(`CLSID_SwitchboardProvider\s*=\s*\{\s*(0x[0-9a-fA-F]+)\s*,\s*(0x[0-9a-fA-F]+)\s*,\s*(0x[0-9a-fA-F]+)\s*,\s*\{([^}]*)\}`)
	m := re.FindStringSubmatch(source)
	if m == nil {
		t.Fatalf("could not find the CLSID initialiser in %s", dllMainPath)
	}

	var d1 uint32
	var d2, d3 uint16
	mustScan(t, m[1], &d1)
	mustScan16(t, m[2], &d2)
	mustScan16(t, m[3], &d3)

	var d4 [8]byte
	parts := strings.Split(m[4], ",")
	if len(parts) != 8 {
		t.Fatalf("CLSID has %d trailing bytes, want 8", len(parts))
	}
	for i, p := range parts {
		var b uint32
		mustScan(t, strings.TrimSpace(p), &b)
		d4[i] = byte(b)
	}

	got := strings.ToUpper(fmt.Sprintf("{%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x}",
		d1, d2, d3, d4[0], d4[1], d4[2], d4[3], d4[4], d4[5], d4[6], d4[7]))

	if got != credProviderCLSID {
		t.Errorf("CLSID mismatch:\n  dllmain.cpp: %s\n  Go constant: %s", got, credProviderCLSID)
	}
}

// TestUnlockEventMatchesDLL checks the name the daemon opens against the one
// the DLL creates. C++ escapes the backslash; Go's raw string does not.
func TestUnlockEventMatchesDLL(t *testing.T) {
	source := readSource(t, headerPath)

	re := regexp.MustCompile(`#define\s+SWITCHBOARD_UNLOCK_EVENT\s+L"([^"]*)"`)
	m := re.FindStringSubmatch(source)
	if m == nil {
		t.Fatalf("could not find SWITCHBOARD_UNLOCK_EVENT in %s", headerPath)
	}

	got := strings.ReplaceAll(m[1], `\\`, `\`)
	if got != unlockEvent {
		t.Errorf("event name mismatch:\n  switchboard_cp.h: %q\n  Go constant:      %q", got, unlockEvent)
	}
}

func readSource(t *testing.T, path string) string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		// The DLL is a Windows component; a checkout that dropped it should
		// fail loudly rather than quietly skip the only check on these two.
		t.Fatalf("reading %s: %v", path, err)
	}
	return string(data)
}

func mustScan(t *testing.T, hex string, out *uint32) {
	t.Helper()
	if _, err := fmt.Sscanf(hex, "0x%x", out); err != nil {
		t.Fatalf("parsing %q: %v", hex, err)
	}
}

func mustScan16(t *testing.T, hex string, out *uint16) {
	t.Helper()
	if _, err := fmt.Sscanf(hex, "0x%x", out); err != nil {
		t.Fatalf("parsing %q: %v", hex, err)
	}
}
