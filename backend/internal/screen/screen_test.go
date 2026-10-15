package screen

import (
	"bytes"
	"errors"
	"image"
	"image/jpeg"
	"sync"
	"testing"
	"time"

	"switchboard/backend/internal/protocol"
	"switchboard/backend/internal/system"
)

// solid builds a BGRX frame of one colour.
func solid(w, h int, r, g, b byte) *system.ScreenFrame {
	pix := make([]byte, w*h*4)
	for i := 0; i < len(pix); i += 4 {
		pix[i], pix[i+1], pix[i+2] = b, g, r
	}
	return &system.ScreenFrame{Width: w, Height: h, Stride: w * 4, Pix: pix, CursorX: -1, CursorY: -1}
}

func TestDownscaleSwapsChannelsAtFullSize(t *testing.T) {
	out := Downscale(solid(4, 2, 10, 20, 30), 0, nil)
	if out.Rect.Dx() != 4 || out.Rect.Dy() != 2 {
		t.Fatalf("size %v", out.Rect)
	}
	if got := out.Pix[:4]; !bytes.Equal(got, []byte{10, 20, 30, 0xff}) {
		t.Fatalf("pixel %v, want RGBA 10,20,30,255", got)
	}
}

func TestDownscaleAveragesBlocks(t *testing.T) {
	// A 4x2 frame, left half black and right half white, halved: each output
	// pixel covers one 2x2 block of a single colour.
	frame := solid(4, 2, 0, 0, 0)
	for y := range 2 {
		for x := 2; x < 4; x++ {
			i := y*frame.Stride + x*4
			frame.Pix[i], frame.Pix[i+1], frame.Pix[i+2] = 255, 255, 255
		}
	}
	out := Downscale(frame, 2, nil)
	if out.Rect.Dx() != 2 || out.Rect.Dy() != 1 {
		t.Fatalf("size %v, want 2x1", out.Rect)
	}
	if out.Pix[0] != 0 || out.Pix[4] != 255 {
		t.Fatalf("got %v", out.Pix)
	}

	// A 2-pixel-wide checker averages to grey, which is what keeps a thin
	// stroke visible instead of dropping it.
	frame = solid(2, 1, 0, 0, 0)
	frame.Pix[4], frame.Pix[5], frame.Pix[6] = 200, 200, 200
	out = Downscale(frame, 1, nil)
	if out.Pix[0] != 100 {
		t.Fatalf("grey %d, want 100", out.Pix[0])
	}
}

func TestDownscaleKeepsAspect(t *testing.T) {
	out := Downscale(solid(1920, 1080, 1, 2, 3), 1280, nil)
	if out.Rect.Dx() != 1280 || out.Rect.Dy() != 720 {
		t.Fatalf("size %v, want 1280x720", out.Rect)
	}
	again := Downscale(solid(1920, 1080, 1, 2, 3), 1280, out)
	if again != out {
		t.Fatal("a right-sized destination was not reused")
	}
}

type sent struct {
	action string
	meta   any
	blob   []byte
}

type recorder struct {
	mu    sync.Mutex
	items []sent
	fail  bool
}

func (r *recorder) send(_ string, action string, payload any, blob []byte) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.fail {
		return errors.New("gone")
	}
	r.items = append(r.items, sent{action, payload, append([]byte(nil), blob...)})
	return nil
}

func (r *recorder) snapshot() []sent {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]sent(nil), r.items...)
}

// frames is every screen.frame sent, leaving out the state reports.
func (r *recorder) frames() []sent {
	var out []sent
	for _, item := range r.snapshot() {
		if item.action == protocol.ActionScreenFrame {
			out = append(out, item)
		}
	}
	return out
}

func noDisplays() ([]protocol.ScreenDisplay, error) { return nil, nil }

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(3 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestStreamerSendsChangesOnly(t *testing.T) {
	var mu sync.Mutex
	current := solid(64, 32, 50, 50, 50)
	capture := func(image.Rectangle) (*system.ScreenFrame, error) {
		mu.Lock()
		defer mu.Unlock()
		copyFrame := *current
		return &copyFrame, nil
	}
	rec := &recorder{}
	s := NewStreamer(capture, noDisplays, rec.send)
	if err := s.Start("phone", protocol.ScreenSettings{FPS: 30}); err != nil {
		t.Fatal(err)
	}
	defer s.Stop("phone", "")

	waitFor(t, "first frame", func() bool { return len(rec.frames()) >= 1 })
	first := rec.frames()[0]
	if first.action != protocol.ActionScreenFrame || len(first.blob) == 0 {
		t.Fatalf("first send %+v", first.action)
	}
	if _, err := jpeg.Decode(bytes.NewReader(first.blob)); err != nil {
		t.Fatalf("first frame is not a JPEG: %v", err)
	}

	// An unchanging desktop sends nothing more.
	time.Sleep(150 * time.Millisecond)
	if n := len(rec.frames()); n != 1 {
		t.Fatalf("%d sends for an unchanged desktop, want 1", n)
	}

	// A moved cursor alone is a metadata-only frame.
	mu.Lock()
	current = solid(64, 32, 50, 50, 50)
	current.CursorX, current.CursorY = 32, 16
	mu.Unlock()
	waitFor(t, "cursor update", func() bool { return len(rec.frames()) >= 2 })
	cursor := rec.frames()[1]
	if len(cursor.blob) != 0 {
		t.Fatal("a cursor move re-sent the picture")
	}
	meta := cursor.meta.(protocol.ScreenFrame)
	if meta.CursorX != 0.5 || meta.CursorY != 0.5 || meta.Sequence != 1 {
		t.Fatalf("cursor meta %+v", meta)
	}

	// New pixels are a new frame with the next sequence.
	mu.Lock()
	current = solid(64, 32, 200, 10, 10)
	current.CursorX, current.CursorY = 32, 16
	mu.Unlock()
	waitFor(t, "changed frame", func() bool { return len(rec.frames()) >= 3 })
	changed := rec.frames()[2]
	if len(changed.blob) == 0 || changed.meta.(protocol.ScreenFrame).Sequence != 2 {
		t.Fatalf("changed frame %+v", changed.meta)
	}

	if viewers := s.Viewers(); len(viewers) != 1 || viewers[0].DeviceID != "phone" {
		t.Fatalf("viewers %+v", viewers)
	}
}

func TestStreamerStopTellsThePhone(t *testing.T) {
	rec := &recorder{}
	s := NewStreamer(func(image.Rectangle) (*system.ScreenFrame, error) { return solid(8, 8, 1, 1, 1), nil }, noDisplays, rec.send)
	s.Start("phone", protocol.ScreenSettings{})
	waitFor(t, "first frame", func() bool { return len(rec.frames()) >= 1 })

	s.Stop("phone", "stopped on the desktop")
	items := rec.snapshot()
	last := items[len(items)-1]
	if last.action != protocol.ActionScreenState {
		t.Fatalf("last send %s, want screen.state", last.action)
	}
	if len(s.Viewers()) != 0 {
		t.Fatal("viewer still listed after stop")
	}

	// Detach of a device with no stream is a no-op, not a hang.
	s.Detach("phone")
}

func TestStreamerEndsWhenDeviceIsGone(t *testing.T) {
	rec := &recorder{fail: true}
	s := NewStreamer(func(image.Rectangle) (*system.ScreenFrame, error) { return solid(8, 8, 1, 1, 1), nil }, noDisplays, rec.send)
	s.Start("phone", protocol.ScreenSettings{})
	waitFor(t, "session to end", func() bool { return len(s.Viewers()) == 0 })
}

func TestStreamerGivesUpOnDeadCapture(t *testing.T) {
	rec := &recorder{}
	s := NewStreamer(func(image.Rectangle) (*system.ScreenFrame, error) { return nil, errors.New("no display") }, noDisplays, rec.send)
	s.Start("phone", protocol.ScreenSettings{FPS: 30})
	waitFor(t, "failure report", func() bool {
		for _, item := range rec.snapshot() {
			if state, ok := item.meta.(protocol.ScreenState); ok && state.Error != "" {
				return true
			}
		}
		return false
	})
	if len(s.Viewers()) != 0 {
		t.Fatal("a dead capture is still listed as streaming")
	}
}

func BenchmarkFrame1080p(b *testing.B) {
	frame := solid(1920, 1080, 30, 60, 90)
	for i := range frame.Pix {
		frame.Pix[i] ^= byte(i * 7)
	}
	var dst = Downscale(frame, 1600, nil)
	var buf bytes.Buffer
	b.ResetTimer()
	for range b.N {
		dst = Downscale(frame, 1600, dst)
		buf.Reset()
		jpeg.Encode(&buf, dst, &jpeg.Options{Quality: 70})
	}
}

var threeMonitors = []protocol.ScreenDisplay{
	{ID: "HDMI-0", X: 0, Y: 0, Width: 1080, Height: 1920},
	{ID: "DP-0", X: 1080, Y: 344, Width: 2560, Height: 1440, Primary: true},
	{ID: "eDP-1", X: 3640, Y: 344, Width: 1920, Height: 1080},
}

func TestChooseDisplay(t *testing.T) {
	cases := []struct {
		want, id string
		rect     image.Rectangle
	}{
		{"", "DP-0", image.Rect(1080, 344, 3640, 1784)},
		{"eDP-1", "eDP-1", image.Rect(3640, 344, 5560, 1424)},
		{"unplugged", "DP-0", image.Rect(1080, 344, 3640, 1784)},
		{protocol.ScreenAllDisplays, protocol.ScreenAllDisplays, image.Rectangle{}},
	}
	for _, c := range cases {
		rect, id := choose(threeMonitors, c.want)
		if id != c.id || rect != c.rect {
			t.Errorf("choose(%q) = %v %q, want %v %q", c.want, rect, id, c.rect, c.id)
		}
	}
	if rect, id := choose(nil, ""); !rect.Empty() || id != protocol.ScreenAllDisplays {
		t.Errorf("no display list should share everything, got %v %q", rect, id)
	}
}

func TestMapPointCoversOneMonitor(t *testing.T) {
	var asked image.Rectangle
	var mu sync.Mutex
	capture := func(r image.Rectangle) (*system.ScreenFrame, error) {
		mu.Lock()
		asked = r
		mu.Unlock()
		f := solid(r.Dx(), r.Dy(), 1, 1, 1)
		f.DesktopWidth, f.DesktopHeight = 5560, 1920
		return f, nil
	}
	rec := &recorder{}
	s := NewStreamer(capture, func() ([]protocol.ScreenDisplay, error) { return threeMonitors, nil }, rec.send)
	s.Start("phone", protocol.ScreenSettings{Display: "eDP-1", MaxWidth: 640})
	defer s.Stop("phone", "")
	waitFor(t, "first frame", func() bool { return len(rec.frames()) >= 1 })

	mu.Lock()
	if asked != image.Rect(3640, 344, 5560, 1424) {
		t.Fatalf("captured %v, want the eDP-1 rectangle", asked)
	}
	mu.Unlock()

	// The picture's top-left and bottom-right are the monitor's corners on
	// the whole desktop.
	x, y := s.MapPoint("phone", 0, 0)
	if abs(x*5559-3640) > 1 || abs(y*1919-344) > 1 {
		t.Fatalf("top-left maps to %.1f,%.1f", x*5559, y*1919)
	}
	x, y = s.MapPoint("phone", 1, 1)
	if abs(x*5559-5559) > 1 || abs(y*1919-1423) > 1 {
		t.Fatalf("bottom-right maps to %.1f,%.1f", x*5559, y*1919)
	}

	// The phone was told which monitor it is looking at, and what else there is.
	state := rec.snapshot()[0].meta.(protocol.ScreenState)
	if state.Display != "eDP-1" || len(state.Displays) != 3 {
		t.Fatalf("state %+v", state)
	}
	if viewers := s.Viewers(); viewers[0].Settings.Display != "eDP-1" {
		t.Fatalf("viewer reports display %q", viewers[0].Settings.Display)
	}

	// No stream, no mapping.
	if x, y := s.MapPoint("other", 0.3, 0.4); x != 0.3 || y != 0.4 {
		t.Fatalf("unknown device mapped to %v,%v", x, y)
	}
}

func abs(v float64) float64 {
	if v < 0 {
		return -v
	}
	return v
}
