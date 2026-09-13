//go:build !windows

package system

import (
	"switchboard/backend/internal/protocol"
)

func sampleMetrics() (protocol.MetricPoint, []protocol.ProcessItem, []protocol.DriveItem, error) {
	return protocol.MetricPoint{}, nil, nil, ErrUnsupported
}
