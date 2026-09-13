package system

import (
	"switchboard/backend/internal/protocol"
)

// SampleMetrics gathers the current system performance snapshot, top processes,
// drive list, and app data usage.
//
// full reports whether anything is watching. When it is false only the cheap
// counters (CPU, memory, network) are read and the process, drive, thermal and
// GPU sources are left alone, so a host with no phone connected still records
// an unbroken history without paying for detail nobody will see.
func (c *Controller) SampleMetrics(full bool) (protocol.MetricPoint, []protocol.ProcessItem, []protocol.DriveItem, []protocol.DataUsageItem, error) {
	return sampleMetrics(full)
}

// AboutSystem reads the host hardware details, operating system build, and battery health.
func (c *Controller) AboutSystem() (protocol.AboutSystemResponse, error) {
	return aboutSystem()
}
