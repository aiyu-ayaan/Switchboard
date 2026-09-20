//go:build !windows && !linux

package system

import "switchboard/backend/internal/protocol"

// Audio stubs for platforms without a host mixer backend. The Windows
// implementation lives in audio_windows.go, mixer_windows.go, endpoints_windows.go
// and mic_windows.go.

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

func getMicVolume() (protocol.Volume, error) { return protocol.Volume{}, ErrUnsupported }

func setMicVolume(int, bool) (protocol.Volume, error) { return protocol.Volume{}, ErrUnsupported }

func audioInputs() ([]protocol.AudioDevice, error) { return nil, ErrUnsupported }

func setAudioInput(string) ([]protocol.AudioDevice, error) { return nil, ErrUnsupported }

func micSupported() bool { return false }

func inputsSupported() bool { return false }
