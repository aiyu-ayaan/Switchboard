package mirror

import (
	"errors"
	"sync"
	"testing"
	"time"

	"switchboard/backend/internal/protocol"
)

type recorder struct {
	mu      sync.Mutex
	actions []string
}

func (r *recorder) send(_ string, action string, _ any, _ []byte) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.actions = append(r.actions, action)
	return nil
}

func (r *recorder) count(action string) int {
	r.mu.Lock()
	defer r.mu.Unlock()
	n := 0
	for _, a := range r.actions {
		if a == action {
			n++
		}
	}
	return n
}

func feed(h *Hub, device string, key bool, b byte) {
	h.Frame(device, protocol.MirrorFrame{Key: key, Width: 1080, Height: 2400}, []byte{b})
}

func TestViewerJoinsAtNewestKeyframeAndReadsInOrder(t *testing.T) {
	rec := &recorder{}
	h := NewHub(rec.send)
	h.Start("phone", protocol.MirrorSettings{})

	feed(h, "phone", true, 1)
	feed(h, "phone", false, 2)
	feed(h, "phone", true, 3)
	feed(h, "phone", false, 4)
	feed(h, "phone", false, 5)

	u, err := h.next(0, time.Second)
	if err != nil || u.data[0] != 3 {
		t.Fatalf("join: got %v %v, want the newest keyframe (3)", u.data, err)
	}
	for _, want := range []byte{4, 5} {
		u, err = h.next(u.seq, time.Second)
		if err != nil || u.data[0] != want {
			t.Fatalf("got %v %v, want %d", u.data, err, want)
		}
	}
	// Caught up: waits, and gives up at the deadline.
	if _, err := h.next(u.seq, 50*time.Millisecond); !errors.Is(err, errNoUnit) {
		t.Fatalf("caught-up viewer: %v", err)
	}
	if st := h.State(); !st.Streaming || st.AwaitingConsent || st.Width != 1080 {
		t.Fatalf("state %+v", st)
	}
}

func TestJoiningWithoutKeyframeAsksForOne(t *testing.T) {
	rec := &recorder{}
	h := NewHub(rec.send)
	h.Start("phone", protocol.MirrorSettings{})
	feed(h, "phone", false, 1)

	done := make(chan unit, 1)
	go func() {
		u, _ := h.next(0, 2*time.Second)
		done <- u
	}()
	deadline := time.Now().Add(time.Second)
	for rec.count(protocol.ActionMirrorKeyframe) == 0 {
		if time.Now().After(deadline) {
			t.Fatal("no keyframe requested")
		}
		time.Sleep(5 * time.Millisecond)
	}
	feed(h, "phone", true, 9)
	if u := <-done; len(u.data) == 0 || u.data[0] != 9 {
		t.Fatalf("got %v, want the keyframe that answered the request", u.data)
	}
}

func TestViewerThatFallsBehindSkipsToKeyframe(t *testing.T) {
	h := NewHub((&recorder{}).send)
	h.Start("phone", protocol.MirrorSettings{})
	feed(h, "phone", true, 0)
	first, _ := h.next(0, time.Second)

	for i := range historyUnits + 10 {
		feed(h, "phone", i == historyUnits+5, byte(i))
	}
	u, err := h.next(first.seq, time.Second)
	if err != nil || !u.meta.Key {
		t.Fatalf("got %+v %v, want a jump to the newest keyframe", u.meta, err)
	}
}

func TestFramesFromOtherDevicesAreIgnored(t *testing.T) {
	h := NewHub((&recorder{}).send)
	h.Start("phone", protocol.MirrorSettings{})
	feed(h, "intruder", true, 1)
	if st := h.State(); st.Streaming {
		t.Fatal("a frame from the wrong device started the mirror")
	}
}

func TestPhoneInitiatedMirrorClaimsFreeSlotOnly(t *testing.T) {
	h := NewHub((&recorder{}).send)
	h.ReportState("a", protocol.MirrorState{Streaming: false})
	if h.current() != "" {
		t.Fatal("an idle report claimed the slot")
	}
	h.ReportState("a", protocol.MirrorState{Streaming: true})
	if h.current() != "a" {
		t.Fatal("a streaming report did not claim the free slot")
	}
	h.ReportState("b", protocol.MirrorState{Streaming: true})
	if h.current() != "a" {
		t.Fatal("a second phone stole the slot")
	}
	h.ReportState("a", protocol.MirrorState{Streaming: false})
	if h.current() != "" {
		t.Fatal("a phone that stopped still holds the slot")
	}
}

func TestStopAndDetachEndViewers(t *testing.T) {
	rec := &recorder{}
	h := NewHub(rec.send)
	h.Start("phone", protocol.MirrorSettings{})
	feed(h, "phone", true, 1)

	errs := make(chan error, 1)
	go func() {
		u, _ := h.next(0, time.Second)
		_, err := h.next(u.seq, 5*time.Second)
		errs <- err
	}()
	time.Sleep(20 * time.Millisecond)
	h.Detach("phone")
	select {
	case err := <-errs:
		if !errors.Is(err, ErrNotMirroring) {
			t.Fatalf("viewer ended with %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("viewer still waiting after detach")
	}
	if err := h.Input(protocol.MirrorInput{Kind: protocol.MirrorTap}); !errors.Is(err, ErrNotMirroring) {
		t.Fatalf("input with no mirror: %v", err)
	}
}

func TestAppsAndLaunchRouting(t *testing.T) {
	rec := &recorder{}
	h := NewHub(rec.send)
	if err := h.RequestApps(""); !errors.Is(err, ErrNotMirroring) {
		t.Fatalf("apps with nobody to ask: %v", err)
	}
	if err := h.RequestApps("phone"); err != nil {
		t.Fatal(err)
	}
	h.SetApps("phone", []protocol.MirrorApp{{Package: "com.example", Label: "Example"}})
	device, apps := h.Apps()
	if device != "phone" || len(apps) != 1 {
		t.Fatalf("apps %s %+v", device, apps)
	}
	if err := h.Launch("phone", "com.example"); err != nil {
		t.Fatal(err)
	}
	if rec.count(protocol.ActionMirrorLaunch) != 1 {
		t.Fatal("launch not sent")
	}
	h.Detach("phone")
	if _, apps := h.Apps(); len(apps) != 0 {
		t.Fatal("a disconnected phone's apps are still listed")
	}
}
