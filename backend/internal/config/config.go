// Package config resolves daemon settings from flags, environment and the
// per-user data directory.
package config

import (
	"flag"
	"os"
	"path/filepath"
	"strconv"
)

// DefaultPort is the daemon's LAN listener.
const DefaultPort = 9427

// Config holds resolved daemon settings.
type Config struct {
	Port   int
	DBPath string
	// Profile names a parallel installation. Empty is the shipped product;
	// anything else ("dev") moves the port, the database and the advertised
	// host name aside so a checkout can run beside an installed copy. Without
	// it the two fight over the port and the SQLite identity store, and only
	// one can run at a time.
	Profile string
}

// DefaultConfig returns settings with the database in the per-user data
// directory, so the daemon does not write next to the binary.
func DefaultConfig() *Config {
	return &Config{Port: DefaultPort, DBPath: defaultDBPath("")}
}

// Load layers flags over environment variables over the defaults.
func Load(args []string) (*Config, error) {
	cfg := DefaultConfig()

	// Read first: it moves the defaults the two settings below start from, so
	// an explicit port or database still wins over the profile's choice.
	if v := os.Getenv("SWITCHBOARD_PROFILE"); v != "" {
		cfg.applyProfile(v)
	}
	if v := os.Getenv("SWITCHBOARD_PORT"); v != "" {
		if port, err := strconv.Atoi(v); err == nil {
			cfg.Port = port
		}
	}
	if v := os.Getenv("SWITCHBOARD_DB"); v != "" {
		cfg.DBPath = v
	}

	fs := flag.NewFlagSet("switchboard", flag.ContinueOnError)
	profile := fs.String("profile", cfg.Profile, `parallel installation name ("dev"); moves port, database and host name aside`)
	port := fs.Int("port", 0, "TCP port for the LAN WebSocket listener")
	dbPath := fs.String("db", "", "path to the SQLite database")
	if err := fs.Parse(args); err != nil {
		return nil, err
	}
	if *profile != cfg.Profile {
		cfg.applyProfile(*profile)
	}
	// Applied after the profile so an explicit value is never overwritten by
	// the defaults the profile moves.
	if *port != 0 {
		cfg.Port = *port
	}
	if *dbPath != "" {
		cfg.DBPath = *dbPath
	}
	return cfg, nil
}

// ProfilePort is the listener a named profile takes. One offset, because
// there is one alternate profile; a second would pick the next free number
// here rather than anywhere else.
func ProfilePort(profile string) int {
	if profile == "" {
		return DefaultPort
	}
	return DefaultPort + 1
}

func (c *Config) applyProfile(profile string) {
	c.Profile = profile
	c.Port = ProfilePort(profile)
	c.DBPath = defaultDBPath(profile)
}

// EnsureDBDir creates the directory holding the database.
func (c *Config) EnsureDBDir() error {
	return os.MkdirAll(filepath.Dir(c.DBPath), 0o700)
}

func defaultDBPath(profile string) string {
	name := "switchboard.db"
	if profile != "" {
		name = "switchboard-" + profile + ".db"
	}
	dir, err := os.UserConfigDir()
	if err != nil {
		return name
	}
	return filepath.Join(dir, "Switchboard", name)
}
