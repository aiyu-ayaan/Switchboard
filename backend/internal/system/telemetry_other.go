//go:build !windows

package system

import (
	"switchboard/backend/internal/protocol"
)

func sampleMetrics() (protocol.MetricPoint, []protocol.ProcessItem, []protocol.DriveItem, []protocol.DataUsageItem, error) {
	return protocol.MetricPoint{}, nil, nil, nil, ErrUnsupported
}
