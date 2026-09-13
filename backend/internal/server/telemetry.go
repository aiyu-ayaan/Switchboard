package server

import (
	"context"
	"log"
	"time"

	"switchboard/backend/internal/protocol"
)

// broadcastTelemetry sends live telemetry to every connected device.
func (s *Server) broadcastTelemetry(pt protocol.MetricPoint, procs []protocol.ProcessItem, drives []protocol.DriveItem) {
	push := protocol.ResourcesLivePush{
		Current:      pt,
		TopProcesses: procs,
		Drives:       drives,
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

// telemetryLoop samples host telemetry every 60s, saves it to SQLite,
// broadcasts live events to connected clients, and runs daily pruning.
func (s *Server) telemetryLoop(ctx context.Context) {
	// Sample immediately on start
	if pt, procs, drives, err := s.control.SampleMetrics(); err == nil {
		_ = s.store.RecordMetrics(pt)
		s.broadcastTelemetry(pt, procs, drives)
	}

	ticker := time.NewTicker(60 * time.Second)
	defer ticker.Stop()

	pruneTicker := time.NewTicker(24 * time.Hour)
	defer pruneTicker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-pruneTicker.C:
			if deleted, err := s.store.PruneOldMetrics(30); err == nil && deleted > 0 {
				log.Printf("telemetry: pruned %d samples older than 30 days", deleted)
			}
		case <-ticker.C:
			pt, procs, drives, err := s.control.SampleMetrics()
			if err != nil {
				continue
			}
			if err := s.store.RecordMetrics(pt); err != nil {
				log.Printf("telemetry: record metric error: %v", err)
			}
			s.broadcastTelemetry(pt, procs, drives)
		}
	}
}
