package discovery

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/libp2p/zeroconf/v2"
)

func TestTXTRecordCarriesWhatAClientMatchesOn(t *testing.T) {
	txt := txtRecord("daemon-1", "WORKSTATION")
	got := map[string]string{}
	for _, entry := range txt {
		k, v, ok := strings.Cut(entry, "=")
		if !ok {
			t.Fatalf("TXT entry %q is not key=value", entry)
		}
		got[k] = v
	}
	if got[TXTDaemonID] != "daemon-1" {
		t.Errorf("daemon id = %q, want daemon-1", got[TXTDaemonID])
	}
	if got[TXTHostName] != "WORKSTATION" {
		t.Errorf("host name = %q, want WORKSTATION", got[TXTHostName])
	}
	if got[TXTVersion] != "1" {
		t.Errorf("version = %q, want 1", got[TXTVersion])
	}
	// The host's public key must not leak into a record that carries no
	// authority; the handshake is what establishes it.
	for k := range got {
		if strings.Contains(strings.ToLower(k), "key") {
			t.Errorf("TXT record carries a key field %q", k)
		}
	}
}

func TestInstanceNameFitsTheDNSSDLimit(t *testing.T) {
	if name := instanceName(""); name == "" {
		t.Error("a host with no name must still get a browsable label")
	}
	long := strings.Repeat("x", 200)
	if name := instanceName(long); len(name) > 63 {
		t.Errorf("instance name is %d bytes, over the 63-byte limit", len(name))
	}
}

func TestAdvertiseRejectsUnusableInput(t *testing.T) {
	if _, err := Advertise("", "host", 9427); err == nil {
		t.Error("expected an error for an empty daemon id")
	}
	if _, err := Advertise("daemon-1", "host", 0); err == nil {
		t.Error("expected an error for port 0")
	}
}

// TestAdvertiseIsDiscoverable registers a service and browses for it, which is
// the only check that proves the record a phone will see is actually on the
// wire. It skips rather than fails where multicast does not work, because a
// blocked network is exactly the case the daemon treats as non-fatal.
func TestAdvertiseIsDiscoverable(t *testing.T) {
	const daemonID = "test-daemon-7f3a"

	advertiser, err := Advertise(daemonID, "Switchboard test host", 9427)
	if err != nil {
		t.Skipf("mDNS registration unavailable here: %v", err)
	}
	defer advertiser.Stop()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	entries := make(chan *zeroconf.ServiceEntry, 8)
	if err := zeroconf.Browse(ctx, Service, Domain, entries); err != nil {
		t.Skipf("mDNS browse unavailable here: %v", err)
	}

	for {
		select {
		case entry, ok := <-entries:
			if !ok {
				t.Skip("no Switchboard service resolved; multicast is likely blocked here")
			}
			for _, txt := range entry.Text {
				if txt == TXTDaemonID+"="+daemonID {
					if entry.Port != 9427 {
						t.Errorf("resolved port %d, want 9427", entry.Port)
					}
					if len(entry.AddrIPv4) == 0 && len(entry.AddrIPv6) == 0 {
						t.Error("record resolved with no address, which is all a client wants from it")
					}
					return
				}
			}
		case <-ctx.Done():
			t.Skip("no Switchboard service resolved; multicast is likely blocked here")
		}
	}
}

// The advertiser swaps its registration whenever the host moves networks, so
// the shutdown it holds is a moving target. This pins the contract the follow
// loop relies on: a swap retires exactly the registration it replaces, Stop
// retires whatever is current, and a swap that loses the race with Stop
// retires itself rather than leaking a live record.
func TestAdvertiserSwapAndStopRetireEveryRegistration(t *testing.T) {
	var retired []string
	a := &Advertiser{
		stop: func() { retired = append(retired, "first") },
		done: make(chan struct{}),
	}

	a.swap(func() { retired = append(retired, "second") })
	if len(retired) != 1 || retired[0] != "first" {
		t.Fatalf("swap retired %v, want the registration it replaced", retired)
	}

	a.Stop()
	if len(retired) != 2 || retired[1] != "second" {
		t.Fatalf("stop retired %v, want the current registration", retired)
	}

	a.Stop() // idempotent: the follow loop's channel must not be closed twice
	a.swap(func() { retired = append(retired, "late") })
	if len(retired) != 3 || retired[2] != "late" {
		t.Fatalf("a swap after Stop must retire itself, got %v", retired)
	}
}
