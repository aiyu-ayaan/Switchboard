package discovery

import (
	"fmt"
	"log"
	"net"
	"time"

	"github.com/libp2p/zeroconf/v2"
)

// followInterval is how often the advertised address is checked against the
// one the host would actually route through. Cheap enough to run forever: it
// is a UDP socket setup, no packet leaves the machine.
const followInterval = 5 * time.Second

// Advertise publishes the daemon as a DNS-SD service on every usable
// interface, and republishes it whenever the host's LAN address changes. The
// caller stops it on shutdown.
//
// zeroconf reads the interface addresses once, at registration. A desktop that
// joined another Wi-Fi network therefore kept answering queries with the
// address it had on the old one, so a phone paired earlier saw a record that
// pointed nowhere and could not reconnect without pairing again. Following the
// address is what makes a pairing survive a network change.
//
// A registration failure is worth reporting but never fatal: a firewall that
// blocks multicast costs discovery, not the daemon, and pairing by typed
// address must keep working on a network that will not carry mDNS at all.
func Advertise(daemonID, hostName string, port int) (*Advertiser, error) {
	if err := validate(daemonID, port); err != nil {
		return nil, err
	}
	stop, err := register(daemonID, hostName, port)
	if err != nil {
		return nil, err
	}
	a := &Advertiser{stop: stop, done: make(chan struct{})}
	go a.follow(daemonID, hostName, port)
	return a, nil
}

func register(daemonID, hostName string, port int) (func(), error) {
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
	return server.Shutdown, nil
}

// follow re-registers whenever the outbound address changes. Polling rather
// than a link-state watcher: the check is trivial and behaves identically on
// Windows, macOS and Linux, where interface notifications do not.
func (a *Advertiser) follow(daemonID, hostName string, port int) {
	current := OutboundIP()
	ticker := time.NewTicker(followInterval)
	defer ticker.Stop()

	for {
		select {
		case <-a.done:
			return
		case <-ticker.C:
			ip := OutboundIP()
			if ip == current {
				continue
			}
			current = ip
			stop, err := register(daemonID, hostName, port)
			if err != nil {
				// Keep the old registration rather than going silent; the next
				// tick tries again.
				log.Printf("mDNS re-advertisement after move to %s failed: %v", ip, err)
				continue
			}
			a.swap(stop)
		}
	}
}

// OutboundIP is the LAN address a phone on the same network can reach. It
// dials an off-machine address without sending anything, which makes the OS
// pick the interface it would actually route through.
func OutboundIP() string {
	conn, err := net.Dial("udp", "192.0.2.1:9") // TEST-NET-1, never routed
	if err != nil {
		return "127.0.0.1"
	}
	defer conn.Close()
	if addr, ok := conn.LocalAddr().(*net.UDPAddr); ok {
		return addr.IP.String()
	}
	return "127.0.0.1"
}
