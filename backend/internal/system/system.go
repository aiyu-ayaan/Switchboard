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

// Media sends a transport command to the active media session.
func (c *Controller) Media(action string) error { return sendMediaCommand(action) }

// State assembles the snapshot pushed to clients. Individual controls are
// allowed to fail without failing the whole snapshot: a machine with no audio
// endpoint should still be able to drive its monitors.
func (c *Controller) State(daemonID string) protocol.HostState {
	state := protocol.HostState{
		HostName:     c.HostName(),
		DaemonID:     daemonID,
		Displays:     []protocol.Display{},
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
	if mediaSupported() {
		state.Capabilities = append(state.Capabilities, "media")
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
