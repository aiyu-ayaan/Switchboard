package discovery

import (
	"fmt"

	"github.com/libp2p/zeroconf/v2"
)

// Advertise publishes the daemon as a DNS-SD service on every usable
// interface. The caller stops it on shutdown.
//
// A registration failure is worth reporting but never fatal: a firewall that
// blocks multicast costs discovery, not the daemon, and pairing by typed
// address must keep working on a network that will not carry mDNS at all.
func Advertise(daemonID, hostName string, port int) (*Advertiser, error) {
	if err := validate(daemonID, port); err != nil {
		return nil, err
	}
	server, err := zeroconf.Register(
		instanceName(hostName),
		Service,
		Domain,
		port,
		txtRecord(daemonID, hostName),
		nil, // every multicast-capable interface
	)
	if err != nil {
		return nil, fmt.Errorf("discovery: register %s: %w", Service, err)
	}
	return &Advertiser{stop: server.Shutdown}, nil
}
