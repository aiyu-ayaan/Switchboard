package server

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"

	"switchboard/backend/internal/db"
)

// Setting keys. These live in the database rather than the config file
// because the desktop UI edits them at runtime; flags and environment stay
// the province of things fixed at launch, like the port and the DB path.
const (
	settingDownloadDir     = "downloadDir"
	settingRateUnit        = "rateUnit"
	settingRunInBackground = "runInBackground"
)

// Rate units the UIs may render transfer speeds in. The daemon reports raw
// bytes per second and stores the preference; it never formats.
const (
	RateUnitMBps = "MBps"
	RateUnitMbps = "Mbps"
)

// Settings are the daemon preferences the desktop UI owns.
type Settings struct {
	DownloadDir     string `json:"downloadDir"`
	RateUnit        string `json:"rateUnit"`
	RunInBackground bool   `json:"runInBackground"`
}

// defaultSettings puts incoming files in a Switchboard folder of their own
// rather than loose in Downloads, so a user can find (or delete) everything a
// phone sent without sifting through browser downloads.
func defaultSettings() Settings {
	dir := "Switchboard"
	if home, err := os.UserHomeDir(); err == nil {
		dir = filepath.Join(home, "Downloads", "Switchboard")
	}
	return Settings{DownloadDir: dir, RateUnit: RateUnitMBps, RunInBackground: true}
}

// loadSettings layers whatever is stored over the defaults, so a key added in
// a later version appears with its default on an existing database.
func loadSettings(store *db.Database) (Settings, error) {
	settings := defaultSettings()
	stored, err := store.Settings()
	if err != nil {
		return settings, err
	}
	if v := stored[settingDownloadDir]; v != "" {
		settings.DownloadDir = v
	}
	if v := stored[settingRateUnit]; v == RateUnitMBps || v == RateUnitMbps {
		settings.RateUnit = v
	}
	if v, err := strconv.ParseBool(stored[settingRunInBackground]); err == nil {
		settings.RunInBackground = v
	}
	return settings, nil
}

// Settings returns the current daemon preferences.
func (s *Server) Settings() Settings {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.settings
}

// UpdateSettings validates and persists a settings change. The download
// directory is created and probed here rather than at the next offer: a user
// who picks an unwritable folder should be told while the dialog is still
// open, not when a transfer fails hours later.
func (s *Server) UpdateSettings(next Settings) (Settings, error) {
	if next.DownloadDir == "" {
		return Settings{}, fmt.Errorf("settings: download directory is required")
	}
	if err := ValidateDownloadDir(next.DownloadDir); err != nil {
		return Settings{}, err
	}
	if next.RateUnit != RateUnitMBps && next.RateUnit != RateUnitMbps {
		return Settings{}, fmt.Errorf("settings: unknown rate unit %q", next.RateUnit)
	}

	for key, value := range map[string]string{
		settingDownloadDir:     next.DownloadDir,
		settingRateUnit:        next.RateUnit,
		settingRunInBackground: strconv.FormatBool(next.RunInBackground),
	} {
		if err := s.store.SetSetting(key, value); err != nil {
			return Settings{}, err
		}
	}

	s.mu.Lock()
	s.settings = next
	s.mu.Unlock()
	return next, nil
}

// ValidateDownloadDir reports whether files can actually be written to dir.
// Existence is not enough: a read-only or full volume passes a stat and then
// fails on the first byte.
func ValidateDownloadDir(dir string) error {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	probe, err := os.CreateTemp(dir, ".switchboard-*")
	if err != nil {
		return err
	}
	probe.Close()
	return os.Remove(probe.Name())
}

// downloadDir is the accessor handed to the transfer manager, so a setting
// changed while the daemon runs applies to the next file offered.
func (s *Server) downloadDir() string {
	return s.Settings().DownloadDir
}
