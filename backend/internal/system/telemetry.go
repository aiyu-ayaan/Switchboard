package system

import (
	"switchboard/backend/internal/protocol"
)

// SampleMetrics gathers the current system performance snapshot, top processes, and drive list.
func (c *Controller) SampleMetrics() (protocol.MetricPoint, []protocol.ProcessItem, []protocol.DriveItem, error) {
	return sampleMetrics()
}

// AboutSystem reads the host hardware details, operating system build, and battery health.
func (c *Controller) AboutSystem() (protocol.AboutSystemResponse, error) {
	return aboutSystem()
}
