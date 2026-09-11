//go:build windows

package system

import (
	"errors"
	"testing"
)

func TestMicSupported(t *testing.T) {
	if !micSupported() {
		t.Fatal("micSupported() = false on windows, want true")
	}
	if !inputsSupported() {
		t.Fatal("inputsSupported() = false on windows, want true")
	}
}

func TestAudioInputs(t *testing.T) {
	c := NewController()
	defer c.Close()

	inputs, err := c.Inputs()
	if errors.Is(err, ErrUnsupported) {
		t.Skip("audio inputs not supported")
	}
	if err != nil {
		t.Skipf("no capture endpoints available: %v", err)
	}
	if len(inputs) == 0 {
		t.Skip("host has no active capture endpoints")
	}

	seen := map[string]bool{}
	defaults := 0
	for _, in := range inputs {
		t.Logf("%s default=%v id=%s", in.Name, in.Default, in.ID)
		if in.ID == "" {
			t.Errorf("endpoint has no id: %+v", in)
		}
		if in.Name == "" {
			t.Errorf("endpoint %s has no label", in.ID)
		}
		if seen[in.ID] {
			t.Errorf("duplicate endpoint id %s", in.ID)
		}
		seen[in.ID] = true
		if in.Default {
			defaults++
		}
	}
	if defaults > 1 {
		t.Errorf("%d endpoints marked default, want at most 1", defaults)
	}
}

func TestMicVolumeRoundTrip(t *testing.T) {
	c := NewController()
	defer c.Close()

	vol, err := c.Mic()
	if err != nil {
		t.Skipf("microphone not available: %v", err)
	}
	t.Logf("current mic level=%d muted=%v", vol.Level, vol.Muted)

	after, err := c.SetMic(vol.Level, vol.Muted)
	if err != nil {
		t.Fatalf("set mic volume failed: %v", err)
	}
	if after.Level != vol.Level || after.Muted != vol.Muted {
		t.Errorf("mic volume after set = %+v, want %+v", after, vol)
	}
}

func TestSetAudioInputRejectsEmptyID(t *testing.T) {
	if _, err := setAudioInput(""); err == nil {
		t.Fatal("expected error for empty input device id")
	}
}
