//go:build !windows

package system

import (
	"switchboard/backend/internal/protocol"
)

func aboutSystem() (protocol.AboutSystemResponse, error) {
	return protocol.AboutSystemResponse{}, ErrUnsupported
}
