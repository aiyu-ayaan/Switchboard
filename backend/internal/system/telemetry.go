package system

import (
	"switchboard/backend/internal/protocol"
)

// SampleMetrics gathers the current system performance snapshot, top processes, drive list, and app data usage.
func (c *Controller) SampleMetrics() (protocol.MetricPoint, []protocol.ProcessItem, []protocol.DriveItem, []protocol.DataUsageItem, error) {
	return sampleMetrics()
}

// AboutSystem reads the host hardware details, operating system build, and battery health.
func (c *Controller) AboutSystem() (protocol.AboutSystemResponse, error) {
	return aboutSystem()
}
