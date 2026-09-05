//go:build windows

package system

import (
	"errors"
	"testing"
)

// TestAudioOutputs exercises the real Core Audio endpoint walk. It asserts the
// invariants an output picker depends on — a stable id, a label the user can
// choose between, and exactly one device marked as the current one — rather
// than any particular sound card.
func TestAudioOutputs(t *testing.T) {
	c := NewController()
	defer c.Close()

	outputs, err := c.Outputs()
	if errors.Is(err, ErrUnsupported) {
		t.Skip("audio output routing not implemented on this platform yet")
	}
	if err != nil {
		t.Skipf("no audio endpoints available here: %v", err)
	}
	if len(outputs) == 0 {
		t.Skip("host has no active render endpoints")
	}

	seen := map[string]bool{}
	defaults := 0
	for _, o := range outputs {
		t.Logf("%s default=%v id=%s", o.Name, o.Default, o.ID)
		if o.ID == "" {
			t.Errorf("endpoint has no id: %+v", o)
		}
		if o.Name == "" {
			t.Errorf("endpoint %s has no label, which renders as a blank row", o.ID)
		}
		if seen[o.ID] {
			t.Errorf("duplicate endpoint id %s", o.ID)
		}
		seen[o.ID] = true
		if o.Default {
			defaults++
		}
	}
	if defaults != 1 {
		t.Errorf("%d endpoints marked default, want exactly 1", defaults)
	}
}

// TestSetAudioOutputRoundTrip re-selects the endpoint that is already default.
// Writing the current value proves the IPolicyConfig vtable slot is right —
// a wrong slot fails or corrupts rather than no-ops — while leaving the host's
// sound exactly where the developer running the test left it.
func TestSetAudioOutputRoundTrip(t *testing.T) {
	c := NewController()
	defer c.Close()

	outputs, err := c.Outputs()
	if err != nil || len(outputs) == 0 {
		t.Skipf("no audio endpoints available here: %v", err)
	}

	var current string
	for _, o := range outputs {
		if o.Default {
			current = o.ID
		}
	}
	if current == "" {
		t.Skip("host has no default render endpoint")
	}

	after, err := c.SetOutput(current)
	if err != nil {
		t.Fatalf("re-selecting the current endpoint failed: %v", err)
	}
	for _, o := range after {
		if o.ID == current && !o.Default {
			t.Error("the re-selected endpoint came back unmarked")
		}
	}
}

func TestSetAudioOutputRejectsEmptyID(t *testing.T) {
	if _, err := setAudioOutput(""); err == nil {
		t.Fatal("expected an error for an empty device id")
	}
}
