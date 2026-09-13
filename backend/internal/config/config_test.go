package config

import (
	"path/filepath"
	"strings"
	"testing"
)

// The whole point of a profile is that two daemons can run at once, so what
// matters is that the three things they would otherwise fight over all move.
func TestProfileMovesPortAndDatabase(t *testing.T) {
	t.Setenv("SWITCHBOARD_PROFILE", "")
	t.Setenv("SWITCHBOARD_PORT", "")
	t.Setenv("SWITCHBOARD_DB", "")

	prod, err := Load(nil)
	if err != nil {
		t.Fatal(err)
	}
	dev, err := Load([]string{"--profile", "dev"})
	if err != nil {
		t.Fatal(err)
	}

	if prod.Port != DefaultPort {
		t.Errorf("prod port %d, want %d", prod.Port, DefaultPort)
	}
	if dev.Port == prod.Port {
		t.Errorf("dev port %d collides with prod", dev.Port)
	}
	if dev.DBPath == prod.DBPath {
		t.Errorf("dev database %q collides with prod", dev.DBPath)
	}
	if name := filepath.Base(dev.DBPath); !strings.Contains(name, "dev") {
		t.Errorf("dev database %q is not identifiable as the dev one", name)
	}
	if dev.Profile != "dev" {
		t.Errorf("profile %q, want dev", dev.Profile)
	}
}

// An explicit port has to survive the profile's default, or a developer who
// needs a specific port cannot ask for one.
func TestExplicitPortOverridesProfile(t *testing.T) {
	t.Setenv("SWITCHBOARD_PROFILE", "dev")
	t.Setenv("SWITCHBOARD_PORT", "")
	t.Setenv("SWITCHBOARD_DB", "")

	cfg, err := Load([]string{"--port", "9500", "--db", "custom.db"})
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Port != 9500 {
		t.Errorf("port %d, want 9500", cfg.Port)
	}
	if cfg.DBPath != "custom.db" {
		t.Errorf("db %q, want custom.db", cfg.DBPath)
	}
	if cfg.Profile != "dev" {
		t.Errorf("profile %q, want dev", cfg.Profile)
	}
}
