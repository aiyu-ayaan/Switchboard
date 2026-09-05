// Package camera holds the frames a phone is streaming and hands them to
// whatever on this machine wants to look at them.
//
// It is deliberately a *latest frame* holder rather than a queue. A video
// stream has no value in its backlog: a consumer that falls behind wants the
// current frame, not the one from two seconds ago, and a queue would trade
// memory and latency for footage nobody will watch. Slow consumers therefore
// drop frames instead of accumulating them.
package camera

import (
	"errors"
	"fmt"
	"io"
	"net/http"
	"sync"
	"time"

	"switchboard/backend/internal/protocol"
)

// staleAfter is how long a stream keeps claiming to be live without a frame.
// A phone that loses Wi-Fi mid-stream sends no goodbye, so the absence of
// frames is the only signal that the camera is gone.
const staleAfter = 5 * time.Second

// ErrNotStreaming reports that nothing is being captured right now.
var ErrNotStreaming = errors.New("camera: no device is streaming")

// Hub owns the single active stream. One at a time is a deliberate limit:
// the desktop shows one camera, and a second phone streaming into the same
// sink would produce a picture that flickers between two rooms.
type Hub struct {
	// send delivers a command to the phone that owns the stream.
	send func(deviceID, action string, payload any, blob []byte) error

	mu   sync.Mutex
	cond *sync.Cond

	deviceID string
	settings protocol.CameraSettings
	state    protocol.CameraState

	frame    []byte
	sequence int64
	frameAt  time.Time

	// Rate is measured over a sliding window rather than reported by the
	// phone: what the link actually delivered is the number that tells a user
	// to drop the quality preset, and the sender cannot know it.
	windowStart time.Time
	windowBytes int64
	windowCount int

	vcam *VCamFeeder
}

func NewHub(send func(deviceID, action string, payload any, blob []byte) error) *Hub {
	h := &Hub{
		send:     send,
		settings: protocol.DefaultCameraSettings(),
		vcam:     NewVCamFeeder(),
	}
	h.cond = sync.NewCond(&h.mu)
	return h
}

// Start asks a device to begin capturing.
func (h *Hub) Start(deviceID string, settings protocol.CameraSettings) error {
	if deviceID == "" {
		return errors.New("camera: no device selected")
	}
	h.mu.Lock()
	previous := h.deviceID
	h.deviceID = deviceID
	h.settings = settings
	h.state = protocol.CameraState{Streaming: false, DeviceID: deviceID, Settings: settings}
	h.frame, h.sequence = nil, 0
	h.mu.Unlock()

	// Tell the previous phone to let go of its camera. Skipping this leaves
	// a device holding an open camera and a foreground notification for a
	// stream nobody is watching.
	if previous != "" && previous != deviceID {
		h.send(previous, protocol.ActionCameraStop, nil, nil)
	}
	return h.send(deviceID, protocol.ActionCameraStart, settings, nil)
}

// Stop releases the camera on the streaming device.
func (h *Hub) Stop() error {
	h.mu.Lock()
	deviceID := h.deviceID
	h.deviceID = ""
	h.state = protocol.CameraState{Settings: h.settings}
	h.frame = nil
	h.mu.Unlock()
	// Wake every waiter so a long poll or an MJPEG client returns rather than
	// hanging on a stream that has ended.
	h.cond.Broadcast()

	if deviceID == "" {
		if h.vcam != nil {
			h.vcam.NotifyStopped()
		}
		return nil
	}
	return h.send(deviceID, protocol.ActionCameraStop, nil, nil)
}

// Control pushes a settings change to the streaming device.
func (h *Hub) Control(settings protocol.CameraSettings) error {
	h.mu.Lock()
	deviceID := h.deviceID
	h.settings = settings
	h.state.Settings = settings
	h.mu.Unlock()

	if deviceID == "" {
		return ErrNotStreaming
	}
	return h.send(deviceID, protocol.ActionCameraControl, settings, nil)
}

// Frame accepts one encoded image from the phone.
func (h *Hub) Frame(deviceID string, meta protocol.CameraFrame, jpeg []byte) {
	if len(jpeg) == 0 {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()

	// A frame from a phone that is not the current source is stale routing —
	// a stream that was switched away while its last frames were in flight.
	if deviceID != h.deviceID {
		return
	}

	h.frame = jpeg
	h.sequence++
	h.frameAt = time.Now()
	h.state.Streaming = true
	h.state.Width, h.state.Height = meta.Width, meta.Height

	h.measureLocked(len(jpeg))
	h.cond.Broadcast()

	if h.vcam != nil {
		_ = h.vcam.Feed(jpeg)
	}
}

// measureLocked keeps a one-second sliding window of what actually arrived.
func (h *Hub) measureLocked(size int) {
	now := time.Now()
	if h.windowStart.IsZero() {
		h.windowStart = now
	}
	h.windowBytes += int64(size)
	h.windowCount++

	if elapsed := now.Sub(h.windowStart); elapsed >= time.Second {
		seconds := elapsed.Seconds()
		h.state.FPS = float64(h.windowCount) / seconds
		h.state.BytesPerSec = int64(float64(h.windowBytes) / seconds)
		h.windowStart, h.windowBytes, h.windowCount = now, 0, 0
	}
}

// ReportState folds the capabilities the phone discovered into the snapshot.
// The measured rate is kept: the phone cannot know what the link delivered.
func (h *Hub) ReportState(deviceID string, reported protocol.CameraState) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if deviceID != h.deviceID {
		return
	}
	fps, bps := h.state.FPS, h.state.BytesPerSec
	h.state = reported
	h.state.DeviceID = deviceID
	h.state.FPS, h.state.BytesPerSec = fps, bps
	h.settings = reported.Settings
	h.cond.Broadcast()
}

// Detach clears a stream whose device has disconnected.
func (h *Hub) Detach(deviceID string) {
	h.mu.Lock()
	wasStreaming := (h.deviceID == deviceID)
	if wasStreaming {
		h.deviceID = ""
		h.frame = nil
		h.state = protocol.CameraState{Settings: h.settings, Error: "the device disconnected"}
	}
	h.mu.Unlock()
	h.cond.Broadcast()

	if wasStreaming && h.vcam != nil {
		h.vcam.NotifyStopped()
	}
}

// State is the snapshot the desktop UI renders.
func (h *Hub) State() protocol.CameraState {
	h.mu.Lock()
	defer h.mu.Unlock()
	state := h.state
	// A phone that dropped off Wi-Fi mid-stream sends no goodbye, so silence
	// is the only evidence the camera is gone.
	if state.Streaming && time.Since(h.frameAt) > staleAfter {
		state.Streaming = false
		state.FPS, state.BytesPerSec = 0, 0
		if state.Error == "" {
			state.Error = "no frames received"
		}
	}
	state.VCamInstalled = h.VCamInstalled()
	return state
}

// VCamInstalled reports whether the Switchboard virtual camera driver is registered in Windows DirectShow.
func (h *Hub) VCamInstalled() bool {
	return IsVCamInstalled()
}

// Close releases the Hub and virtual camera resources.
func (h *Hub) Close() {
	_ = h.Stop()
	if h.vcam != nil {
		h.vcam.Close()
	}
}

// Await blocks until a frame newer than after exists, and returns it with its
// sequence number. It gives up at the deadline so a caller polling in a loop
// gets a turn to notice its own context ending.
//
// This is how the desktop UI reads the stream: the renderer holds a strict
// content policy and makes no network requests of its own, so frames reach it
// through the main process, which asks for the next one rather than polling on
// a timer it would have to guess.
func (h *Hub) Await(after int64, timeout time.Duration) ([]byte, int64, error) {
	deadline := time.Now().Add(timeout)

	// sync.Cond has no deadline, so a timer does the waking. Without it a
	// caller waiting on a stream that has gone quiet would never return.
	timer := time.AfterFunc(timeout, func() { h.cond.Broadcast() })
	defer timer.Stop()

	h.mu.Lock()
	defer h.mu.Unlock()
	for {
		if h.sequence > after && h.frame != nil {
			// Safe to hand out past the unlock: Frame replaces the slice
			// header rather than writing into the existing array, so the
			// bytes a caller is still sending cannot change underneath it.
			return h.frame, h.sequence, nil
		}
		if h.deviceID == "" {
			return nil, h.sequence, ErrNotStreaming
		}
		if time.Now().After(deadline) {
			return nil, h.sequence, ErrNoNewFrame
		}
		h.cond.Wait()
	}
}

// ErrNoNewFrame reports that nothing arrived before the deadline. It is not a
// failure a caller should show: a camera pointed at a still wall legitimately
// produces nothing new for a while.
var ErrNoNewFrame = errors.New("camera: no new frame")

// ServeMJPEG streams frames as multipart/x-mixed-replace, which is what OBS,
// VLC and any browser understand without a plugin.
//
// This is the path that makes the phone usable as a webcam in applications
// Switchboard knows nothing about: point OBS at it as a media source and its
// own virtual camera carries the picture into any conferencing app. A true
// system camera device would need a signed Media Foundation or DirectShow
// filter, which is a signing problem rather than a coding one.
func (h *Hub) ServeMJPEG(w http.ResponseWriter, r *http.Request) {
	const boundary = "switchboardframe"

	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "multipart/x-mixed-replace; boundary="+boundary)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Connection", "close")

	// Waiters are woken when the client goes away, so a closed tab does not
	// leave a goroutine blocked on a camera that may never send again.
	done := r.Context().Done()
	go func() {
		<-done
		h.cond.Broadcast()
	}()

	var seen int64
	for {
		select {
		case <-done:
			return
		default:
		}

		frame, seq, err := h.Await(seen, 2*time.Second)
		if errors.Is(err, ErrNotStreaming) {
			return
		}
		if err != nil {
			continue // a still camera; keep the connection open
		}
		seen = seq

		header := fmt.Sprintf("\r\n--%s\r\nContent-Type: image/jpeg\r\nContent-Length: %d\r\n\r\n",
			boundary, len(frame))
		if _, err := io.WriteString(w, header); err != nil {
			return
		}
		if _, err := w.Write(frame); err != nil {
			return
		}
		flusher.Flush()
	}
}
