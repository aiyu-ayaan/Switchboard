package db_test

import (
	"path/filepath"
	"testing"
	"time"

	"switchboard/backend/internal/db"
	"switchboard/backend/internal/protocol"
)

func TestTelemetryStorageAndDownsampling(t *testing.T) {
	dir := t.TempDir()
	database, err := db.Open(filepath.Join(dir, "telemetry_test.db"))
	if err != nil {
		t.Fatalf("db open: %v", err)
	}
	defer database.Close()

	now := time.Now().Unix()
	temp := 52.0

	// Insert 120 points spaced 1 minute apart (spanning 2 hours)
	for i := 119; i >= 0; i-- {
		ts := now - int64(i*60)
		pt := protocol.MetricPoint{
			Timestamp:   ts,
			CPU:         float64(10 + (i % 20)),
			RAMUsed:     8000000000 + uint64(i*1000000),
			RAMTotal:    16000000000,
			GPU:         float64(5 + (i % 10)),
			GPUMemUsed:  2000000000,
			GPUMemTotal: 8000000000,
			DiskRead:    1024,
			DiskWrite:   2048,
			NetRx:       5000,
			NetTx:       1000,
			CPUTemp:     &temp,
			GPUTemp:     &temp,
		}
		if err := database.RecordMetrics(pt); err != nil {
			t.Fatalf("record metric error at %d: %v", i, err)
		}
	}

	// 1. Query "1m"
	pts1m, err := database.QueryMetrics("1m")
	if err != nil {
		t.Fatalf("query 1m: %v", err)
	}
	if len(pts1m) == 0 {
		t.Fatalf("expected at least 1 point for 1m query, got %d", len(pts1m))
	}

	// 2. Query "1h" -> should return roughly 60 points
	pts1h, err := database.QueryMetrics("1h")
	if err != nil {
		t.Fatalf("query 1h: %v", err)
	}
	if len(pts1h) < 55 || len(pts1h) > 65 {
		t.Fatalf("expected ~60 points for 1h query, got %d", len(pts1h))
	}

	// 3. Query "12h" -> spans 2 hours of data with 5-minute buckets (120 min / 5 = ~24 buckets)
	pts12h, err := database.QueryMetrics("12h")
	if err != nil {
		t.Fatalf("query 12h: %v", err)
	}
	if len(pts12h) < 20 || len(pts12h) > 30 {
		t.Fatalf("expected ~24 points for 12h query over 2h data, got %d", len(pts12h))
	}

	// Verify values are aggregated and valid
	for _, p := range pts12h {
		if p.CPU <= 0 || p.RAMUsed <= 0 || p.RAMTotal != 16000000000 {
			t.Fatalf("invalid aggregated point: %+v", p)
		}
		if p.CPUTemp == nil || *p.CPUTemp != 52.0 {
			t.Fatalf("invalid temp in aggregated point: %+v", p)
		}
	}

	// 4. Test Pruning old metrics (> 30 days)
	// Insert an old metric point from 35 days ago
	oldPoint := protocol.MetricPoint{
		Timestamp: now - 35*86400,
		CPU:       50.0,
		RAMUsed:   8000000000,
		RAMTotal:  16000000000,
	}
	if err := database.RecordMetrics(oldPoint); err != nil {
		t.Fatalf("insert old point: %v", err)
	}

	deleted, err := database.PruneOldMetrics(30)
	if err != nil {
		t.Fatalf("prune old metrics: %v", err)
	}
	if deleted < 1 {
		t.Fatalf("expected at least 1 deleted row, got %d", deleted)
	}
}
