package protocol

// Telemetry action constants.
const (
	ActionResourcesQuery = "system.resources.query"
	ActionResourcesLive  = "system.resources.live"
	ActionAboutQuery     = "system.about.query"
)

// MetricPoint represents a single host resource telemetry snapshot or rolled-up point.
type MetricPoint struct {
	Timestamp   int64    `json:"timestamp"`
	CPU         float64  `json:"cpu"`         // 0 - 100%
	RAMUsed     uint64   `json:"ramUsed"`     // bytes
	RAMTotal    uint64   `json:"ramTotal"`    // bytes
	GPU         float64  `json:"gpu"`         // 0 - 100%
	GPUMemUsed  uint64   `json:"gpuMemUsed"`  // bytes
	GPUMemTotal uint64   `json:"gpuMemTotal"` // bytes
	DiskRead    uint64   `json:"diskRead"`    // bytes/sec
	DiskWrite   uint64   `json:"diskWrite"`   // bytes/sec
	NetRx       uint64   `json:"netRx"`       // bytes/sec
	NetTx       uint64   `json:"netTx"`       // bytes/sec
	CPUTemp     *float64 `json:"cpuTemp,omitempty"`
	GPUTemp     *float64 `json:"gpuTemp,omitempty"`
}

// ProcessItem describes one active OS process.
type ProcessItem struct {
	Name     string  `json:"name"`
	PID      int     `json:"pid"`
	CPU      float64 `json:"cpu"`      // percent
	RAMBytes uint64  `json:"ramBytes"` // working set bytes
}

// DriveItem describes a storage partition or drive volume.
type DriveItem struct {
	Device     string `json:"device"` // e.g. "C:"
	Label      string `json:"label"`
	TotalBytes uint64 `json:"totalBytes"`
	FreeBytes  uint64 `json:"freeBytes"`
}

// ResourcesQuery requests historical metrics over a specified range.
type ResourcesQuery struct {
	Range string `json:"range"` // "1m", "1h", "12h", "24h", "1w", "30d"
}

// ResourcesResponse carries historical time-series points.
type ResourcesResponse struct {
	Range  string        `json:"range"`
	Points []MetricPoint `json:"points"`
}

// ResourcesLivePush delivers the latest 1-min snapshot and top processes.
type ResourcesLivePush struct {
	Current      MetricPoint   `json:"current"`
	TopProcesses []ProcessItem `json:"topProcesses"`
	Drives       []DriveItem   `json:"drives"`
}

// BatteryInfo details the host's battery health and power supply state.
type BatteryInfo struct {
	Present           bool    `json:"present"`
	Charging          bool    `json:"charging"`
	Percent           int     `json:"percent"`
	WearPercent       float64 `json:"wearPercent"`
	CycleCount        int     `json:"cycleCount"`
	DesignCapacityMwh uint64  `json:"designCapacityMwh"`
	FullCapacityMwh   uint64  `json:"fullCapacityMwh"`
}

// CpuInfo contains host CPU topology and clock specifications.
type CpuInfo struct {
	Model        string `json:"model"`
	Cores        int    `json:"cores"`
	Threads      int    `json:"threads"`
	BaseClockMhz int    `json:"baseClockMhz"`
}

// GpuInfo contains graphics adapter specs.
type GpuInfo struct {
	Name      string `json:"name"`
	Driver    string `json:"driver"`
	VRAMBytes uint64 `json:"vramBytes"`
}

// MemoryInfo contains physical memory layout.
type MemoryInfo struct {
	TotalBytes uint64 `json:"totalBytes"`
	Type       string `json:"type"`
	Slots      int    `json:"slots"`
}

// OsInfo contains host operating system build and runtime info.
type OsInfo struct {
	Name          string `json:"name"`
	Build         string `json:"build"`
	UptimeSeconds uint64 `json:"uptimeSeconds"`
}

// AboutSystemResponse is the complete payload for system.about.query.
type AboutSystemResponse struct {
	OS      OsInfo      `json:"os"`
	CPU     CpuInfo     `json:"cpu"`
	Memory  MemoryInfo  `json:"memory"`
	GPUs    []GpuInfo   `json:"gpus"`
	Drives  []DriveItem `json:"drives"`
	Battery BatteryInfo `json:"battery"`
}
