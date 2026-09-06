package db

import (
	"path/filepath"
	"testing"

	"switchboard/backend/internal/protocol"
)

func TestDeckConfigPersistence(t *testing.T) {
	dir := t.TempDir()
	db, err := Open(filepath.Join(dir, "test.db"))
	if err != nil {
		t.Fatalf("Open failed: %v", err)
	}
	defer db.Close()

	// Initial fetch should return default configuration
	cfg, err := db.DeckConfig()
	if err != nil {
		t.Fatalf("DeckConfig() error = %v", err)
	}
	if len(cfg.Pages) == 0 {
		t.Fatalf("Expected default deck pages, got 0")
	}

	// Update configuration
	cfg.Pages[0].Keys[0].Title = "Custom Key"
	cfg.Pages[0].Keys[0].Action = protocol.DeckAction{Type: "url", Value: "https://switchboard.local"}
	if err := db.SaveDeckConfig(cfg); err != nil {
		t.Fatalf("SaveDeckConfig() error = %v", err)
	}

	// Re-fetch and verify
	loaded, err := db.DeckConfig()
	if err != nil {
		t.Fatalf("DeckConfig() re-fetch error = %v", err)
	}
	if loaded.Pages[0].Keys[0].Title != "Custom Key" {
		t.Errorf("Expected Title %q, got %q", "Custom Key", loaded.Pages[0].Keys[0].Title)
	}
	if loaded.Pages[0].Keys[0].Action.Value != "https://switchboard.local" {
		t.Errorf("Expected Action.Value %q, got %q", "https://switchboard.local", loaded.Pages[0].Keys[0].Action.Value)
	}
}
