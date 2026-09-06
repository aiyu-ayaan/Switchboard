package system

import (
	"errors"
	"testing"

	"switchboard/backend/internal/protocol"
)

// TestEnumerateDisplays exercises the real OS backend. It asserts the
// invariants the mobile and desktop sliders depend on rather than any
// particular hardware, and skips where no controllable panel exists (CI
// runners, headless hosts, macOS/Linux until Phase 3).
func TestEnumerateDisplays(t *testing.T) {
	c := NewController()
	defer c.Close()

	displays, err := c.Displays()
	if errors.Is(err, ErrUnsupported) {
		t.Skip("display control not implemented on this platform yet")
	}
	if err != nil {
		t.Skipf("no display bus available here: %v", err)
	}
	if len(displays) == 0 {
		t.Skip("no DDC/CI or internal panels detected")
	}

	for _, d := range displays {
		t.Logf("%s (%s) brightness=%d range=%d..%d contrast=%v %d..%d internal=%v",
			d.Name, d.ID, d.Brightness, d.MinBright, d.MaxBright,
			d.HasContrast, d.MinContrast, d.MaxContrast, d.Internal)

		if d.ID == "" || d.Name == "" {
			t.Errorf("display has empty ID or name: %+v", d)
		}
		if d.MaxBright <= d.MinBright {
			t.Errorf("%s: brightness range is empty: %d..%d", d.Name, d.MinBright, d.MaxBright)
		}
		if d.Brightness < d.MinBright || d.Brightness > d.MaxBright {
			t.Errorf("%s: brightness %d outside reported range %d..%d",
				d.Name, d.Brightness, d.MinBright, d.MaxBright)
		}
		if d.HasContrast && d.MaxContrast <= d.MinContrast {
			t.Errorf("%s: contrast range is empty: %d..%d", d.Name, d.MinContrast, d.MaxContrast)
		}
		if d.Internal && d.HasContrast {
			t.Errorf("%s: internal panels have no contrast control", d.Name)
		}
	}
}

// TestSetBrightnessClampsToPanelRange proves a slider cannot drive a panel
// outside its own capability, which is the whole reason the range is reported.
func TestSetBrightnessClampsToPanelRange(t *testing.T) {
	c := NewController()
	defer c.Close()

	displays, err := c.Displays()
	if err != nil || len(displays) == 0 {
		t.Skip("no controllable display available")
	}
	target := displays[0]
	original := target.Brightness
	t.Cleanup(func() { c.SetBrightness(target.ID, original) })

	got, err := c.SetBrightness(target.ID, target.MaxBright+500)
	if err != nil {
		t.Fatalf("set brightness: %v", err)
	}
	if got.Brightness != target.MaxBright {
		t.Errorf("over-range write clamped to %d, want %d", got.Brightness, target.MaxBright)
	}

	if got, err = c.SetBrightness(target.ID, target.MinBright-500); err != nil {
		t.Fatalf("set brightness: %v", err)
	}
	if got.Brightness != target.MinBright {
		t.Errorf("under-range write clamped to %d, want %d", got.Brightness, target.MinBright)
	}
}

// internalPanel returns the built-in display from a fresh enumeration. It is
// looked up by the Internal flag rather than by ID because the ID is not
// stable: enumeratePanels pairs a monitor that failed DDC/CI with the next
// unclaimed WMI panel by position, so a transient DDC/CI failure on an
// external monitor hands the built-in panel that monitor's ID.
func internalPanel(t *testing.T, c *Controller) (protocol.Display, bool) {
	t.Helper()
	displays, err := c.Displays()
	if err != nil {
		return protocol.Display{}, false
	}
	for _, d := range displays {
		if d.Internal {
			return d, true
		}
	}
	return protocol.Display{}, false
}

// TestInternalPanelBrightnessRoundTrips exercises the WMI path specifically:
// the DDC/CI branch above cannot reach it, and the read and the write are
// separate COM calls, so a write that lands nowhere and a read that reports a
// stale value look identical from the caller. Writing a value and re-reading
// it after a fresh enumeration is what tells them apart. Skips on any host
// with no internal panel, which is every CI runner and most desktops.
func TestInternalPanelBrightnessRoundTrips(t *testing.T) {
	c := NewController()
	defer c.Close()

	start, ok := internalPanel(t, c)
	if !ok {
		t.Skip("no internal panel on this host")
	}
	original := start.Brightness
	t.Cleanup(func() {
		if d, ok := internalPanel(t, c); ok {
			c.SetBrightness(d.ID, original)
		}
	})

	// Two distinct values, so a backend that silently does nothing cannot pass
	// by happening to already sit at the one we asked for.
	for _, want := range []int{40, 70} {
		before, ok := internalPanel(t, c)
		if !ok {
			t.Fatal("internal panel disappeared between enumerations")
		}
		if _, err := c.SetBrightness(before.ID, want); err != nil {
			t.Fatalf("set internal brightness to %d: %v", want, err)
		}
		if err := c.RefreshDisplays(); err != nil {
			t.Fatalf("refresh: %v", err)
		}
		after, ok := internalPanel(t, c)
		if !ok {
			t.Fatal("internal panel disappeared after refresh")
		}
		if after.Brightness != want {
			t.Errorf("wrote %d, WMI read back %d", want, after.Brightness)
		}
	}
}
