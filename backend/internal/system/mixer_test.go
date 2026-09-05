//go:build windows

package system

import (
	"errors"
	"testing"
)

// TestMixerSessions exercises the real Core Audio path. It asserts the
// invariants a mixer UI depends on rather than any particular program, and
// skips on a host with nothing playing (CI runners have no audio sessions).
func TestMixerSessions(t *testing.T) {
	c := NewController()
	defer c.Close()

	sessions, err := c.Mixer()
	if errors.Is(err, ErrUnsupported) {
		t.Skip("per-application mixer not implemented on this platform yet")
	}
	if err != nil {
		t.Skipf("no audio endpoint available here: %v", err)
	}
	if len(sessions) == 0 {
		t.Skip("no audio sessions on this host")
	}

	seen := map[string]bool{}
	for _, s := range sessions {
		t.Logf("%s (pid=%d) level=%d muted=%v active=%v id=%s",
			s.Name, s.PID, s.Level, s.Muted, s.Active, s.ID)

		if s.ID == "" {
			t.Errorf("session has empty id: %+v", s)
		}
		if s.Name == "" {
			t.Errorf("session %s has no label, which renders as a blank mixer row", s.ID)
		}
		if s.Level < 0 || s.Level > 100 {
			t.Errorf("%s: level %d outside 0..100", s.Name, s.Level)
		}
		if seen[s.ID] {
			t.Errorf("%s: duplicate session id %s", s.Name, s.ID)
		}
		seen[s.ID] = true
	}
}

func TestTidyExeName(t *testing.T) {
	cases := map[string]string{
		"chrome.exe":  "Chrome",
		"Spotify.EXE": "Spotify",
		"Discord.exe": "Discord",
		"vlc":         "Vlc",
		".exe":        "",
		"":            "",
	}
	for in, want := range cases {
		if got := tidyExeName(in); got != want {
			t.Errorf("tidyExeName(%q) = %q, want %q", in, got, want)
		}
	}
}

// TestSetSessionVolumeRejectsEmptyID guards the write path: an empty id would
// otherwise match no session and silently walk the whole mixer for nothing.
func TestSetSessionVolumeRejectsEmptyID(t *testing.T) {
	if _, err := setSessionVolume("", 50, false); err == nil {
		t.Fatal("expected an error for an empty session id")
	}
}
