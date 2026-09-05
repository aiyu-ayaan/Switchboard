package transfer

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"switchboard/backend/internal/protocol"
)

// TestSendQueueCapsConcurrency proves a batch offers maxParallelSends files
// and holds the rest back.
//
// Dropping a folder used to offer every file at once: each pump then crawled
// against the others on the single socket, and the phone's one frame reader
// serialised them, which is what made a bar advance in bursts. The peer here
// never accepts, so every offer that goes out is one still occupying a slot.
func TestSendQueueCapsConcurrency(t *testing.T) {
	dir := t.TempDir()

	var mu sync.Mutex
	offered := []string{}
	m := NewManager(
		func() string { return dir },
		func(_, action string, payload any, _ []byte) error {
			if action == protocol.ActionFileOffer {
				mu.Lock()
				offered = append(offered, payload.(protocol.FileOffer).TransferID)
				mu.Unlock()
			}
			return nil
		},
		func(Event) {},
	)

	const files = maxParallelSends + 3
	for i := 0; i < files; i++ {
		path := filepath.Join(dir, fmt.Sprintf("f%d.bin", i))
		if err := os.WriteFile(path, []byte("payload"), 0o600); err != nil {
			t.Fatal(err)
		}
		if _, err := m.Send(testDevice, path); err != nil {
			t.Fatal(err)
		}
	}

	count := func() int {
		mu.Lock()
		defer mu.Unlock()
		return len(offered)
	}
	await := func(want int) {
		t.Helper()
		deadline := time.Now().Add(2 * time.Second)
		for time.Now().Before(deadline) {
			if count() >= want {
				return
			}
			time.Sleep(5 * time.Millisecond)
		}
		t.Fatalf("only %d of %d offers went out", count(), want)
	}

	await(maxParallelSends)
	// The queue is given room to overrun before the cap is trusted; asserting
	// the moment the fourth arrives would pass even if the fifth were racing.
	time.Sleep(150 * time.Millisecond)
	if got := count(); got != maxParallelSends {
		t.Fatalf("offers in flight = %d, want the cap of %d", got, maxParallelSends)
	}

	mu.Lock()
	first := offered[0]
	mu.Unlock()
	if err := m.Control(protocol.FileControl{
		TransferID: first, Action: protocol.ControlCancel,
	}); err != nil {
		t.Fatal(err)
	}

	await(maxParallelSends + 1)
	time.Sleep(150 * time.Millisecond)
	if got := count(); got != maxParallelSends+1 {
		t.Fatalf("one finished transfer admitted %d more, want 1", got-maxParallelSends)
	}
}
