package server

import (
	"testing"
	"time"

	"switchboard/backend/internal/crypto"
	"switchboard/backend/internal/protocol"
)

func TestTelemetryServer(t *testing.T) {
	srv, ts := newHarness(t)

	// Pre-populate some metrics in the store
	now := time.Now().Unix()
	pt := protocol.MetricPoint{
		Timestamp: now - 30,
		CPU:       25.0,
		RAMUsed:   4000000000,
		RAMTotal:  8000000000,
	}
	if err := srv.store.RecordMetrics(pt); err != nil {
		t.Fatalf("record metric: %v", err)
	}

	phoneID, err := crypto.NewIdentity()
	if err != nil {
		t.Fatal(err)
	}

	pairing, err := srv.RotatePairing()
	if err != nil {
		t.Fatal(err)
	}

	client, err := dial(t, ts, phoneID, modePair, []byte(pairing.Code))
	if err != nil {
		t.Fatalf("dial: %v", err)
	}

	// 1. Send system.resources.query
	respEnv := client.call(t, protocol.ActionResourcesQuery, protocol.ResourcesQuery{Range: "1h"})
	if respEnv.Action != protocol.ActionResourcesQuery {
		t.Fatalf("expected action %s, got %s", protocol.ActionResourcesQuery, respEnv.Action)
	}
	var res protocol.ResourcesResponse
	if err := respEnv.Decode(&res); err != nil {
		t.Fatalf("decode resources response: %v", err)
	}
	if len(res.Points) == 0 {
		t.Fatalf("expected at least 1 metric point, got 0")
	}

	// 2. Send system.about.query
	aboutRespEnv := client.call(t, protocol.ActionAboutQuery, struct{}{})
	if aboutRespEnv.Action != protocol.ActionAboutQuery {
		t.Fatalf("expected action %s, got %s", protocol.ActionAboutQuery, aboutRespEnv.Action)
	}
	var about protocol.AboutSystemResponse
	if err := aboutRespEnv.Decode(&about); err != nil {
		t.Fatalf("decode about response: %v", err)
	}
	if about.OS.Name == "" {
		t.Fatalf("expected non-empty OS name in about response")
	}
}
