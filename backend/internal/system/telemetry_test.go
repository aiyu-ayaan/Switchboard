package system_test

import (
	"runtime"
	"testing"
	"time"

	"switchboard/backend/internal/system"
)

func TestSampleMetrics(t *testing.T) {
	c := system.NewController()
	defer c.Close()

	pt, procs, drives, dataUsage, err := c.SampleMetrics()
	if err != nil {
		t.Fatalf("SampleMetrics failed: %v", err)
	}

	if pt.RAMTotal == 0 {
		t.Errorf("expected non-zero RAMTotal, got %d", pt.RAMTotal)
	}
	if pt.RAMUsed == 0 {
		t.Errorf("expected non-zero RAMUsed, got %d", pt.RAMUsed)
	}
	if pt.Timestamp == 0 {
		t.Errorf("expected non-zero Timestamp, got %d", pt.Timestamp)
	}

	t.Logf("Sampled metric: CPU=%.1f%%, RAM=%d/%d MB, NetRx=%d B/s, NetTotalRx=%d bytes, Drives=%d, Procs=%d, DataUsageApps=%d",
		pt.CPU, pt.RAMUsed/(1024*1024), pt.RAMTotal/(1024*1024), pt.NetRx, pt.NetTotalRx, len(drives), len(procs), len(dataUsage))

	if len(dataUsage) > 0 {
		t.Logf("Top data usage app: %s (PID %d) - Total: %d bytes (Rx: %d, Tx: %d)",
			dataUsage[0].Name, dataUsage[0].PID, dataUsage[0].TotalBytes, dataUsage[0].RxBytes, dataUsage[0].TxBytes)
	}
}

func TestAboutSystem(t *testing.T) {
	c := system.NewController()
	defer c.Close()

	about, err := c.AboutSystem()
	if err != nil {
		t.Fatalf("AboutSystem failed: %v", err)
	}

	if about.OS.Name == "" {
		t.Errorf("expected OS name, got empty string")
	}
	if about.CPU.Model == "" {
		t.Errorf("expected CPU model, got empty string")
	}
	if about.CPU.Cores == 0 {
		t.Errorf("expected non-zero CPU cores, got %d", about.CPU.Cores)
	}
	if about.Memory.TotalBytes == 0 {
		t.Errorf("expected non-zero TotalBytes, got %d", about.Memory.TotalBytes)
	}

	t.Logf("About System: OS=%s (%s), CPU=%s (%d cores, %d threads), RAM=%d MB, BatteryPresent=%v",
		about.OS.Name, about.OS.Build, about.CPU.Model, about.CPU.Cores, about.CPU.Threads,
		about.Memory.TotalBytes/(1024*1024), about.Battery.Present)
}

// TestSampleMetricsCachesSlowSources guards the sampling cadence: the live
// loop calls SampleMetrics every 2s, so once the slow sources (nvidia-smi,
// WMI thermal, the process table, drive capacity) are primed, a tick must
// cost a handful of syscalls rather than tens of milliseconds of host CPU.
func TestSampleMetricsCachesSlowSources(t *testing.T) {
	if runtime.GOOS != "windows" {
		t.Skip("telemetry sampling is only implemented on windows")
	}
	c := system.NewController()
	defer c.Close()

	if _, _, _, _, err := c.SampleMetrics(); err != nil {
		t.Fatalf("priming sample: %v", err)
	}

	// Generous bound: a cached sample measures ~1.5ms, an uncached one ~80ms.
	const maxCached = 25 * time.Millisecond
	for i := range 3 {
		start := time.Now()
		if _, _, _, _, err := c.SampleMetrics(); err != nil {
			t.Fatalf("sample %d: %v", i, err)
		}
		if elapsed := time.Since(start); elapsed > maxCached {
			t.Errorf("cached sample %d took %v, want under %v: slow sources are being resampled every tick", i, elapsed, maxCached)
		}
	}
}
