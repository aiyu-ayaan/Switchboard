//go:build linux

package system

import (
	"errors"
	"testing"
)

// These exercise the real PulseAudio/PipeWire path rather than a fake, for the
// same reason the Windows audio tests do: what breaks an audio backend is the
// server's actual shape -- a machine with no sink, a default that has gone
// away, a monitor source masquerading as a microphone -- and none of that
// survives being mocked. They skip rather than fail where no server is
// reachable, since CI runners have no audio.

func skipWithoutPulse(t *testing.T) {
	t.Helper()
	if _, err := pulseState(); err != nil {
		if errors.Is(err, errNoPulse) {
			t.Skip("pactl not installed")
		}
		t.Skipf("no audio server reachable: %v", err)
	}
}

func TestLinuxVolumeReadsMasterLevel(t *testing.T) {
	skipWithoutPulse(t)

	vol, err := getVolume()
	if err != nil {
		if errors.Is(err, ErrUnsupported) {
			t.Skip("host has no audio sink")
		}
		t.Fatalf("getVolume: %v", err)
	}
	if vol.Level < 0 || vol.Level > 100 {
		t.Errorf("master level %d outside 0-100", vol.Level)
	}
	t.Logf("master volume: %d%% muted=%v", vol.Level, vol.Muted)
}

// TestLinuxOutputsHaveStableIdentity guards what a picker on the phone needs:
// an id it can send back, a label a human can choose between, and exactly one
// device marked current. A list where every row claims to be the default, or
// none does, renders as a picker with no selection.
func TestLinuxOutputsHaveStableIdentity(t *testing.T) {
	skipWithoutPulse(t)

	outputs, err := audioOutputs()
	if err != nil {
		t.Fatalf("audioOutputs: %v", err)
	}
	if len(outputs) == 0 {
		t.Skip("host has no audio sink")
	}

	defaults := 0
	seen := map[string]bool{}
	for _, out := range outputs {
		if out.ID == "" {
			t.Errorf("output %q has no id", out.Name)
		}
		if out.Name == "" {
			t.Errorf("output %q has no label", out.ID)
		}
		if seen[out.ID] {
			t.Errorf("duplicate output id %q", out.ID)
		}
		seen[out.ID] = true
		if out.Default {
			defaults++
		}
	}
	if defaults != 1 {
		t.Errorf("got %d default outputs, want exactly 1", defaults)
	}
}

// TestLinuxInputsExcludeMonitors is the one invariant worth asserting about
// the capture list: PulseAudio reports a monitor source for every sink, and
// they are sources in every respect except being microphones.
func TestLinuxInputsExcludeMonitors(t *testing.T) {
	skipWithoutPulse(t)

	snap, err := pulseState()
	if err != nil {
		t.Fatalf("pulseState: %v", err)
	}
	monitors := map[string]bool{}
	for _, src := range snap.Sources {
		if src.MonitorOf != "" {
			monitors[src.Name] = true
		}
	}
	if len(monitors) == 0 {
		t.Skip("host reports no monitor sources")
	}

	inputs, err := audioInputs()
	if err != nil {
		t.Fatalf("audioInputs: %v", err)
	}
	for _, in := range inputs {
		if monitors[in.ID] {
			t.Errorf("capture list includes monitor source %q", in.ID)
		}
	}
}

// TestLinuxMixerSessionsAreAddressable checks that every row carries an id
// setSessionVolume will accept. The ids are sink-input indices, and a row the
// caller cannot write back to is a slider that silently does nothing.
func TestLinuxMixerSessionsAreAddressable(t *testing.T) {
	skipWithoutPulse(t)

	sessions, err := mixerSessions()
	if err != nil {
		t.Fatalf("mixerSessions: %v", err)
	}
	if len(sessions) == 0 {
		t.Skip("nothing is playing on this host")
	}
	for _, s := range sessions {
		if s.ID == "" {
			t.Errorf("session %q has no id", s.Name)
		}
		if s.Name == "" {
			t.Errorf("session %q has no name", s.ID)
		}
		if s.Level < 0 || s.Level > 100 {
			t.Errorf("session %q level %d outside 0-100", s.Name, s.Level)
		}
		t.Logf("session %s: %s pid=%d %d%% muted=%v active=%v", s.ID, s.Name, s.PID, s.Level, s.Muted, s.Active)
	}
}

// TestPulseVolumePercent covers the raw-to-percent conversion against the
// values PulseAudio actually emits, including the boost above unity that the
// server permits and the slider cannot show.
func TestPulseVolumePercent(t *testing.T) {
	cases := []struct {
		name string
		vol  pulseVolume
		want int
	}{
		{"silent", pulseVolume{"front-left": {0}, "front-right": {0}}, 0},
		{"unity", pulseVolume{"front-left": {65536}, "front-right": {65536}}, 100},
		{"half", pulseVolume{"front-left": {32768}, "front-right": {32768}}, 50},
		{"rounds to nearest", pulseVolume{"mono": {44560}}, 68},
		{"panned hard takes the loud channel", pulseVolume{"front-left": {65536}, "front-right": {0}}, 100},
		{"boost above unity clamps", pulseVolume{"mono": {98304}}, 100},
		{"no channels", pulseVolume{}, 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := tc.vol.percent(); got != tc.want {
				t.Errorf("percent() = %d, want %d", got, tc.want)
			}
		})
	}
}

// TestLinuxAudioCapabilities pins the wiring: on a host with a reachable
// server, State must advertise the audio capabilities, because the phone hides
// the whole audio screen when they are missing.
func TestLinuxAudioCapabilities(t *testing.T) {
	skipWithoutPulse(t)

	c := NewController()
	defer c.Close()

	state := c.State("test-daemon")
	have := map[string]bool{}
	for _, cap := range state.Capabilities {
		have[cap] = true
	}
	for _, want := range []string{"volume", "mixer", "outputs", "mic", "inputs"} {
		if !have[want] {
			t.Errorf("capability %q missing from %v", want, state.Capabilities)
		}
	}
}
