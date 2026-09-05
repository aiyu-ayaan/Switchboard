// Package protocol defines the Switchboard wire format shared by the host
// daemon and every mobile client. See docs/docs/api-protocol.md.
package protocol

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

// Frame kinds.
const (
	TypeCommand  = "command"
	TypeEvent    = "event"
	TypeResponse = "response"
	TypeError    = "error"
)

// Actions understood by the daemon.
const (
	ActionDisplayList       = "display.list"
	ActionDisplayBrightness = "display.brightness.set"
	ActionDisplayContrast   = "display.contrast.set"

	ActionVolumeGet = "system.volume.get"
	ActionVolumeSet = "system.volume.set"

	// Per-application mixer: the volume of one program, not the whole host.
	ActionMixerList = "audio.mixer.list"
	ActionMixerSet  = "audio.mixer.set"

	// Output routing: which endpoint the host plays through.
	ActionOutputList = "audio.output.list"
	ActionOutputSet  = "audio.output.set"

	ActionMediaCommand = "media.playback.command"
	// ActionMediaArtwork fetches the cover art for the track named by
	// MediaState.ArtworkID. Artwork is pulled on demand rather than carried in
	// every host.state broadcast: the image is orders of magnitude larger than
	// the rest of the snapshot and only changes when the track does.
	ActionMediaArtwork = "media.artwork"

	// File transfer. Both directions use the same frames: whichever side
	// holds the file sends the offer, and the receiver drives the pace by
	// acknowledging. See docs/docs/api-protocol.md.
	ActionFileOffer    = "file.offer"    // sender -> receiver: what is coming
	ActionFileAccept   = "file.accept"   // receiver -> sender: start at offset
	ActionFileChunk    = "file.chunk"    // sender -> receiver: one slice
	ActionFileAck      = "file.ack"      // receiver -> sender: slice landed
	ActionFileComplete = "file.complete" // receiver -> sender: verified
	ActionFileControl  = "file.control"  // either side: pause/resume/cancel
	ActionFileProgress = "file.progress" // event: transfer telemetry
	ActionFileList     = "file.list"     // history

	// Air mouse. The phone owns the gesture vocabulary and sends already
	// resolved intents; the host only injects them. See docs/docs/air-mouse.md.
	ActionInputMove    = "input.move"    // relative pointer motion
	ActionInputButton  = "input.button"  // press, release, click, double click
	ActionInputScroll  = "input.scroll"  // wheel, vertical and horizontal
	ActionInputGesture = "input.gesture" // named shell gesture (task view, ...)

	// Wi-Fi camera. The phone is the capture device and the desktop is the
	// sink, so start/stop/control travel desktop -> phone and frames come
	// back the other way. See docs/docs/wifi-camera.md.
	ActionCameraStart   = "camera.start"   // desktop -> phone: begin capture
	ActionCameraStop    = "camera.stop"    // desktop -> phone: release the camera
	ActionCameraControl = "camera.control" // desktop -> phone: change a setting
	ActionCameraFrame   = "camera.frame"   // phone -> desktop: one encoded frame
	ActionCameraState   = "camera.state"   // phone -> desktop: capabilities and settings

	ActionHostState = "host.state" // event: full snapshot pushed to clients
	ActionPing      = "system.ping"
)

// ChunkSize is the payload slice carried by one file.chunk frame.
//
// 256 KiB base64-expands to ~341 KB per frame, which stays well inside a
// WebSocket frame while keeping the per-frame envelope and AEAD overhead
// negligible. Chunks are streamed straight off disk, so a multi-GB file never
// sits in memory on either side.
const ChunkSize = 256 * 1024

// Envelope is the JSON structure carried inside every encrypted frame.
type Envelope struct {
	ID        string          `json:"id"`
	Type      string          `json:"type"`
	Action    string          `json:"action"`
	Payload   json.RawMessage `json:"payload,omitempty"`
	Timestamp int64           `json:"timestamp"`
}

// New builds an envelope, marshalling payload if non-nil.
func New(kind, action string, payload any) (*Envelope, error) {
	e := &Envelope{
		ID:        uuid.NewString(),
		Type:      kind,
		Action:    action,
		Timestamp: time.Now().UnixMilli(),
	}
	if payload != nil {
		b, err := json.Marshal(payload)
		if err != nil {
			return nil, err
		}
		e.Payload = b
	}
	return e, nil
}

// Reply builds a response envelope that keeps the request's correlation ID so
// the client can match it to its pending call.
func Reply(reqID, action string, payload any) (*Envelope, error) {
	e, err := New(TypeResponse, action, payload)
	if err != nil {
		return nil, err
	}
	if reqID != "" {
		e.ID = reqID
	}
	return e, nil
}

// Errorf builds an error envelope correlated to a request.
func Errorf(reqID, action, msg string) *Envelope {
	b, _ := json.Marshal(map[string]string{"message": msg})
	id := reqID
	if id == "" {
		id = uuid.NewString()
	}
	return &Envelope{ID: id, Type: TypeError, Action: action, Payload: b, Timestamp: time.Now().UnixMilli()}
}

// Decode unmarshals the envelope payload into v.
func (e *Envelope) Decode(v any) error {
	if len(e.Payload) == 0 {
		return nil
	}
	return json.Unmarshal(e.Payload, v)
}

// ---- Payload shapes ----

// Display is one controllable panel. Min/Max are the real capability range
// reported by the hardware (DDC/CI VCP limits or the WMI brightness table),
// not an assumed 0-100 — panels routinely report other ranges.
type Display struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Internal    bool   `json:"internal"`
	Brightness  int    `json:"brightness"`
	MinBright   int    `json:"minBrightness"`
	MaxBright   int    `json:"maxBrightness"`
	HasContrast bool   `json:"hasContrast"`
	Contrast    int    `json:"contrast"`
	MinContrast int    `json:"minContrast"`
	MaxContrast int    `json:"maxContrast"`
}

// DisplaySet is the payload for display.brightness.set / display.contrast.set.
type DisplaySet struct {
	DisplayID string `json:"displayId"`
	Value     int    `json:"value"`
}

// Volume is the host master audio state.
type Volume struct {
	Level int  `json:"level"` // 0-100
	Muted bool `json:"muted"`
}

// AudioSession is one program's entry in the host mixer.
//
// ID is the OS session identifier rather than the process ID: a browser or a
// chat client spans several processes that share one mixer entry, and the PID
// of whichever one happened to open the stream is not stable across a restart.
// PID is carried anyway because it is what lets a client show a real icon.
type AudioSession struct {
	ID    string `json:"id"`
	Name  string `json:"name"`
	PID   int    `json:"pid"`
	Level int    `json:"level"` // 0-100
	Muted bool   `json:"muted"`
	// Active is false for a session that still holds its mixer entry but has
	// stopped playing. Windows keeps those around for a while, and hiding them
	// outright would make a paused player vanish from the mixer mid-use.
	Active bool `json:"active"`
}

// AudioDevice is one output endpoint the host can play through — a speaker
// set, a headset, an HDMI sink. ID is the OS endpoint identifier, which
// survives a reboot and a re-plug, unlike the position in the list.
type AudioDevice struct {
	ID      string `json:"id"`
	Name    string `json:"name"`
	Default bool   `json:"default"`
}

// OutputSet is the payload for audio.output.set.
type OutputSet struct {
	DeviceID string `json:"deviceId"`
}

// MixerSet is the payload for audio.mixer.set.
type MixerSet struct {
	SessionID string `json:"sessionId"`
	Level     int    `json:"level"`
	Muted     bool   `json:"muted"`
}

// MediaCommand is the payload for media.playback.command.
type MediaCommand struct {
	Action string `json:"action"` // play | pause | toggle | next | prev | stop
}

// Playback states reported in MediaState.Status.
const (
	PlaybackStopped = "stopped"
	PlaybackPlaying = "playing"
	PlaybackPaused  = "paused"
)

// MediaState is the now-playing snapshot read from the OS media session, so a
// client can render real transport state instead of a stateless button row.
//
// Every field is a value type: the daemon compares two snapshots to decide
// whether anything changed and a broadcast is warranted.
type MediaState struct {
	Active bool   `json:"active"` // false when nothing holds the media session
	Status string `json:"status"` // playing | paused | stopped
	Title  string `json:"title"`
	Artist string `json:"artist"`
	Album  string `json:"album"`
	Source string `json:"source"` // owning application, for the "from" label

	// ArtworkID identifies the cover art for this track. It is empty when the
	// session exposes no thumbnail. Clients cache by this value and only
	// re-fetch when it changes.
	ArtworkID string `json:"artworkId"`
}

// MediaArtwork is the reply to media.artwork: the cover image itself.
type MediaArtwork struct {
	ArtworkID string `json:"artworkId"`
	MimeType  string `json:"mimeType"`
	Data      string `json:"data"` // base64, empty when the track has no artwork
}

// HostState is the full snapshot pushed on connect and after every change.
type HostState struct {
	HostName string    `json:"hostName"`
	DaemonID string    `json:"daemonId"`
	Displays []Display `json:"displays"`
	Volume   Volume    `json:"volume"`
	// Mixer is empty on a host with no per-application control, which is what
	// the "mixer" capability tells a client to expect.
	Mixer []AudioSession `json:"mixer"`
	// Outputs are the endpoints the host can route sound to, guarded by the
	// "outputs" capability. Exactly one carries Default.
	Outputs      []AudioDevice `json:"outputs"`
	Media        MediaState    `json:"media"`
	Capabilities []string      `json:"capabilities"`
}

// ---- Wi-Fi camera ----

// Quality presets. These pick a capture resolution and a JPEG quality
// together, because the two trade off against the same thing — bandwidth —
// and exposing them separately invites combinations that make no sense.
const (
	QualityFull     = "full"     // native sensor resolution, high quality
	QualityBalanced = "balanced" // 720p
	QualityLow      = "low"      // 480p, for a congested link
)

// White balance presets, named after the Camera2 modes they map to.
const (
	WhiteBalanceAuto         = "auto"
	WhiteBalanceIncandescent = "incandescent"
	WhiteBalanceFluorescent  = "fluorescent"
	WhiteBalanceDaylight     = "daylight"
	WhiteBalanceCloudy       = "cloudy"
	WhiteBalanceShade        = "shade"
)

// Camera facings.
const (
	FacingBack  = "back"
	FacingFront = "front"
)

// CameraSettings is the full control surface, sent whole rather than as
// individual patches: the phone applies it as one camera reconfiguration, and
// a partial update would need every field to be nullable to tell "unset" from
// "set to zero".
type CameraSettings struct {
	Facing  string `json:"facing"`
	Quality string `json:"quality"`
	FPS     int    `json:"fps"`

	// Rotation is applied by the phone before encoding, in degrees clockwise.
	// Doing it at the source means the desktop never has to rotate a decoded
	// frame, and a rotated stream costs the same bandwidth as an upright one.
	Rotation int  `json:"rotation"`
	Mirror   bool `json:"mirror"`

	// Zoom is normalised 0-1 across the sensor's own range rather than a
	// ratio, because the maximum differs per lens and a desktop slider should
	// not have to know which phone is on the other end.
	Zoom  float64 `json:"zoom"`
	Torch bool    `json:"torch"`

	AutoFocus     bool    `json:"autoFocus"`
	FocusDistance float64 `json:"focusDistance"` // 0 (near) - 1 (infinity)

	AutoExposure bool `json:"autoExposure"`
	// Exposure is an index into the sensor's compensation range, which is
	// what Camera2 exposes; MinExposure and MaxExposure in CameraState give
	// it meaning.
	Exposure int `json:"exposure"`

	WhiteBalance string `json:"whiteBalance"`

	// AutoFraming keeps a detected face centred by cropping, so the subject
	// stays in shot without a gimbal.
	AutoFraming bool `json:"autoFraming"`
}

// DefaultCameraSettings is what a stream starts at: full quality, as the
// feature was specified, with everything automatic.
func DefaultCameraSettings() CameraSettings {
	return CameraSettings{
		Facing: FacingBack, Quality: QualityFull, FPS: 30,
		AutoFocus: true, AutoExposure: true, WhiteBalance: WhiteBalanceAuto,
	}
}

// CameraState is what the phone reports back: the settings in force plus the
// ranges this particular lens can actually honour, so the desktop hides a
// control the hardware does not have rather than offering a dead slider.
type CameraState struct {
	Streaming bool           `json:"streaming"`
	DeviceID  string         `json:"deviceId,omitempty"`
	Settings  CameraSettings `json:"settings"`

	Width  int `json:"width"`
	Height int `json:"height"`

	MaxZoomRatio float64 `json:"maxZoomRatio"`
	MinExposure  int     `json:"minExposure"`
	MaxExposure  int     `json:"maxExposure"`

	HasTorch          bool `json:"hasTorch"`
	HasManualFocus    bool `json:"hasManualFocus"`
	HasManualExposure bool `json:"hasManualExposure"`
	HasWhiteBalance   bool `json:"hasWhiteBalance"`
	HasFrontCamera    bool `json:"hasFrontCamera"`

	// FPS and BytesPerSec are measured, not requested: what the link is
	// actually carrying, which is the number that tells a user to drop the
	// quality preset.
	FPS         float64 `json:"fps"`
	BytesPerSec int64   `json:"bytesPerSec"`

	Error string `json:"error,omitempty"`
}

// CameraFrame is the metadata for one encoded frame. The JPEG itself rides
// beside it as a FrameBlob — at 30fps, base64 would add a third to the
// bandwidth of the single heaviest thing on the wire.
type CameraFrame struct {
	Sequence  int64 `json:"seq"`
	Width     int   `json:"width"`
	Height    int   `json:"height"`
	Timestamp int64 `json:"ts"`
}

// ---- Air mouse ----

// Mouse buttons carried by InputButton.Button.
const (
	ButtonLeft   = "left"
	ButtonRight  = "right"
	ButtonMiddle = "middle"
)

// Button actions. Down and Up exist separately so a tap-and-a-half drag can
// hold the button across many input.move frames.
const (
	ButtonDown   = "down"
	ButtonUp     = "up"
	ButtonClick  = "click"
	ButtonDouble = "double"
)

// Named shell gestures. The host maps each to whatever its window manager
// uses; the phone never sends raw key codes, so this list is the whole of the
// keyboard surface the air mouse exposes.
const (
	GestureTaskView     = "taskView"
	GestureShowDesktop  = "showDesktop"
	GestureDesktopLeft  = "desktopLeft"
	GestureDesktopRight = "desktopRight"
	GestureBack         = "back"
	GestureForward      = "forward"
)

// InputMove is relative pointer motion in host pixels. The values are
// fractional because a slow drag moves well under a pixel per frame: the host
// accumulates the remainder rather than truncating it away, so a careful
// finger still moves the cursor.
type InputMove struct {
	DX float64 `json:"dx"`
	DY float64 `json:"dy"`
}

// InputButton presses, releases, or clicks one mouse button.
type InputButton struct {
	Button string `json:"button"`
	Action string `json:"action"`
}

// InputScroll is wheel motion in notches, where one notch is one detent of a
// physical wheel. Positive DY scrolls up and positive DX scrolls right; the
// phone applies the user's natural-scroll preference before sending, so the
// host never has to know about it. Ctrl asks for the wheel to be sent with
// control held, which is how every desktop spells "zoom".
type InputScroll struct {
	DX   float64 `json:"dx"`
	DY   float64 `json:"dy"`
	Ctrl bool    `json:"ctrl,omitempty"`
}

// InputGesture triggers one named shell gesture.
type InputGesture struct {
	Name string `json:"name"`
}

// ---- File transfer ----

// Transfer directions, named from the mobile client's point of view so a
// stored row reads the same on both ends.
const (
	DirectionUpload   = "upload"   // phone -> desktop
	DirectionDownload = "download" // desktop -> phone
)

// Transfer lifecycle states.
const (
	TransferPending   = "pending"
	TransferActive    = "active"
	TransferPaused    = "paused"
	TransferCompleted = "completed"
	TransferFailed    = "failed"
	TransferCancelled = "cancelled"
)

// Control actions carried by file.control.
const (
	ControlPause  = "pause"
	ControlResume = "resume"
	ControlCancel = "cancel"
)

// FileOffer announces a file before any bytes move, so the receiver can
// allocate, resume, or refuse before the sender starts streaming.
type FileOffer struct {
	TransferID string `json:"transferId"`
	Name       string `json:"name"`
	Size       int64  `json:"size"`
	MimeType   string `json:"mimeType,omitempty"`
	// SHA256 of the whole file, hex. Checked by the receiver on completion.
	SHA256    string `json:"sha256,omitempty"`
	Direction string `json:"direction"`
}

// FileAccept is the receiver's go-ahead. Offset is how many bytes it already
// holds: a fresh transfer sends 0, a resumed one sends the length of the
// partial file it kept, and the sender seeks there rather than restarting.
type FileAccept struct {
	TransferID string `json:"transferId"`
	Offset     int64  `json:"offset"`
	Accepted   bool   `json:"accepted"`
	Reason     string `json:"reason,omitempty"`
}

// FileChunk describes one slice of the file. The bytes themselves ride beside
// this envelope as a FrameBlob rather than base64 inside it: the frame is
// binary on the wire either way, so encoding them would inflate every byte by
// a third and buy an encode on one CPU and a decode on the other for nothing.
//
// Offset is authoritative — the receiver writes at it rather than appending,
// so a duplicate or reordered frame cannot corrupt the output.
type FileChunk struct {
	TransferID string `json:"transferId"`
	Offset     int64  `json:"offset"`
	Last       bool   `json:"last,omitempty"`
}

// FileAck paces the sender: it waits for the receiver to confirm a window of
// chunks before sending more, so a fast disk cannot outrun a slow phone and
// pile frames up in the socket buffer.
type FileAck struct {
	TransferID string `json:"transferId"`
	Received   int64  `json:"received"` // bytes written so far
}

// FileComplete closes a transfer. OK is false when the receiver's digest did
// not match the offer, which means the bytes are bad and the file was dropped.
type FileComplete struct {
	TransferID string `json:"transferId"`
	OK         bool   `json:"ok"`
	SHA256     string `json:"sha256,omitempty"`
	Error      string `json:"error,omitempty"`
}

// FileControl pauses, resumes, or cancels a live transfer from either side.
type FileControl struct {
	TransferID string `json:"transferId"`
	Action     string `json:"action"`
}

// FileProgress is the telemetry both UIs render: a progress bar, a rate, and
// the state that decides which buttons are live.
type FileProgress struct {
	TransferID  string `json:"transferId"`
	Name        string `json:"name"`
	Direction   string `json:"direction"`
	Status      string `json:"status"`
	Transferred int64  `json:"transferred"`
	Size        int64  `json:"size"`
	// BytesPerSec is a smoothed rate; both UIs format it per the user's
	// MB/s or Mb/s preference rather than the daemon picking a unit.
	BytesPerSec int64  `json:"bytesPerSec"`
	Error       string `json:"error,omitempty"`
	StartedAt   int64  `json:"startedAt"`
	FinishedAt  int64  `json:"finishedAt,omitempty"`
}

// FileHistory is the reply to file.list.
type FileHistory struct {
	Transfers []FileProgress `json:"transfers"`
}
