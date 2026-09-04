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
}

// DefaultConfig returns settings with the database in the per-user data
// directory, so the daemon does not write next to the binary.
func DefaultConfig() *Config {
	return &Config{Port: DefaultPort, DBPath: defaultDBPath()}
}

// Load layers flags over environment variables over the defaults.
func Load(args []string) (*Config, error) {
	cfg := DefaultConfig()

	if v := os.Getenv("SWITCHBOARD_PORT"); v != "" {
		if port, err := strconv.Atoi(v); err == nil {
			cfg.Port = port
		}
	}
	if v := os.Getenv("SWITCHBOARD_DB"); v != "" {
		cfg.DBPath = v
	}

	fs := flag.NewFlagSet("switchboard", flag.ContinueOnError)
	fs.IntVar(&cfg.Port, "port", cfg.Port, "TCP port for the LAN WebSocket listener")
	fs.StringVar(&cfg.DBPath, "db", cfg.DBPath, "path to the SQLite database")
	if err := fs.Parse(args); err != nil {
		return nil, err
	}
	return cfg, nil
}

// EnsureDBDir creates the directory holding the database.
func (c *Config) EnsureDBDir() error {
	return os.MkdirAll(filepath.Dir(c.DBPath), 0o700)
}

func defaultDBPath() string {
	dir, err := os.UserConfigDir()
	if err != nil {
		return "switchboard.db"
	}
	return filepath.Join(dir, "Switchboard", "switchboard.db")
}
