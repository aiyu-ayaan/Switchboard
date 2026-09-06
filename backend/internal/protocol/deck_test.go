package protocol

import (
	"encoding/json"
	"testing"
)

func TestDefaultDeckConfig(t *testing.T) {
	cfg := DefaultDeckConfig()
	if len(cfg.Pages) < 2 {
		t.Fatalf("DefaultDeckConfig() returned %d pages, expected at least 2", len(cfg.Pages))
	}
	if len(cfg.Pages[0].Keys) != 8 {
		t.Fatalf("DefaultDeckConfig() page 1 has %d keys, expected exactly 8 keys for Stream Deck Neo", len(cfg.Pages[0].Keys))
	}
	if cfg.Infobar.Mode != "clock" {
		t.Errorf("DefaultDeckConfig() Infobar mode = %q, want 'clock'", cfg.Infobar.Mode)
	}

	// Verify serialization and deserialization
	data, err := json.Marshal(cfg)
	if err != nil {
		t.Fatalf("json.Marshal(cfg) error = %v", err)
	}

	var decoded DeckConfig
	if err := json.Unmarshal(data, &decoded); err != nil {
		t.Fatalf("json.Unmarshal(data) error = %v", err)
	}

	if decoded.ActivePage != cfg.ActivePage || len(decoded.Pages) != len(cfg.Pages) {
		t.Errorf("Decoded config does not match original: %+v vs %+v", decoded, cfg)
	}
}
