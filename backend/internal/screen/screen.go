// Package screen streams this desktop to the phones that ask to see it.
//
// It is the mirror image of the camera package: there the phone captures and
// the desktop watches, here the desktop captures and a phone watches. Each
// viewing phone gets its own capture loop at its own size and rate, because a
// tablet held sideways and a phone held upright want different pictures, and
// a slow link to one must not slow the other.
//
// Frames are JPEG. The daemon builds without cgo, so the hardware video
// encoders a desktop has are out of reach, and a software H.264 encoder in pure
// Go is not something worth carrying. JPEG at a phone's resolution is a few
// tens of milliseconds a frame, which sustains a rate where a pointer feels
// attached to the finger -- and a desktop that is not changing costs nothing,
// because an unchanged frame is never resent.
package screen

import (
	"bytes"
	"context"
	"errors"
	"hash/crc32"
	"image"
	"image/jpeg"
	"log"
	"runtime"
	"sync"
	"time"

	"switchboard/backend/internal/protocol"
	"switchboard/backend/internal/system"
)

// maxCaptureFailures is how many grabs in a row may fail before a stream is
// given up. A few failures are ordinary -- Windows refuses BitBlt while the
// secure desktop of a UAC prompt is up -- and the stream should ride them out;
// a capture that never recovers should end with a reason the phone can show.
const maxCaptureFailures = 50

// Streamer owns every live desktop stream, one per viewing device.
type Streamer struct {
	capture  func(image.Rectangle) (*system.ScreenFrame, error)
	displays func() ([]protocol.ScreenDisplay, error)
	send     func(deviceID, action string, payload any, blob []byte) error

	mu       sync.Mutex
	sessions map[string]*session
}

type session struct {
	cancel   context.CancelFunc
	done     chan struct{}
	since    time.Time
	settings protocol.ScreenSettings

	mu      sync.Mutex
	fps     float64
	display string
	// region is what is being captured, in desktop pixels, and desktop the
	// whole desktop's size: together they turn a touch on the picture into a
	// pointer position on the desktop.
	region  image.Rectangle
	desktop image.Point
}

// NewStreamer builds a Streamer that grabs frames with capture and delivers
// them with send.
func NewStreamer(
	capture func(image.Rectangle) (*system.ScreenFrame, error),
	displays func() ([]protocol.ScreenDisplay, error),
	send func(deviceID, action string, payload any, blob []byte) error,
) *Streamer {
	return &Streamer{capture: capture, displays: displays, send: send, sessions: map[string]*session{}}
}

// MapPoint turns a position on a device's picture -- fractions of what it is
// being shown -- into fractions of the whole desktop, which is what absolute
// pointer moves take. A device that is not streaming is passed through as if
// it were looking at the whole desktop.
func (s *Streamer) MapPoint(deviceID string, x, y float64) (float64, float64) {
	s.mu.Lock()
	sess := s.sessions[deviceID]
	s.mu.Unlock()
	if sess == nil {
		return x, y
	}
	sess.mu.Lock()
	defer sess.mu.Unlock()
	if sess.region.Empty() || sess.desktop.X <= 0 || sess.desktop.Y <= 0 {
		return x, y
	}
	r := sess.region
	px := float64(r.Min.X) + x*float64(r.Dx()-1)
	py := float64(r.Min.Y) + y*float64(r.Dy()-1)
	return px / float64(sess.desktop.X-1), py / float64(sess.desktop.Y-1)
}

// choose picks the region for a requested display: the whole desktop, the
// display with that ID, or the primary monitor. A display that has gone falls
// back to the primary rather than failing the stream.
func choose(displays []protocol.ScreenDisplay, want string) (image.Rectangle, string) {
	if want == protocol.ScreenAllDisplays || len(displays) == 0 {
		return image.Rectangle{}, protocol.ScreenAllDisplays
	}
	pick := -1
	for i, d := range displays {
		if want != "" && d.ID == want {
			pick = i
			break
		}
	}
	if pick < 0 {
		pick = 0
		for i, d := range displays {
			if d.Primary {
				pick = i
				break
			}
		}
	}
	d := displays[pick]
	return image.Rect(d.X, d.Y, d.X+d.Width, d.Y+d.Height), d.ID
}

// Start begins streaming to a device, replacing any stream it already has. A
// second start is how a phone changes size or quality mid-stream: the old
// loop ends and the new one sends a fresh first frame at the new settings.
func (s *Streamer) Start(deviceID string, settings protocol.ScreenSettings) error {
	if deviceID == "" {
		return errors.New("screen: no device")
	}
	settings = settings.Normalize()

	s.mu.Lock()
	previous := s.sessions[deviceID]
	ctx, cancel := context.WithCancel(context.Background())
	sess := &session{cancel: cancel, done: make(chan struct{}), since: time.Now(), settings: settings}
	s.sessions[deviceID] = sess
	s.mu.Unlock()

	if previous != nil {
		previous.cancel()
		<-previous.done
	}
	go s.run(ctx, deviceID, sess)
	return nil
}

// Stop ends a device's stream. When the desktop is the one ending it, the
// phone is told why, so it can say "the desktop stopped sharing" rather than
// sit on a frozen picture.
func (s *Streamer) Stop(deviceID, reason string) {
	if !s.end(deviceID) {
		return
	}
	if reason != "" {
		s.send(deviceID, protocol.ActionScreenState, protocol.ScreenState{Error: reason}, nil)
	}
}

// Detach ends a stream whose device has disconnected. There is nobody to tell.
func (s *Streamer) Detach(deviceID string) { s.end(deviceID) }

func (s *Streamer) end(deviceID string) bool {
	s.mu.Lock()
	sess := s.sessions[deviceID]
	delete(s.sessions, deviceID)
	s.mu.Unlock()
	if sess == nil {
		return false
	}
	sess.cancel()
	<-sess.done
	return true
}

// Viewers lists who is watching, for the desktop's sharing indicator. A
// desktop being watched should always be able to see that it is.
func (s *Streamer) Viewers() []protocol.ScreenViewer {
	s.mu.Lock()
	defer s.mu.Unlock()
	viewers := make([]protocol.ScreenViewer, 0, len(s.sessions))
	for id, sess := range s.sessions {
		sess.mu.Lock()
		settings := sess.settings
		settings.Display = sess.display
		viewers = append(viewers, protocol.ScreenViewer{
			DeviceID: id,
			Since:    sess.since.UnixMilli(),
			Settings: settings,
			FPS:      sess.fps,
		})
		sess.mu.Unlock()
	}
	return viewers
}

// run is one device's capture loop.
func (s *Streamer) run(ctx context.Context, deviceID string, sess *session) {
	defer close(sess.done)

	settings := sess.settings
	interval := time.Second / time.Duration(settings.FPS)
	var (
		scaled    *image.RGBA
		encoded   bytes.Buffer
		lastSum   uint32
		lastX     = -2.0
		lastY     = -2.0
		seq       int64
		failures  int
		windowAt  = time.Now()
		windowNum int
	)
	table := crc32.MakeTable(crc32.Castagnoli)

	// Which part of the desktop, worked out at the start and again whenever a
	// capture fails -- a monitor unplugged mid-stream fails the grab, and the
	// fresh list moves the stream to whatever is still there.
	var region image.Rectangle
	pickRegion := func() {
		displays, err := s.displays()
		if err != nil {
			displays = nil
		}
		var id string
		region, id = choose(displays, settings.Display)
		sess.mu.Lock()
		sess.region, sess.display = region, id
		sess.mu.Unlock()
		s.send(deviceID, protocol.ActionScreenState, protocol.ScreenState{
			Streaming: true, Displays: displays, Display: id,
		}, nil)
	}
	pickRegion()

	for {
		started := time.Now()

		frame, err := s.capture(region)
		if err != nil {
			failures++
			if failures%10 == 1 {
				pickRegion()
			}
			if failures >= maxCaptureFailures {
				log.Printf("screen: giving up on %s: %v", deviceID, err)
				s.mu.Lock()
				if s.sessions[deviceID] == sess {
					delete(s.sessions, deviceID)
				}
				s.mu.Unlock()
				s.send(deviceID, protocol.ActionScreenState,
					protocol.ScreenState{Error: "this desktop cannot be captured: " + err.Error()}, nil)
				return
			}
		} else {
			failures = 0
			sess.mu.Lock()
			sess.desktop = image.Pt(frame.DesktopWidth, frame.DesktopHeight)
			sess.mu.Unlock()
			scaled = Downscale(frame, settings.MaxWidth, scaled)
			sum := crc32.Checksum(scaled.Pix, table)

			cursorX, cursorY := -1.0, -1.0
			if frame.CursorX >= 0 {
				cursorX = float64(frame.CursorX) / float64(frame.Width)
				cursorY = float64(frame.CursorY) / float64(frame.Height)
			}

			meta := protocol.ScreenFrame{
				Width:        scaled.Rect.Dx(),
				Height:       scaled.Rect.Dy(),
				ScreenWidth:  frame.Width,
				ScreenHeight: frame.Height,
				CursorX:      cursorX,
				CursorY:      cursorY,
			}

			var sendErr error
			switch {
			case seq == 0 || sum != lastSum:
				encoded.Reset()
				if err := jpeg.Encode(&encoded, scaled, &jpeg.Options{Quality: settings.Quality}); err != nil {
					log.Printf("screen: encode: %v", err)
					break
				}
				seq++
				meta.Sequence = seq
				// The buffer is reused next frame. That is safe because send
				// seals the bytes into a new frame before it returns.
				sendErr = s.send(deviceID, protocol.ActionScreenFrame, meta, encoded.Bytes())
				lastSum, lastX, lastY = sum, cursorX, cursorY
				windowNum++

			case cursorX != lastX || cursorY != lastY:
				// Only the pointer moved. The picture the phone already has
				// is right, so this sends the position alone: a few dozen
				// bytes instead of a re-encoded desktop.
				meta.Sequence = seq
				sendErr = s.send(deviceID, protocol.ActionScreenFrame, meta, nil)
				lastX, lastY = cursorX, cursorY
			}
			if sendErr != nil {
				// The device is gone; removeClient will detach it, and there
				// is nothing to stream to in the meantime.
				s.mu.Lock()
				if s.sessions[deviceID] == sess {
					delete(s.sessions, deviceID)
				}
				s.mu.Unlock()
				return
			}
		}

		if elapsed := time.Since(windowAt); elapsed >= time.Second {
			sess.mu.Lock()
			sess.fps = float64(windowNum) / elapsed.Seconds()
			sess.mu.Unlock()
			windowAt, windowNum = time.Now(), 0
		}

		// Paced from when this frame started, so a slow encode eats into the
		// wait rather than adding to it. A send that blocked on a slow link
		// has already throttled the loop to what the link carries.
		wait := interval - time.Since(started)
		if wait < 0 {
			wait = 0
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(wait):
		}
	}
}

// Downscale box-filters a BGRX frame to at most maxWidth wide, writing RGBA
// into dst when it is already the right size.
//
// A box filter rather than nearest-neighbour because the thing being shrunk is
// mostly text: dropping pixels makes thin strokes vanish and reappear as a
// window moves, where averaging keeps them as a lighter line that stays put.
// The cost is one read of every source pixel, which the capture already paid
// to produce.
func Downscale(frame *system.ScreenFrame, maxWidth int, dst *image.RGBA) *image.RGBA {
	sw, sh := frame.Width, frame.Height
	dw, dh := sw, sh
	if maxWidth > 0 && sw > maxWidth {
		dw = maxWidth
		dh = max(1, (sh*maxWidth+sw/2)/sw)
	}
	if dst == nil || dst.Rect.Dx() != dw || dst.Rect.Dy() != dh {
		dst = image.NewRGBA(image.Rect(0, 0, dw, dh))
	}
	src := frame.Pix

	if dw == sw && dh == sh {
		for y := range dh {
			in := src[y*frame.Stride : y*frame.Stride+sw*4]
			out := dst.Pix[y*dst.Stride : y*dst.Stride+dw*4]
			for i := 0; i < len(in); i += 4 {
				out[i], out[i+1], out[i+2], out[i+3] = in[i+2], in[i+1], in[i], 0xff
			}
		}
		return dst
	}

	// Each source column belongs to exactly one output column, worked out
	// once: the inner loop is then one lookup and three adds per source pixel,
	// with no per-pixel span arithmetic.
	colOf := make([]int, sw)
	width := make([]uint32, dw)
	for sx := range sw {
		dx := sx * dw / sw
		colOf[sx] = dx
		width[dx]++
	}

	// Row bands run in parallel. Each output row reads only its own source
	// rows and writes only its own output row, so the bands share nothing.
	bands := min(runtime.GOMAXPROCS(0), 8, dh)
	var wg sync.WaitGroup
	for band := range bands {
		wg.Add(1)
		go func(from, to int) {
			defer wg.Done()
			acc := make([]uint32, dw*3)
			for y := from; y < to; y++ {
				y0, y1 := y*sh/dh, (y+1)*sh/dh
				if y1 <= y0 {
					y1 = y0 + 1
				}
				clear(acc)
				for sy := y0; sy < y1; sy++ {
					row := src[sy*frame.Stride : sy*frame.Stride+sw*4]
					for sx, dx := range colOf {
						i, c := sx*4, dx*3
						acc[c] += uint32(row[i+2])
						acc[c+1] += uint32(row[i+1])
						acc[c+2] += uint32(row[i])
					}
				}
				rows := uint32(y1 - y0)
				out := dst.Pix[y*dst.Stride : y*dst.Stride+dw*4]
				for x := range dw {
					n := width[x] * rows
					out[x*4] = byte(acc[x*3] / n)
					out[x*4+1] = byte(acc[x*3+1] / n)
					out[x*4+2] = byte(acc[x*3+2] / n)
					out[x*4+3] = 0xff
				}
			}
		}(band*dh/bands, (band+1)*dh/bands)
	}
	wg.Wait()
	return dst
}
