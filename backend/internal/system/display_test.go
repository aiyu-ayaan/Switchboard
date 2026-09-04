package system

import (
	"errors"
	"testing"
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
