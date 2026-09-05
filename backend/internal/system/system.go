// Package system exposes the host OS controls Switchboard drives: display
// brightness and contrast, master audio volume, and media transport.
//
// Every platform-specific detail lives behind this Controller, so the server
// and wire protocol never branch on GOOS.
package system

import (
	"errors"
	"os"
	"sync"

	"switchboard/backend/internal/protocol"
)

// ErrUnsupported reports a control the current platform cannot provide.
var ErrUnsupported = errors.New("system: not supported on this platform")

// Controller manages OS-level operations. It is safe for concurrent use.
type Controller struct {
	displays *displayController

	mu       sync.Mutex
	hostName string
}

// NewController builds the host controller.
func NewController() *Controller {
	name, err := os.Hostname()
	if err != nil || name == "" {
		name = "Unknown host"
	}
	return &Controller{displays: newDisplayController(), hostName: name}
}

// Close releases OS handles held by the controller.
func (c *Controller) Close() { c.displays.Close() }

// HostName is the machine name shown in the mobile host switcher.
func (c *Controller) HostName() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.hostName
}

// Displays returns every controllable panel with its real capability range.
func (c *Controller) Displays() ([]protocol.Display, error) { return c.displays.List() }

// RefreshDisplays re-enumerates after a display hotplug.
func (c *Controller) RefreshDisplays() error { return c.displays.Refresh() }

// SetBrightness applies a brightness value, clamped to the panel's own range.
func (c *Controller) SetBrightness(id string, value int) (protocol.Display, error) {
	return c.displays.SetBrightness(id, value)
}

// SetContrast applies a contrast value, clamped to the panel's own range.
func (c *Controller) SetContrast(id string, value int) (protocol.Display, error) {
	return c.displays.SetContrast(id, value)
}

// Volume reads the host master output volume.
func (c *Controller) Volume() (protocol.Volume, error) { return getVolume() }

// SetVolume writes the host master output volume and mute state.
func (c *Controller) SetVolume(level int, muted bool) (protocol.Volume, error) {
	return setVolume(level, muted)
}

// Mixer lists the per-application audio sessions on the default output.
func (c *Controller) Mixer() ([]protocol.AudioSession, error) { return mixerSessions() }

// SetSessionVolume writes one program's level and mute state, and returns the
// whole mixer: moving one session can change others, because Windows ducks
// sessions against each other, and the caller renders the list as a unit.
func (c *Controller) SetSessionVolume(id string, level int, muted bool) ([]protocol.AudioSession, error) {
	return setSessionVolume(id, level, muted)
}

// Outputs lists the audio endpoints the host can play through.
func (c *Controller) Outputs() ([]protocol.AudioDevice, error) { return audioOutputs() }

// SetOutput routes host audio to one endpoint and returns the refreshed list,
// because moving the default changes which row is marked, not just one row.
func (c *Controller) SetOutput(id string) ([]protocol.AudioDevice, error) {
	return setAudioOutput(id)
}

// Media sends a transport command to the active media session.
func (c *Controller) Media(action string) error { return sendMediaCommand(action) }

// MediaState reads what the host is playing. A host with nothing playing
// yields the zero state and no error, so callers distinguish "idle" from
// "broken" without inspecting the error.
func (c *Controller) MediaState() (protocol.MediaState, error) { return mediaState() }

// MediaArtwork reads the cover image for the current track. Artwork is fetched
// on demand rather than carried in every snapshot: it is far larger than the
// rest of the state and changes only when the track does.
func (c *Controller) MediaArtwork() (protocol.MediaArtwork, error) { return mediaArtwork() }

// State assembles the snapshot pushed to clients. Individual controls are
// allowed to fail without failing the whole snapshot: a machine with no audio
// endpoint should still be able to drive its monitors.
func (c *Controller) State(daemonID string) protocol.HostState {
	state := protocol.HostState{
		HostName:     c.HostName(),
		DaemonID:     daemonID,
		Displays:     []protocol.Display{},
		Mixer:        []protocol.AudioSession{},
		Outputs:      []protocol.AudioDevice{},
		Capabilities: []string{},
	}
	if displays, err := c.Displays(); err == nil {
		state.Displays = displays
		state.Capabilities = append(state.Capabilities, "display")
	}
	if volume, err := c.Volume(); err == nil {
		state.Volume = volume
		state.Capabilities = append(state.Capabilities, "volume")
	}
	if mixerSupported() {
		state.Capabilities = append(state.Capabilities, "mixer")
		if sessions, err := c.Mixer(); err == nil {
			state.Mixer = sessions
		}
	}
	if outputsSupported() {
		state.Capabilities = append(state.Capabilities, "outputs")
		if outputs, err := c.Outputs(); err == nil {
			state.Outputs = outputs
		}
	}
	if mediaSupported() {
		state.Capabilities = append(state.Capabilities, "media")
		if media, err := c.MediaState(); err == nil {
			state.Media = media
		}
	}
	return state
}

func clamp(v, lo, hi int) int {
	if hi < lo {
		lo, hi = hi, lo
	}
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
