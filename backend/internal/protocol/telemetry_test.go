package protocol_test

import (
	"encoding/json"
	"testing"

	"switchboard/backend/internal/protocol"
)

func TestTelemetrySerialization(t *testing.T) {
	temp := 45.5
	pt := protocol.MetricPoint{
		Timestamp:   1700000000,
		CPU:         15.5,
		RAMUsed:     8589934592,
		RAMTotal:    17179869184,
		GPU:         22.0,
		GPUMemUsed:  2147483648,
		GPUMemTotal: 8589934592,
		DiskRead:    1048576,
		DiskWrite:   2097152,
		NetRx:       524288,
		NetTx:       131072,
		CPUTemp:     &temp,
		GPUTemp:     &temp,
	}

	res := protocol.ResourcesResponse{
		Range:  "1h",
		Points: []protocol.MetricPoint{pt},
	}

	data, err := json.Marshal(res)
	if err != nil {
		t.Fatalf("marshal error: %v", err)
	}

	var decoded protocol.ResourcesResponse
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("unmarshal error: %v", err)
	}

	if decoded.Range != "1h" || len(decoded.Points) != 1 {
		t.Fatalf("unexpected decoded response: %+v", decoded)
	}
	if decoded.Points[0].CPU != 15.5 || *decoded.Points[0].CPUTemp != 45.5 {
		t.Fatalf("unexpected decoded point: %+v", decoded.Points[0])
	}

	about := protocol.AboutSystemResponse{
		OS: protocol.OsInfo{
			Name:          "Windows 11 Pro",
			Build:         "22631",
			UptimeSeconds: 3600,
		},
		CPU: protocol.CpuInfo{
			Model:        "AMD Ryzen 9",
			Cores:        12,
			Threads:      24,
			BaseClockMhz: 3700,
		},
		Memory: protocol.MemoryInfo{
			TotalBytes: 34359738368,
			Type:       "DDR5",
			Slots:      2,
		},
		GPUs: []protocol.GpuInfo{
			{Name: "RTX 4080", Driver: "550.00", VRAMBytes: 17179869184},
		},
		Drives: []protocol.DriveItem{
			{Device: "C:", Label: "System", TotalBytes: 1000000000, FreeBytes: 500000000},
		},
		Battery: protocol.BatteryInfo{
			Present:            true,
			Charging:           true,
			Percent:            92,
			WearPercent:        3.5,
			CycleCount:         28,
			DesignCapacityMwh:  75000,
			FullCapacityMwh:    72375,
		},
	}

	aboutData, err := json.Marshal(about)
	if err != nil {
		t.Fatalf("marshal about error: %v", err)
	}

	var decodedAbout protocol.AboutSystemResponse
	if err := json.Unmarshal(aboutData, &decodedAbout); err != nil {
		t.Fatalf("unmarshal about error: %v", err)
	}

	if decodedAbout.Battery.CycleCount != 28 || decodedAbout.CPU.Cores != 12 {
		t.Fatalf("unexpected decoded about: %+v", decodedAbout)
	}
}
