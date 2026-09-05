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

	srv, err := server.New(cfg, store, control)
	if err != nil {
		return err
	}

	// mDNS lets a phone find this host without being told an address. It is
	// advertised best-effort: a network that will not carry multicast costs
	// discovery, not the daemon.
	if advertiser, err := discovery.Advertise(srv.DaemonID(), control.HostName(), cfg.Port); err != nil {
		log.Printf("mDNS advertisement unavailable, pair by address instead: %v", err)
	} else {
		defer advertiser.Stop()
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Media playback changes at the host, not at our request, so it needs a
	// watcher to reach connected phones.
	go srv.WatchMedia(ctx)

	return srv.ListenAndServe(ctx)
}
