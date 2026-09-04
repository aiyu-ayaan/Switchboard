//go:build windows

package system

import (
	"errors"
	"testing"
	"time"

	"switchboard/backend/internal/protocol"
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

// TestMixerCacheServesRepeats proves the COM walk is skipped inside the TTL
// and re-run once it lapses; without that, every one-second host snapshot
// would enumerate Core Audio afresh.
func TestMixerCacheServesRepeats(t *testing.T) {
	calls := 0
	load := func() ([]protocol.AudioSession, error) {
		calls++
		return []protocol.AudioSession{{ID: "a"}}, nil
	}

	c := mixerCache{ttl: 20 * time.Millisecond}
	for i := 0; i < 3; i++ {
		if _, err := c.get(load); err != nil {
			t.Fatalf("get: %v", err)
		}
	}
	if calls != 1 {
		t.Errorf("loaded %d times inside the TTL, want 1", calls)
	}

	time.Sleep(30 * time.Millisecond)
	if _, err := c.get(load); err != nil {
		t.Fatalf("get: %v", err)
	}
	if calls != 2 {
		t.Errorf("loaded %d times after the TTL lapsed, want 2", calls)
	}

	// A write seeds the cache directly, so the next read must not walk COM.
	c.store([]protocol.AudioSession{{ID: "b", Level: 10}})
	got, err := c.get(load)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if calls != 2 {
		t.Errorf("stored sessions did not suppress the reload: %d calls", calls)
	}
	if len(got) != 1 || got[0].ID != "b" {
		t.Errorf("cache served %+v, want the stored session", got)
	}
}

// TestMixerCacheReloadsAfterError keeps a transient COM failure from being
// remembered as an empty mixer for the rest of the TTL.
func TestMixerCacheReloadsAfterError(t *testing.T) {
	c := mixerCache{ttl: time.Minute}
	if _, err := c.get(func() ([]protocol.AudioSession, error) {
		return nil, errors.New("boom")
	}); err == nil {
		t.Fatal("expected the load error to surface")
	}

	calls := 0
	if _, err := c.get(func() ([]protocol.AudioSession, error) {
		calls++
		return []protocol.AudioSession{}, nil
	}); err != nil {
		t.Fatalf("get: %v", err)
	}
	if calls != 1 {
		t.Error("a failed load was cached instead of retried")
	}
}
