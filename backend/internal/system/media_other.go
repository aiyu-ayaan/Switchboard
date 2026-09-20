//go:build !windows && !linux

package system

import "switchboard/backend/internal/protocol"

// Media transport stubs. The Windows implementation, which drives the
// GlobalSystemMediaTransportControls session, lives in media_windows.go.

func sendMediaCommand(string) error { return ErrUnsupported }

func mediaState() (protocol.MediaState, error) { return protocol.MediaState{}, ErrUnsupported }

func mediaArtwork() (protocol.MediaArtwork, error) { return protocol.MediaArtwork{}, ErrUnsupported }

func mediaSupported() bool { return false }
