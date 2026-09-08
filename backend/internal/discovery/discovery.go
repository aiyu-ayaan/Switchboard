// Package discovery advertises the daemon on the local network over
// mDNS/DNS-SD, so a phone can find a desktop without being told its address.
//
// Discovery carries no authority. The record says "a Switchboard daemon with
// this id answers here"; it does not say the daemon is trustworthy, and
// nothing in the pairing handshake trusts it. A phone still proves the pairing
// code and pins the host key it saw during the handshake, so a hostile machine
// advertising a stolen daemon id gets a failed handshake rather than a
// connection. That is why the host's public key is deliberately absent from
// the TXT record: publishing it would imply an authority the record does not
// have, and the handshake establishes the real key anyway.
package discovery

import (
	"fmt"
	"strconv"
	"sync"
)

// Service is the DNS-SD service type Switchboard daemons register under.
const Service = "_switchboard._tcp"

// Domain is the mDNS domain; DNS-SD over multicast is always "local.".
const Domain = "local."

// TXT record keys. Clients match a discovered record to a stored host by
// TXTDaemonID and show TXTHostName until they have connected.
const (
	TXTVersion  = "v"
	TXTDaemonID = "id"
	TXTHostName = "name"

	// Version is bumped if the record's meaning changes, so an old client can
	// ignore a record it would misread rather than half-understand it.
	Version = 1
)

// Advertiser publishes the daemon until it is stopped. The registration behind
// it is replaced whenever the host changes network, so `stop` is guarded.
type Advertiser struct {
	mu     sync.Mutex
	stop   func()
	done   chan struct{}
	closed bool
}

// swap installs a fresh registration and withdraws the one it replaces.
func (a *Advertiser) swap(stop func()) {
	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		stop()
		return
	}
	old := a.stop
	a.stop = stop
	a.mu.Unlock()
	if old != nil {
		old()
	}
}

// Stop withdraws the advertisement, sending the DNS-SD goodbye packets so
// clients drop the host immediately rather than waiting for the TTL.
func (a *Advertiser) Stop() {
	if a == nil {
		return
	}
	a.mu.Lock()
	if a.closed {
		a.mu.Unlock()
		return
	}
	a.closed = true
	stop := a.stop
	a.stop = nil
	if a.done != nil {
		close(a.done)
	}
	a.mu.Unlock()
	if stop != nil {
		stop()
	}
}

func txtRecord(daemonID, hostName string) []string {
	return []string{
		TXTVersion + "=" + strconv.Itoa(Version),
		TXTDaemonID + "=" + daemonID,
		TXTHostName + "=" + hostName,
	}
}

// instanceName is what a service browser shows before it resolves anything.
// The machine name is the only label a user would recognise; DNS-SD allows the
// full UTF-8 range here, so it needs no sanitising, only a length bound.
func instanceName(hostName string) string {
	if hostName == "" {
		return "Switchboard host"
	}
	const maxInstanceBytes = 63 // RFC 6763 §4.1.1
	if len(hostName) > maxInstanceBytes {
		return hostName[:maxInstanceBytes]
	}
	return hostName
}

func validate(daemonID string, port int) error {
	if daemonID == "" {
		return fmt.Errorf("discovery: daemon id is required")
	}
	if port <= 0 || port > 65535 {
		return fmt.Errorf("discovery: port %d is out of range", port)
	}
	return nil
}
