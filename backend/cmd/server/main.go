// Command server runs the Switchboard host daemon.
package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"switchboard/backend/internal/config"
	"switchboard/backend/internal/db"
	"switchboard/backend/internal/discovery"
	"switchboard/backend/internal/server"
	"switchboard/backend/internal/system"
)

func main() {
	log.SetFlags(log.Ltime)
	log.SetPrefix("switchboard: ")

	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	cfg, err := config.Load(os.Args[1:])
	if err != nil {
		return err
	}
	if err := cfg.EnsureDBDir(); err != nil {
		return err
	}

	store, err := db.Open(cfg.DBPath)
	if err != nil {
		return err
	}
	defer store.Close()

	control := system.NewController()
	defer control.Close()

	// A profiled daemon says so everywhere it is named, because the phone
	// lists it next to the installed copy.
	if cfg.Profile != "" {
		control.SetHostName(control.HostName() + " (" + cfg.Profile + ")")
	}

	srv, err := server.New(cfg, store, control)
	if err != nil {
		return err
	}

	// mDNS lets a phone find this host without being told an address. It is
	// advertised best-effort and off the startup path: registering walks every
	// network interface, which at login (before the network is up) or beside
	// virtual bridges can take long enough to keep the local API from binding,
	// and the app waits on that port. A network that will not carry multicast
	// costs discovery, not the daemon.
	advertiserCh := make(chan *discovery.Advertiser, 1)
	go func() {
		advertiser, err := discovery.Advertise(srv.DaemonID(), control.HostName(), cfg.Port)
		if err != nil {
			log.Printf("mDNS advertisement unavailable, pair by address instead: %v", err)
			advertiserCh <- nil
			return
		}
		advertiserCh <- advertiser
	}()
	defer func() {
		if advertiser := <-advertiserCh; advertiser != nil {
			advertiser.Stop()
		}
	}()

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Media playback changes at the host, not at our request, so it needs a
	// watcher to reach connected phones.
	go srv.WatchMedia(ctx)

	return srv.ListenAndServe(ctx)
}
