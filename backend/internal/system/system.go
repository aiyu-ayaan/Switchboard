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

// ---- Air mouse ----
//
// The phone resolves gestures and sends intents; these four calls are the
// whole host surface. Injection is stateless apart from the sub-pixel
// remainder the platform layer carries between moves.

// MoveMouse nudges the pointer by a relative amount in host pixels.
func (c *Controller) MoveMouse(dx, dy float64) error { return moveMouse(dx, dy) }

// MouseButton presses, releases, clicks, or double-clicks a mouse button.
func (c *Controller) MouseButton(button, action string) error {
	return mouseButton(button, action)
}

// Scroll turns the wheel by a number of notches, optionally with control held
// so the focused application reads it as zoom.
func (c *Controller) Scroll(dx, dy float64, ctrl bool) error { return scrollMouse(dx, dy, ctrl) }

// ShellGesture triggers one named window-manager gesture.
func (c *Controller) ShellGesture(name string) error { return shellGesture(name) }

// Lock locks the workstation display.
func (c *Controller) Lock() error { return lockSystem() }

// IsLocked reports whether the workstation console session is locked.
func (c *Controller) IsLocked() bool { return isLocked() }

// Power executes a host power command (display_off, sleep, shutdown, abort_shutdown).
func (c *Controller) Power(action string, seconds int) error {
	return powerAction(action, seconds)
}

// Mic reads the host default capture endpoint volume and mute state.
func (c *Controller) Mic() (protocol.Volume, error) {
	return getMicVolume()
}

// SetMic sets the host default capture endpoint volume and mute state.
func (c *Controller) SetMic(level int, muted bool) (protocol.Volume, error) {
	return setMicVolume(level, muted)
}

// Inputs lists the audio recording endpoints the host can capture from.
func (c *Controller) Inputs() ([]protocol.AudioDevice, error) {
	return audioInputs()
}

// SetInput routes host audio capture to one endpoint and returns the refreshed list.
func (c *Controller) SetInput(id string) ([]protocol.AudioDevice, error) {
	return setAudioInput(id)
}

// InputText injects unicode text as keystrokes.
func (c *Controller) InputText(text string) error {
	return inputText(text)
}

// SetClipboard sets the host clipboard text.
func (c *Controller) SetClipboard(text string) error {
	return setClipboard(text)
}

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
		Inputs:       []protocol.AudioDevice{},
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
	if inputSupported() {
		state.Capabilities = append(state.Capabilities, "input")
	}
	if mediaSupported() {
		state.Capabilities = append(state.Capabilities, "media")
		if media, err := c.MediaState(); err == nil {
			state.Media = media
		}
	}
	if lockSupported() {
		state.Capabilities = append(state.Capabilities, "lock")
		state.Locked = isLocked()
	}
	if powerSupported() {
		state.Capabilities = append(state.Capabilities, "power")
	}
	if micSupported() {
		state.Capabilities = append(state.Capabilities, "mic")
		if mic, err := c.Mic(); err == nil {
			state.Mic = mic
		}
	}
	if inputsSupported() {
		state.Capabilities = append(state.Capabilities, "inputs")
		if inputs, err := c.Inputs(); err == nil {
			state.Inputs = inputs
		}
	}
	if keyboardSupported() {
		state.Capabilities = append(state.Capabilities, "keyboard")
	}
	if clipboardSupported() {
		state.Capabilities = append(state.Capabilities, "clipboard")
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
