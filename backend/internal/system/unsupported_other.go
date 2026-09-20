//go:build !windows

package system

import "switchboard/backend/internal/protocol"

// Phase 1 ships the Windows host integration, and display control now also runs
// on Linux (display_linux.go). These stubs keep the daemon compiling and running
// everywhere else so the pairing, transport and UI layers can be developed
// there; the remaining OS backends land in Phase 3.

func getVolume() (protocol.Volume, error) { return protocol.Volume{}, ErrUnsupported }

func setVolume(int, bool) (protocol.Volume, error) { return protocol.Volume{}, ErrUnsupported }

func mixerSessions() ([]protocol.AudioSession, error) { return nil, ErrUnsupported }

func setSessionVolume(string, int, bool) ([]protocol.AudioSession, error) {
	return nil, ErrUnsupported
}

func mixerSupported() bool { return false }

func audioOutputs() ([]protocol.AudioDevice, error) { return nil, ErrUnsupported }

func setAudioOutput(string) ([]protocol.AudioDevice, error) { return nil, ErrUnsupported }

func outputsSupported() bool { return false }

func sendMediaCommand(string) error { return ErrUnsupported }

func mediaState() (protocol.MediaState, error) { return protocol.MediaState{}, ErrUnsupported }

func mediaArtwork() (protocol.MediaArtwork, error) { return protocol.MediaArtwork{}, ErrUnsupported }

func mediaSupported() bool { return false }

func inputSupported() bool { return false }

func moveMouse(float64, float64) error { return ErrUnsupported }

func mouseButton(string, string) error { return ErrUnsupported }

func scrollMouse(float64, float64, bool) error { return ErrUnsupported }

func shellGesture(string) error { return ErrUnsupported }

func lockSystem() error { return ErrUnsupported }

func lockSupported() bool { return false }

func isLocked() bool { return false }
