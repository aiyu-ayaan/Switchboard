package server

import (
	"net"
	"sync"
	"time"
)

// Handshake rate limiting, keyed on the peer's IP.
//
// Every attempt on /ws costs the daemon an X25519 double DH plus HKDF before
// it can say no, and the pairing code is only 50 bits, so an unauthenticated
// peer that can open sockets as fast as it likes gets both a brute-force
// channel and a free CPU burn. The bucket is per address, so one hostile
// source cannot starve a phone reconnecting from another.
//
// Limits are constants, not config: a phone reconnects a handful of times a
// day, so anything above a slow trickle is not a user.
const (
	// handshakeBurst is what a single address may spend back-to-back — enough
	// for a user fumbling the typed code a few times over flaky Wi-Fi.
	handshakeBurst = 10
	// handshakeRefill is how long one attempt takes to come back, so a
	// saturated attacker is held to 10 tries a minute.
	handshakeRefill = 6 * time.Second
	// handshakeIdleTTL is how long an address is remembered after its last
	// attempt. Anything older is evicted so the map cannot grow without bound.
	handshakeIdleTTL = 10 * time.Minute
)

// handshakeLimiter is a token bucket per source address. The zero value is
// ready to use.
type handshakeLimiter struct {
	mu        sync.Mutex
	buckets   map[string]*handshakeBucket
	nextSweep time.Time
}

type handshakeBucket struct {
	tokens float64
	seen   time.Time
}

// allow spends one token for addr, reporting whether the attempt may proceed.
// Callers must run this before any crypto work — that is the entire point.
func (l *handshakeLimiter) allow(addr string) bool {
	now := time.Now()

	l.mu.Lock()
	defer l.mu.Unlock()
	if l.buckets == nil {
		l.buckets = map[string]*handshakeBucket{}
	}
	l.sweepLocked(now)

	b := l.buckets[addr]
	if b == nil {
		b = &handshakeBucket{tokens: handshakeBurst}
		l.buckets[addr] = b
	}
	b.tokens += now.Sub(b.seen).Seconds() / handshakeRefill.Seconds()
	if b.tokens > handshakeBurst {
		b.tokens = handshakeBurst
	}
	b.seen = now

	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

// succeed clears the bucket after a handshake that authenticated. Resetting on
// success is chosen over charging failures extra because it is the same one
// line and it means a device that keeps proving itself is never throttled,
// while an attacker — who by definition never succeeds — only ever refills at
// handshakeRefill.
func (l *handshakeLimiter) succeed(addr string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	delete(l.buckets, addr)
}

// sweepLocked drops addresses that stopped connecting. It walks the whole map,
// which is fine at LAN scale, and runs at most once per idle window so a flood
// of distinct addresses cannot turn every attempt into an O(n) scan.
//
// ponytail: full scan on a timer; a heap keyed on expiry if this ever hosts
// more than a handful of peers.
func (l *handshakeLimiter) sweepLocked(now time.Time) {
	if now.Before(l.nextSweep) {
		return
	}
	l.nextSweep = now.Add(handshakeIdleTTL)
	for addr, b := range l.buckets {
		if now.Sub(b.seen) > handshakeIdleTTL {
			delete(l.buckets, addr)
		}
	}
}

// peerIP is the limiter key: the remote address with the port stripped, which
// also normalises the bracketed IPv6 form. X-Forwarded-For is deliberately
// ignored — this is a LAN daemon, and trusting a header a client writes would
// hand every attacker an unlimited supply of buckets.
func peerIP(remoteAddr string) string {
	if host, _, err := net.SplitHostPort(remoteAddr); err == nil {
		return host
	}
	return remoteAddr
}
