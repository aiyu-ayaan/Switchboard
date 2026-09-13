package system_test

import (
	"testing"

	"switchboard/backend/internal/system"
)

func TestSampleMetrics(t *testing.T) {
	c := system.NewController()
	defer c.Close()

	pt, procs, drives, err := c.SampleMetrics()
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

	t.Logf("Sampled metric: CPU=%.1f%%, RAM=%d/%d MB, DiskRead=%d, NetRx=%d, Drives=%d, Procs=%d",
		pt.CPU, pt.RAMUsed/(1024*1024), pt.RAMTotal/(1024*1024), pt.DiskRead, pt.NetRx, len(drives), len(procs))
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
