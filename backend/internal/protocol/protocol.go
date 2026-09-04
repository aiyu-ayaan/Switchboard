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

	ActionHostState = "host.state" // event: full snapshot pushed to clients
	ActionPing      = "system.ping"
)

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

// HostState is the full snapshot pushed on connect and after every change.
type HostState struct {
	HostName     string    `json:"hostName"`
	DaemonID     string    `json:"daemonId"`
	Displays     []Display `json:"displays"`
	Volume       Volume    `json:"volume"`
	Capabilities []string  `json:"capabilities"`
}
