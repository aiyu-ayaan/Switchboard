//go:build !windows

package system

import "switchboard/backend/internal/protocol"

func getMicVolume() (protocol.Volume, error)               { return protocol.Volume{}, ErrUnsupported }
func setMicVolume(int, bool) (protocol.Volume, error)      { return protocol.Volume{}, ErrUnsupported }
func audioInputs() ([]protocol.AudioDevice, error)         { return nil, ErrUnsupported }
func setAudioInput(string) ([]protocol.AudioDevice, error) { return nil, ErrUnsupported }
func micSupported() bool                                   { return false }
func inputsSupported() bool                                { return false }
