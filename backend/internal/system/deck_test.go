package system

import (
	"testing"
)

func TestParseHotkeyChord(t *testing.T) {
	tests := []struct {
		chord    string
		wantKeys []uint16
		wantErr  bool
	}{
		{
			chord:    "ctrl+c",
			wantKeys: []uint16{0x11, 0x43},
			wantErr:  false,
		},
		{
			chord:    "win+d",
			wantKeys: []uint16{0x5B, 0x44},
			wantErr:  false,
		},
		{
			chord:    "ctrl+shift+esc",
			wantKeys: []uint16{0x11, 0x10, 0x1B},
			wantErr:  false,
		},
		{
			chord:    "alt+f4",
			wantKeys: []uint16{0x12, 0x73},
			wantErr:  false,
		},
		{
			chord:    "invalid_key_combo_xyz",
			wantKeys: nil,
			wantErr:  true,
		},
	}

	for _, tt := range tests {
		got, err := ParseHotkeyChord(tt.chord)
		if (err != nil) != tt.wantErr {
			t.Errorf("ParseHotkeyChord(%q) error = %v, wantErr %v", tt.chord, err, tt.wantErr)
			continue
		}
		if !tt.wantErr {
			if len(got) != len(tt.wantKeys) {
				t.Fatalf("ParseHotkeyChord(%q) length = %d, want %d", tt.chord, len(got), len(tt.wantKeys))
			}
			for i := range got {
				if got[i] != tt.wantKeys[i] {
					t.Errorf("ParseHotkeyChord(%q)[%d] = 0x%X, want 0x%X", tt.chord, i, got[i], tt.wantKeys[i])
				}
			}
		}
	}
}
