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
	HostName     string     `json:"hostName"`
	DaemonID     string     `json:"daemonId"`
	Displays     []Display  `json:"displays"`
	Volume       Volume     `json:"volume"`
	Media        MediaState `json:"media"`
	Capabilities []string   `json:"capabilities"`
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

// FileChunk is one slice of the file. Offset is authoritative — the receiver
// writes at it rather than appending, so a duplicate or reordered frame cannot
// corrupt the output.
type FileChunk struct {
	TransferID string `json:"transferId"`
	Offset     int64  `json:"offset"`
	Data       string `json:"data"` // base64
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
