package server

import (
	"context"
	"log"
	"time"

	"switchboard/backend/internal/protocol"
)

// broadcastTelemetry sends live telemetry to every connected device.
func (s *Server) broadcastTelemetry(pt protocol.MetricPoint, procs []protocol.ProcessItem, drives []protocol.DriveItem, dataUsage []protocol.DataUsageItem) {
	push := protocol.ResourcesLivePush{
		Current:      pt,
		TopProcesses: procs,
		Drives:       drives,
		DataUsage:    dataUsage,
		TotalNetRx:   pt.NetTotalRx,
		TotalNetTx:   pt.NetTotalTx,
	}
	env, err := protocol.New(protocol.TypeEvent, protocol.ActionResourcesLive, push)
	if err != nil {
		return
	}

	s.mu.RLock()
	clients := make([]*client, 0, len(s.clients))
	for _, c := range s.clients {
		clients = append(clients, c)
	}
	s.mu.RUnlock()

	for _, c := range clients {
		c.send(env)
	}
}

// telemetryLoop samples host telemetry every 2s, saves to SQLite every 30s,
// broadcasts live events to connected clients, and runs daily pruning.
//
// The daemon idles on the user's own machine for most of its life, so a tick
// with no phone connected does nothing at all unless it is a persistence tick,
// and that one takes the cheap sample: history stays unbroken while the
// process table, drive table, thermal zone and GPU are left untouched until
// someone is actually watching.
func (s *Server) telemetryLoop(ctx context.Context) {
	sample := func(live bool, persist bool) {
		pt, procs, drives, dataUsage, err := s.control.SampleMetrics(live)
		if err != nil {
			return
		}
		s.recordRecentMetric(pt)
		if persist {
			if err := s.store.RecordMetrics(pt); err != nil {
				log.Printf("telemetry: record metric error: %v", err)
			}
		}
		if live {
			s.broadcastTelemetry(pt, procs, drives, dataUsage)
		}
	}

	// Sample immediately on start to seed history and prime the CPU and
	// network deltas.
	sample(s.clientCount() > 0, true)

	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()

	pruneTicker := time.NewTicker(24 * time.Hour)
	defer pruneTicker.Stop()

	ticks := 0
	for {
		select {
		case <-ctx.Done():
			return
		case <-pruneTicker.C:
			if deleted, err := s.store.PruneOldMetrics(30); err == nil && deleted > 0 {
				log.Printf("telemetry: pruned %d samples older than 30 days", deleted)
			}
		case <-ticker.C:
			ticks++
			// Persist every 30 seconds (15 ticks of 2s).
			persist := ticks%15 == 0
			live := s.clientCount() > 0
			if !live && !persist {
				continue
			}
			sample(live, persist)
		}
	}
}

func (s *Server) recordRecentMetric(pt protocol.MetricPoint) {
	s.metricsMu.Lock()
	defer s.metricsMu.Unlock()
	s.recentMetrics = append(s.recentMetrics, pt)
	cutoff := pt.Timestamp - 60
	start := 0
	for start < len(s.recentMetrics) && s.recentMetrics[start].Timestamp < cutoff {
		start++
	}
	if start > 0 {
		s.recentMetrics = s.recentMetrics[start:]
	}
}

func (s *Server) getRecentMetrics() []protocol.MetricPoint {
	s.metricsMu.RLock()
	defer s.metricsMu.RUnlock()
	res := make([]protocol.MetricPoint, len(s.recentMetrics))
	copy(res, s.recentMetrics)
	return res
}
