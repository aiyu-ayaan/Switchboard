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

	// The video track, kept beside the JPEG one rather than replacing it.
	// H.264 is what makes 60 fps possible — the phone's hardware encoder does
	// it for free where a software JPEG per frame cannot — but an access unit
	// is not a picture, and the virtual camera and the MJPEG endpoint can only
	// take pictures. So both arrive and each consumer reads the track it can
	// actually use.
	video     []byte
	videoMeta protocol.CameraFrame
	videoSeq  int64
	videoAt   time.Time

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
	h.video, h.videoSeq, h.videoAt = nil, 0, time.Time{}
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
	h.frame, h.video = nil, nil
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

// Frame accepts one encoded frame from the phone, on either track.
func (h *Hub) Frame(deviceID string, meta protocol.CameraFrame, payload []byte) {
	if len(payload) == 0 {
		return
	}
	h.mu.Lock()
	defer h.mu.Unlock()

	// A frame from a phone that is not the current source is stale routing —
	// a stream that was switched away, or stopped, while its last frames were
	// still in flight.
	if deviceID != h.deviceID {
		return
	}

	now := time.Now()
	h.frameAt = now
	h.state.Streaming = true

	// Always the size as displayed. The video track reports its encoded
	// buffer, which is still on its side and rotated at the far end, so the
	// two tracks would otherwise report a picture and its transpose in turn
	// and the readout would flicker between them.
	h.state.Width, h.state.Height = meta.Width, meta.Height
	if meta.Rotation == 90 || meta.Rotation == 270 {
		h.state.Width, h.state.Height = meta.Height, meta.Width
	}

	if meta.Codec == protocol.CodecH264 {
		h.video = payload
		h.videoMeta = meta
		h.videoSeq++
		h.videoAt = now
		h.state.Codec = protocol.CodecH264
		// Only the displayed track is measured. Both are on the wire, and
		// counting each would report a frame rate no viewer is seeing.
		h.measureLocked(len(payload))
		h.cond.Broadcast()
		return
	}

	h.frame = payload
	h.sequence++
	if now.Sub(h.videoAt) > staleAfter {
		h.state.Codec = protocol.CodecJPEG
		h.measureLocked(len(payload))
	}
	h.cond.Broadcast()

	if h.vcam != nil {
		_ = h.vcam.Feed(payload)
	}
}

// adoptLocked decides whether a state report from deviceID owns the stream.
//
// A phone can start its camera from its own screen, without the desktop having
// asked, and nothing tells the host in advance. Its first state report is the
// announcement, so an unclaimed slot goes to whoever announces first. Without
// this the host discarded everything a phone-initiated stream sent and the
// camera looked dead here while it was plainly running on the phone.
//
// Only a report claims the slot, never a frame: a frame still in flight when
// the desktop pressed Stop would otherwise resurrect the stream it just ended.
//
// A slot that is already claimed is never stolen. The desktop shows one camera,
// and a second phone streaming into the same sink would produce a picture that
// flickers between two rooms.
func (h *Hub) adoptLocked(deviceID string) bool {
	if deviceID == "" {
		return false
	}
	if h.deviceID == "" {
		h.deviceID = deviceID
		return true
	}
	return h.deviceID == deviceID
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
	// A phone that has stopped and is saying so must not claim the free slot;
	// otherwise every idle device would take ownership of the stream in turn.
	if !reported.Streaming && h.deviceID == "" {
		return
	}
	if !h.adoptLocked(deviceID) {
		return
	}
	fps, bps, codec := h.state.FPS, h.state.BytesPerSec, h.state.Codec
	h.state = reported
	h.state.DeviceID = deviceID
	h.state.FPS, h.state.BytesPerSec = fps, bps
	// The phone reports what it is sending; what actually arrived is the
	// truth, and only this side knows it.
	h.state.Codec = codec
	h.settings = reported.Settings
	h.cond.Broadcast()
}

// Detach clears a stream whose device has disconnected.
func (h *Hub) Detach(deviceID string) {
	h.mu.Lock()
	wasStreaming := (h.deviceID == deviceID)
	if wasStreaming {
		h.deviceID = ""
		h.frame, h.video = nil, nil
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

// AwaitVideo blocks until an H.264 access unit newer than after exists.
//
// The video track's own sequence, not the JPEG one: the two run at different
// rates by design, and a viewer that shared a counter with the virtual camera
// would skip whichever track was behind.
func (h *Hub) AwaitVideo(after int64, timeout time.Duration) ([]byte, protocol.CameraFrame, int64, error) {
	deadline := time.Now().Add(timeout)
	timer := time.AfterFunc(timeout, func() { h.cond.Broadcast() })
	defer timer.Stop()

	h.mu.Lock()
	defer h.mu.Unlock()
	for {
		if h.videoSeq > after && h.video != nil {
			return h.video, h.videoMeta, h.videoSeq, nil
		}
		if h.deviceID == "" {
			return nil, protocol.CameraFrame{}, h.videoSeq, ErrNotStreaming
		}
		if time.Now().After(deadline) {
			return nil, protocol.CameraFrame{}, h.videoSeq, ErrNoNewFrame
		}
		h.cond.Wait()
	}
}

// ServeVideo streams the H.264 track to the desktop's own renderer, in the
// same multipart framing as ServeMJPEG so one parser reads both.
//
// This is the 60 fps path. Each part is one Annex-B access unit with its
// orientation and whether it decodes standalone; a client joining mid-stream
// discards parts until the first X-Key.
func (h *Hub) ServeVideo(w http.ResponseWriter, r *http.Request) {
	const boundary = "switchboardvideo"

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

	var seen int64
	for {
		select {
		case <-done:
			return
		default:
		}

		unit, meta, seq, err := h.AwaitVideo(seen, 2*time.Second)
		if errors.Is(err, ErrNotStreaming) {
			return
		}
		if err != nil {
			continue // a phone with no hardware encoder; keep the door open
		}
		seen = seq

		key := 0
		if meta.Key {
			key = 1
		}
		mirror := 0
		if meta.Mirror {
			mirror = 1
		}
		header := fmt.Sprintf(
			"\r\n--%s\r\nContent-Type: video/h264\r\nContent-Length: %d\r\n"+
				"X-Key: %d\r\nX-Rotation: %d\r\nX-Mirror: %d\r\nX-Width: %d\r\nX-Height: %d\r\n"+
				"X-Timestamp: %d\r\n\r\n",
			boundary, len(unit), key, meta.Rotation, mirror, meta.Width, meta.Height, meta.Timestamp)
		if _, err := io.WriteString(w, header); err != nil {
			return
		}
		if _, err := w.Write(unit); err != nil {
			return
		}
		flusher.Flush()
	}
}

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
