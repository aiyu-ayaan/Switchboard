//go:build windows

package system

import (
	"errors"
	"testing"
	"time"
)

// TestTTLCacheServesRepeats proves the COM walk is skipped inside the TTL and
// re-run once it lapses; without that, every one-second host snapshot would
// enumerate Core Audio afresh.
func TestTTLCacheServesRepeats(t *testing.T) {
	const ttl = 20 * time.Millisecond
	calls := 0
	load := func() ([]string, error) {
		calls++
		return []string{"a"}, nil
	}

	var c ttlCache[[]string]
	for i := 0; i < 3; i++ {
		if _, err := c.get(ttl, load); err != nil {
			t.Fatalf("get: %v", err)
		}
	}
	if calls != 1 {
		t.Errorf("loaded %d times inside the TTL, want 1", calls)
	}

	time.Sleep(ttl + 10*time.Millisecond)
	if _, err := c.get(ttl, load); err != nil {
		t.Fatalf("get: %v", err)
	}
	if calls != 2 {
		t.Errorf("loaded %d times after the TTL lapsed, want 2", calls)
	}

	// A write seeds the cache directly, so the next read must not reload.
	c.store([]string{"b"})
	got, err := c.get(ttl, load)
	if err != nil {
		t.Fatalf("get: %v", err)
	}
	if calls != 2 {
		t.Errorf("stored value did not suppress the reload: %d calls", calls)
	}
	if len(got) != 1 || got[0] != "b" {
		t.Errorf("cache served %+v, want the stored value", got)
	}
}

// TestTTLCacheInvalidateForcesReload covers the audio-output write path: the
// endpoint moved, so the cached list still marks the old default and must not
// be served for the rest of its TTL.
func TestTTLCacheInvalidateForcesReload(t *testing.T) {
	calls := 0
	load := func() ([]string, error) {
		calls++
		return []string{"a"}, nil
	}

	var c ttlCache[[]string]
	if _, err := c.get(time.Minute, load); err != nil {
		t.Fatalf("get: %v", err)
	}
	c.invalidate()
	if _, err := c.get(time.Minute, load); err != nil {
		t.Fatalf("get: %v", err)
	}
	if calls != 2 {
		t.Errorf("loaded %d times across an invalidate, want 2", calls)
	}
}

// TestTTLCacheReloadsAfterError keeps a transient COM failure from being
// remembered as an empty result for the rest of the TTL.
func TestTTLCacheReloadsAfterError(t *testing.T) {
	var c ttlCache[[]string]
	if _, err := c.get(time.Minute, func() ([]string, error) {
		return nil, errors.New("boom")
	}); err == nil {
		t.Fatal("expected the load error to surface")
	}

	calls := 0
	if _, err := c.get(time.Minute, func() ([]string, error) {
		calls++
		return []string{}, nil
	}); err != nil {
		t.Fatalf("get: %v", err)
	}
	if calls != 1 {
		t.Error("a failed load was cached instead of retried")
	}
}
