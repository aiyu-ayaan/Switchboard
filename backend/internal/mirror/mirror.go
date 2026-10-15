// Package mirror receives a phone's screen and serves it to the desktop UI.
//
// It differs from the camera hub in one way that matters: the camera hub keeps
// only the latest frame, which is right for pictures but wrong for H.264. A
// delta access unit only decodes on top of every unit before it, so a viewer
// that skips one shows smeared garbage until the next keyframe. This hub keeps
// a short ordered history instead, and each viewer reads it in order from the
// most recent keyframe. A viewer that falls further behind than the history
// reaches jumps forward to the newest keyframe rather than queueing latency.
//
// Several viewers read one stream. Each app window on the desktop is a viewer
// of the same phone screen, so opening three windows costs the phone nothing
// extra.
package mirror

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"switchboard/backend/internal/protocol"
)

const (
	// historyUnits and historyBytes bound the kept history: a few seconds at
	// 60 fps, and a ceiling that a burst of large keyframes cannot pass.
	historyUnits = 360
	historyBytes = 24 << 20

	// staleAfter is how long a mirror keeps claiming to be live without a
	// unit. A static phone screen still sends one per keyframe interval.
	staleAfter = 6 * time.Second

	// keyframeEvery throttles keyframe requests, which every viewer that
	// joins mid-stream makes.
	keyframeEvery = 500 * time.Millisecond
)

// ErrNotMirroring reports that no phone is sharing its screen.
var ErrNotMirroring = errors.New("mirror: no device is sharing its screen")

type unit struct {
	seq  int64
	meta protocol.MirrorFrame
	data []byte
}

// Hub owns the single active mirror. One at a time, like the camera: a
// desktop showing two phones' screens interleaved in one stream would be
// useless, and the phone-side cost of a second capture is real.
type Hub struct {
	send func(deviceID, action string, payload any, blob []byte) error

	mu   sync.Mutex
	cond *sync.Cond

	deviceID string
	settings protocol.MirrorSettings
	state    protocol.MirrorState

	units   []unit
	bytes   int
	nextSeq int64
	unitAt  time.Time

	keyAskedAt time.Time

	appsDevice string
	apps       []protocol.MirrorApp

	windowStart time.Time
	windowBytes int64
	windowCount int
}

func NewHub(send func(deviceID, action string, payload any, blob []byte) error) *Hub {
	h := &Hub{send: send, settings: protocol.DefaultMirrorSettings(), nextSeq: 1}
	h.cond = sync.NewCond(&h.mu)
	return h
}

// Start asks a phone to begin sharing its screen.
func (h *Hub) Start(deviceID string, settings protocol.MirrorSettings) error {
	if deviceID == "" {
		return errors.New("mirror: no device selected")
	}
	if settings.MaxSize <= 0 || settings.Bitrate <= 0 || settings.FPS <= 0 {
		settings = protocol.DefaultMirrorSettings()
	}
	h.mu.Lock()
	previous := h.deviceID
	h.deviceID = deviceID
	h.settings = settings
	h.state = protocol.MirrorState{DeviceID: deviceID, Settings: settings, AwaitingConsent: true}
	h.resetLocked()
	h.mu.Unlock()
	h.cond.Broadcast()

	if previous != "" && previous != deviceID {
		h.send(previous, protocol.ActionMirrorStop, nil, nil)
	}
	return h.send(deviceID, protocol.ActionMirrorStart, settings, nil)
}

// Stop ends the mirror on the phone.
func (h *Hub) Stop() error {
	h.mu.Lock()
	deviceID := h.deviceID
	h.deviceID = ""
	h.state = protocol.MirrorState{Settings: h.settings}
	h.resetLocked()
	h.mu.Unlock()
	h.cond.Broadcast()

	if deviceID == "" {
		return nil
	}
	return h.send(deviceID, protocol.ActionMirrorStop, nil, nil)
}

func (h *Hub) resetLocked() {
	h.units, h.bytes = nil, 0
	h.unitAt = time.Time{}
	h.windowStart, h.windowBytes, h.windowCount = time.Time{}, 0, 0
}

// Frame accepts one access unit from the phone.
func (h *Hub) Frame(deviceID string, meta protocol.MirrorFrame, payload []byte) {
	if len(payload) == 0 {
		return
	}
	// The blob aliases the session's read buffer; history outlives it.
	data := append([]byte(nil), payload...)

	h.mu.Lock()
	if deviceID != h.deviceID {
		h.mu.Unlock()
		return
	}
	now := time.Now()
	h.unitAt = now
	h.state.Streaming = true
	h.state.AwaitingConsent = false
	if meta.Width > 0 && meta.Height > 0 {
		h.state.Width, h.state.Height = meta.Width, meta.Height
	}

	h.units = append(h.units, unit{seq: h.nextSeq, meta: meta, data: data})
	h.nextSeq++
	h.bytes += len(data)
	for len(h.units) > 1 && (len(h.units) > historyUnits || h.bytes > historyBytes) {
		h.bytes -= len(h.units[0].data)
		h.units[0] = unit{}
		h.units = h.units[1:]
	}
	h.measureLocked(len(data))
	h.mu.Unlock()
	h.cond.Broadcast()
}

func (h *Hub) measureLocked(size int) {
	now := time.Now()
	if h.windowStart.IsZero() {
		h.windowStart = now
	}
	h.windowBytes += int64(size)
	h.windowCount++
	if elapsed := now.Sub(h.windowStart); elapsed >= time.Second {
		h.state.FPS = float64(h.windowCount) / elapsed.Seconds()
		h.state.BytesPerSec = int64(float64(h.windowBytes) / elapsed.Seconds())
		h.windowStart, h.windowBytes, h.windowCount = now, 0, 0
	}
}

// ReportState folds the phone's own report into the snapshot.
//
// A phone can start sharing from its own screen, without the desktop asking;
// its first streaming report claims a free slot, as the camera's does. A slot
// that is already claimed is never stolen.
func (h *Hub) ReportState(deviceID string, reported protocol.MirrorState) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.deviceID == "" {
		if !reported.Streaming && !reported.AwaitingConsent {
			return
		}
		h.deviceID = deviceID
	}
	if h.deviceID != deviceID {
		return
	}
	fps, bps := h.state.FPS, h.state.BytesPerSec
	h.state = reported
	h.state.DeviceID = deviceID
	h.state.FPS, h.state.BytesPerSec = fps, bps
	if reported.Settings.MaxSize > 0 {
		h.settings = reported.Settings
	}
	// A phone that stopped on its own -- the user ended the cast from the
	// notification -- frees the slot.
	if !reported.Streaming && !reported.AwaitingConsent {
		h.deviceID = ""
		h.resetLocked()
	}
	h.cond.Broadcast()
}

// Detach clears a mirror whose device has disconnected.
func (h *Hub) Detach(deviceID string) {
	h.mu.Lock()
	if h.deviceID == deviceID {
		h.deviceID = ""
		h.state = protocol.MirrorState{Settings: h.settings, Error: "the phone disconnected"}
		h.resetLocked()
	}
	if h.appsDevice == deviceID {
		h.appsDevice, h.apps = "", nil
	}
	h.mu.Unlock()
	h.cond.Broadcast()
}

// State is the snapshot the desktop UI renders.
func (h *Hub) State() protocol.MirrorState {
	h.mu.Lock()
	defer h.mu.Unlock()
	state := h.state
	if state.Streaming && time.Since(h.unitAt) > staleAfter {
		state.Streaming = false
		state.FPS, state.BytesPerSec = 0, 0
		if state.Error == "" {
			state.Error = "no frames received"
		}
	}
	return state
}

// Input forwards one desktop action to the mirroring phone.
func (h *Hub) Input(in protocol.MirrorInput) error {
	deviceID := h.current()
	if deviceID == "" {
		return ErrNotMirroring
	}
	return h.send(deviceID, protocol.ActionMirrorInput, in, nil)
}

// Launch asks a phone to bring an app to the front. It goes to the mirroring
// phone when there is one, and otherwise to whichever phone the apps came
// from, so launching from the list works before the mirror has started.
func (h *Hub) Launch(deviceID, pkg string) error {
	if deviceID == "" {
		deviceID = h.current()
	}
	if deviceID == "" {
		return ErrNotMirroring
	}
	return h.send(deviceID, protocol.ActionMirrorLaunch, protocol.MirrorLaunch{Package: pkg}, nil)
}

// RequestApps asks a phone for its launcher list. The answer arrives later
// as a mirror.apps frame, folded in by SetApps.
func (h *Hub) RequestApps(deviceID string) error {
	if deviceID == "" {
		deviceID = h.current()
	}
	if deviceID == "" {
		return ErrNotMirroring
	}
	return h.send(deviceID, protocol.ActionMirrorApps, nil, nil)
}

// SetApps records a phone's launcher list.
func (h *Hub) SetApps(deviceID string, apps []protocol.MirrorApp) {
	h.mu.Lock()
	h.appsDevice, h.apps = deviceID, apps
	h.mu.Unlock()
}

// Apps returns the last launcher list and the phone it came from.
func (h *Hub) Apps() (string, []protocol.MirrorApp) {
	h.mu.Lock()
	defer h.mu.Unlock()
	apps := h.apps
	if apps == nil {
		apps = []protocol.MirrorApp{}
	}
	return h.appsDevice, apps
}

func (h *Hub) current() string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.deviceID
}

// askKeyframeLocked requests a keyframe, at most once per keyframeEvery.
// The send happens off the lock: it writes to a socket, and every frame from
// the phone needs this lock to land.
func (h *Hub) askKeyframeLocked() {
	if h.deviceID == "" || time.Since(h.keyAskedAt) < keyframeEvery {
		return
	}
	h.keyAskedAt = time.Now()
	deviceID := h.deviceID
	go h.send(deviceID, protocol.ActionMirrorKeyframe, nil, nil)
}

// next returns the unit a viewer should send after the one it sent last, or
// waits for one. A viewer starts -- and restarts after falling behind -- at
// the newest keyframe in the history.
func (h *Hub) next(after int64, timeout time.Duration) (unit, error) {
	deadline := time.Now().Add(timeout)
	timer := time.AfterFunc(timeout, h.cond.Broadcast)
	defer timer.Stop()

	h.mu.Lock()
	defer h.mu.Unlock()
	for {
		if h.deviceID == "" {
			return unit{}, ErrNotMirroring
		}
		if len(h.units) > 0 {
			oldest := h.units[0].seq
			want := after + 1
			if after > 0 && want >= oldest && want < h.nextSeq {
				return h.units[want-oldest], nil
			}
			if after == 0 || want < oldest {
				// Joining, or fell off the end of the history.
				for i := len(h.units) - 1; i >= 0; i-- {
					if h.units[i].meta.Key {
						if after == 0 || h.units[i].seq > after {
							return h.units[i], nil
						}
						break
					}
				}
				h.askKeyframeLocked()
			}
		}
		if time.Now().After(deadline) {
			return unit{}, errNoUnit
		}
		h.cond.Wait()
	}
}

var errNoUnit = errors.New("mirror: no new unit")

// ServeVideo streams H.264 to one desktop viewer, in the same multipart
// framing the camera's video track uses so the desktop reads both with one
// parser.
func (h *Hub) ServeVideo(w http.ResponseWriter, r *http.Request) {
	const boundary = "switchboardmirror"

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary="+boundary)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Connection", "close")

	done := r.Context().Done()
	go func() {
		<-done
		h.cond.Broadcast()
	}()

	var sent int64
	for {
		select {
		case <-done:
			return
		default:
		}
		u, err := h.next(sent, 2*time.Second)
		if errors.Is(err, ErrNotMirroring) {
			return
		}
		if err != nil {
			continue
		}
		sent = u.seq

		key := 0
		if u.meta.Key {
			key = 1
		}
		header := fmt.Sprintf(
			"\r\n--%s\r\nContent-Type: video/h264\r\nContent-Length: %d\r\n"+
				"X-Key: %d\r\nX-Rotation: 0\r\nX-Mirror: 0\r\nX-Width: %d\r\nX-Height: %d\r\n"+
				"X-Timestamp: %d\r\n\r\n",
			boundary, len(u.data), key, u.meta.Width, u.meta.Height, u.meta.Timestamp)
		if _, err := io.WriteString(w, header); err != nil {
			return
		}
		if _, err := w.Write(u.data); err != nil {
			return
		}
		flusher.Flush()
	}
}
