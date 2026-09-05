package server

import (
	"testing"
	"time"
)

func TestHandshakeLimiterBurstRefillAndIsolation(t *testing.T) {
	var l handshakeLimiter

	for i := 0; i < handshakeBurst; i++ {
		if !l.allow("10.0.0.1") {
			t.Fatalf("attempt %d inside the burst was rejected", i+1)
		}
	}
	if l.allow("10.0.0.1") {
		t.Fatal("the attempt past the burst was allowed")
	}
	// A different address must not be starved by the first one.
	if !l.allow("10.0.0.2") {
		t.Fatal("a second address was throttled by the first one's spending")
	}

	// One refill window buys exactly one attempt back.
	l.buckets["10.0.0.1"].seen = time.Now().Add(-handshakeRefill)
	if !l.allow("10.0.0.1") {
		t.Fatal("no attempt came back after one refill window")
	}
	if l.allow("10.0.0.1") {
		t.Fatal("the refill handed back more than one attempt")
	}

	// A handshake that authenticated clears the debt.
	l.succeed("10.0.0.1")
	if !l.allow("10.0.0.1") {
		t.Fatal("a successful handshake did not reset the bucket")
	}
}

func TestHandshakeLimiterEvictsIdleAddresses(t *testing.T) {
	var l handshakeLimiter
	l.allow("10.0.0.1")
	l.buckets["10.0.0.1"].seen = time.Now().Add(-2 * handshakeIdleTTL)
	l.nextSweep = time.Time{} // the sweep is on a timer; make it due

	l.allow("10.0.0.2")
	if _, ok := l.buckets["10.0.0.1"]; ok {
		t.Fatal("an address that stopped connecting was never evicted")
	}
}

func TestPeerIPStripsPort(t *testing.T) {
	for addr, want := range map[string]string{
		"192.168.1.5:51234":  "192.168.1.5",
		"[fe80::1%eth0]:443": "fe80::1%eth0",
		"192.168.1.5":        "192.168.1.5", // no port: key on it verbatim
	} {
		if got := peerIP(addr); got != want {
			t.Errorf("peerIP(%q) = %q, want %q", addr, got, want)
		}
	}
}
