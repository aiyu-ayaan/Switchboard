//go:build windows

package system

import (
	"sync"
	"time"
)

// ttlCache serves repeat reads from the last load.
//
// The host snapshot is reassembled every time the media watcher ticks (once a
// second) and again on every broadcast. Every Core Audio read behind it walks
// COM, activating interfaces per session or opening a property store per
// endpoint, so without this an idle daemon would spend its time enumerating
// audio devices. Writes invalidate, so a slider drag never reads back the
// level it just replaced.
type ttlCache[T any] struct {
	mu        sync.Mutex
	fetchedAt time.Time
	value     T
}

func (c *ttlCache[T]) get(ttl time.Duration, load func() (T, error)) (T, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.fetchedAt.IsZero() && time.Since(c.fetchedAt) < ttl {
		return c.value, nil
	}
	value, err := load()
	if err != nil {
		var zero T
		return zero, err
	}
	c.value, c.fetchedAt = value, time.Now()
	return value, nil
}

// store seeds the cache with a value the caller already has, so a write that
// read the new state back does not force the next snapshot to walk COM again.
func (c *ttlCache[T]) store(value T) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.value, c.fetchedAt = value, time.Now()
}

// invalidate forces the next read to reload. Used when a write changed
// something the cached value describes but does not itself contain.
func (c *ttlCache[T]) invalidate() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.fetchedAt = time.Time{}
}
