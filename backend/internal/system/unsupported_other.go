//go:build !windows

package system

import "switchboard/backend/internal/protocol"

// Phase 1 ships the Windows host integration. These stubs keep the daemon
// compiling and running on macOS and Linux so the pairing, transport and UI
// layers can be developed there; the OS backends land in Phase 3.

type displayController struct{}

func newDisplayController() *displayController { return &displayController{} }

func (c *displayController) List() ([]protocol.Display, error) { return nil, ErrUnsupported }
func (c *displayController) Refresh() error                    { return ErrUnsupported }
func (c *displayController) Close()                            {}

func (c *displayController) SetBrightness(string, int) (protocol.Display, error) {
	return protocol.Display{}, ErrUnsupported
}

func (c *displayController) SetContrast(string, int) (protocol.Display, error) {
	return protocol.Display{}, ErrUnsupported
}

func getVolume() (protocol.Volume, error) { return protocol.Volume{}, ErrUnsupported }

func setVolume(int, bool) (protocol.Volume, error) { return protocol.Volume{}, ErrUnsupported }

func mixerSessions() ([]protocol.AudioSession, error) { return nil, ErrUnsupported }

func setSessionVolume(string, int, bool) ([]protocol.AudioSession, error) {
	return nil, ErrUnsupported
}

func mixerSupported() bool { return false }

func sendMediaCommand(string) error { return ErrUnsupported }

func mediaState() (protocol.MediaState, error) { return protocol.MediaState{}, ErrUnsupported }

func mediaArtwork() (protocol.MediaArtwork, error) { return protocol.MediaArtwork{}, ErrUnsupported }

func mediaSupported() bool { return false }
