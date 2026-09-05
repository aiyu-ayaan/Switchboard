package camera

import (
	"errors"
	"sync"
	"testing"
	"time"

	"switchboard/backend/internal/protocol"
)

const testDevice = "phone-1"

// sink records what the hub asked the phone to do.
type sink struct {
	mu      sync.Mutex
	actions []string
}

func (s *sink) send(_, action string, _ any, _ []byte) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.actions = append(s.actions, action)
	return nil
}

func (s *sink) saw(action string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, a := range s.actions {
		if a == action {
			return true
		}
	}
	return false
}

func newHub(t *testing.T) (*Hub, *sink) {
	t.Helper()
	s := &sink{}
	h := NewHub(s.send)
	if err := h.Start(testDevice, protocol.DefaultCameraSettings()); err != nil {
		t.Fatal(err)
	}
	return h, s
}

// The hub keeps the newest frame, not a backlog. A viewer that falls behind
// wants the current picture; queueing would trade memory and latency for
// footage nobody is going to watch.
func TestOnlyTheNewestFrameIsKept(t *testing.T) {
	h, _ := newHub(t)

	for i := 1; i <= 50; i++ {
		h.Frame(testDevice, protocol.CameraFrame{Width: 1280, Height: 720}, []byte{byte(i)})
	}

	frame, seq, err := h.Await(0, time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if len(frame) != 1 || frame[0] != 50 {
		t.Fatalf("got frame %v, want the 50th: the hub queued instead of replacing", frame)
	}
	if seq != 50 {
		t.Fatalf("sequence = %d, want 50", seq)
	}
}

// A caller that already has the current frame must block rather than spin,
// and must be released the moment a new one lands.
func TestAwaitBlocksUntilANewerFrameArrives(t *testing.T) {
	h, _ := newHub(t)
	h.Frame(testDevice, protocol.CameraFrame{}, []byte{1})

	released := make(chan int64, 1)
	go func() {
		_, seq, err := h.Await(1, 5*time.Second)
		if err != nil {
			released <- -1
			return
		}
		released <- seq
	}()

	select {
	case seq := <-released:
		t.Fatalf("Await returned at sequence %d before a new frame existed", seq)
	case <-time.After(100 * time.Millisecond):
	}

	h.Frame(testDevice, protocol.CameraFrame{}, []byte{2})
	select {
	case seq := <-released:
		if seq != 2 {
			t.Fatalf("released at sequence %d, want 2", seq)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("a new frame did not release the waiter")
	}
}

// Without a deadline a waiter on a camera pointed at a still wall would never
// return, and the caller would never get a turn to notice its client left.
func TestAwaitGivesUpOnAStillCamera(t *testing.T) {
	h, _ := newHub(t)
	h.Frame(testDevice, protocol.CameraFrame{}, []byte{1})

	start := time.Now()
	if _, _, err := h.Await(1, 150*time.Millisecond); !errors.Is(err, ErrNoNewFrame) {
		t.Fatalf("err = %v, want ErrNoNewFrame", err)
	}
	if elapsed := time.Since(start); elapsed > 2*time.Second {
		t.Fatalf("Await took %v to give up", elapsed)
	}
}

func TestStopReleasesWaiters(t *testing.T) {
	h, s := newHub(t)
	h.Frame(testDevice, protocol.CameraFrame{}, []byte{1})

	released := make(chan error, 1)
	go func() {
		_, _, err := h.Await(1, 5*time.Second)
		released <- err
	}()
	time.Sleep(50 * time.Millisecond)

	if err := h.Stop(); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-released:
		if !errors.Is(err, ErrNotStreaming) {
			t.Fatalf("err = %v, want ErrNotStreaming", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("stopping the stream left a waiter hanging")
	}
	if !s.saw(protocol.ActionCameraStop) {
		t.Fatal("the phone was never told to release its camera")
	}
}

// A phone that walks out of Wi-Fi sends no goodbye, so silence is the only
// evidence the camera is gone. Reporting it as live indefinitely would leave
// the desktop showing a frozen picture it believes is current.
func TestSilenceIsReportedRatherThanShownAsLive(t *testing.T) {
	h, _ := newHub(t)
	h.Frame(testDevice, protocol.CameraFrame{}, []byte{1})

	if !h.State().Streaming {
		t.Fatal("a stream with a fresh frame should report as live")
	}

	h.mu.Lock()
	h.frameAt = time.Now().Add(-2 * staleAfter)
	h.mu.Unlock()

	state := h.State()
	if state.Streaming {
		t.Fatal("a stream with no recent frames still reports as live")
	}
	if state.Error == "" {
		t.Fatal("a stalled stream reported no reason")
	}
}

// Frames from a phone that is no longer the source are the tail of a stream
// that was switched away. Accepting them would splice two rooms together.
func TestFramesFromAnOldSourceAreDropped(t *testing.T) {
	h, _ := newHub(t)
	h.Frame(testDevice, protocol.CameraFrame{}, []byte{1})

	if err := h.Start("phone-2", protocol.DefaultCameraSettings()); err != nil {
		t.Fatal(err)
	}
	h.Frame(testDevice, protocol.CameraFrame{}, []byte{99})

	if _, _, err := h.Await(0, 100*time.Millisecond); !errors.Is(err, ErrNoNewFrame) {
		t.Fatal("a frame from the previous source was accepted into the new stream")
	}
}

func TestDisconnectClearsTheStream(t *testing.T) {
	h, _ := newHub(t)
	h.Frame(testDevice, protocol.CameraFrame{}, []byte{1})

	h.Detach(testDevice)
	if state := h.State(); state.Streaming {
		t.Fatal("a disconnected device still reports as streaming")
	}
	if _, _, err := h.Await(0, time.Second); !errors.Is(err, ErrNotStreaming) {
		t.Fatalf("err = %v, want ErrNotStreaming", err)
	}
}
