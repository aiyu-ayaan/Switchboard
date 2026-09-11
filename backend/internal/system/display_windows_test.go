package system

import (
	"errors"
	"testing"

	"golang.org/x/sys/windows"

	"switchboard/backend/internal/protocol"
)

// loadedController builds a controller holding one synthetic panel, so the
// retry logic can be exercised on a machine with no DDC/CI monitor at all.
func loadedController(id string) *displayController {
	c := &displayController{
		loaded: true,
		panels: []*panel{{info: protocol.Display{ID: id, Name: "Fake", MaxBright: 100}}},
	}
	c.reloadFn = c.reload
	return c
}

// TestWithPanelRetriesAfterReload is the regression guard for brightness dying
// after a fullscreen game. The mode change invalidates the cached handle, so
// the first write fails; nothing re-enumerated the panels, so every later write
// failed the same way until the daemon restarted.
func TestWithPanelRetriesAfterReload(t *testing.T) {
	c := loadedController("\\\\.\\DISPLAY1")

	reloads := 0
	c.reloadFn = func() error {
		reloads++
		// A real reload drops the stale handles and opens fresh ones.
		c.panels = []*panel{{info: protocol.Display{ID: "\\\\.\\DISPLAY1", Name: "Fake", MaxBright: 100}}}
		return nil
	}

	attempts := 0
	got, err := c.withPanel("\\\\.\\DISPLAY1", func(p *panel) error {
		attempts++
		if attempts == 1 {
			return errors.New("the handle is stale")
		}
		p.info.Brightness = 42
		return nil
	})
	if err != nil {
		t.Fatalf("withPanel gave up instead of re-enumerating: %v", err)
	}
	if reloads != 1 {
		t.Errorf("re-enumerated %d times, want exactly 1", reloads)
	}
	if attempts != 2 {
		t.Errorf("op ran %d times, want 2 (fail, then retry)", attempts)
	}
	if got.Brightness != 42 {
		t.Errorf("returned brightness %d, want the value the retry wrote (42)", got.Brightness)
	}
}

// TestWithPanelSkipsReloadOnSuccess keeps the healthy path cheap: enumeration
// opens DDC/CI handles for every monitor and must not run on every slider drag.
func TestWithPanelSkipsReloadOnSuccess(t *testing.T) {
	c := loadedController("\\\\.\\DISPLAY1")
	c.reloadFn = func() error {
		t.Error("re-enumerated after a write that succeeded")
		return nil
	}

	if _, err := c.withPanel("\\\\.\\DISPLAY1", func(*panel) error { return nil }); err != nil {
		t.Fatalf("withPanel: %v", err)
	}
}

// TestWithPanelReportsPersistentFailure proves a genuinely broken panel still
// surfaces an error rather than being masked by the retry.
func TestWithPanelReportsPersistentFailure(t *testing.T) {
	c := loadedController("\\\\.\\DISPLAY1")
	c.reloadFn = func() error { return nil }

	_, err := c.withPanel("\\\\.\\DISPLAY1", func(*panel) error {
		return errors.New("monitor is off")
	})
	if err == nil {
		t.Fatal("a write that failed twice reported success")
	}
}

func TestSetDisplayPowerExternal(t *testing.T) {
	c := loadedController("\\\\.\\DISPLAY1")
	c.panels[0].info.Power = true

	var lastCode, lastVal uint32
	c.setVCPFeatureFn = func(handle windows.Handle, code, value uint32) error {
		lastCode = code
		lastVal = value
		return nil
	}

	// Turn power off
	disp, err := c.SetPower("\\\\.\\DISPLAY1", false)
	if err != nil {
		t.Fatalf("SetPower(false): %v", err)
	}
	if disp.Power != false {
		t.Errorf("expected Power=false, got %v", disp.Power)
	}
	if lastCode != 0xD6 || lastVal != 4 {
		t.Errorf("expected VCP code 0xD6 and val 4, got code 0x%X val %d", lastCode, lastVal)
	}

	// Turn power on
	disp, err = c.SetPower("\\\\.\\DISPLAY1", true)
	if err != nil {
		t.Fatalf("SetPower(true): %v", err)
	}
	if disp.Power != true {
		t.Errorf("expected Power=true, got %v", disp.Power)
	}
	if lastCode != 0xD6 || lastVal != 1 {
		t.Errorf("expected VCP code 0xD6 and val 1, got code 0x%X val %d", lastCode, lastVal)
	}
}

func TestSetDisplayPowerInternal(t *testing.T) {
	c := &displayController{
		loaded: true,
		panels: []*panel{{
			wmiInstance: "WMI\\FakePanel",
			info: protocol.Display{
				ID:         "INTERNAL_1",
				Name:       "Built-in display",
				Internal:   true,
				Brightness: 70,
				MinBright:  0,
				MaxBright:  100,
				Power:      true,
			},
		}},
	}
	c.reloadFn = func() error { return nil }

	var lastInstance string
	var lastBright int
	c.setInternalBrightnessFn = func(instance string, value int) error {
		lastInstance = instance
		lastBright = value
		return nil
	}

	// Turn off: saves brightness (70) and sets to minBrightness (0)
	disp, err := c.SetPower("INTERNAL_1", false)
	if err != nil {
		t.Fatalf("SetPower(false): %v", err)
	}
	if disp.Power != false {
		t.Errorf("expected Power=false, got %v", disp.Power)
	}
	if disp.Brightness != 0 {
		t.Errorf("expected Brightness=0, got %d", disp.Brightness)
	}
	if lastInstance != "WMI\\FakePanel" || lastBright != 0 {
		t.Errorf("setInternalBrightness called with (%s, %d), want (WMI\\FakePanel, 0)", lastInstance, lastBright)
	}

	// Turn back on: restores saved brightness (70)
	disp, err = c.SetPower("INTERNAL_1", true)
	if err != nil {
		t.Fatalf("SetPower(true): %v", err)
	}
	if disp.Power != true {
		t.Errorf("expected Power=true, got %v", disp.Power)
	}
	if disp.Brightness != 70 {
		t.Errorf("expected restored Brightness=70, got %d", disp.Brightness)
	}
	if lastBright != 70 {
		t.Errorf("setInternalBrightness called with %d, want 70", lastBright)
	}

	// If saved brightness was <= 0, turning back on defaults to 50
	c.panels[0].savedBrightness = 0
	c.panels[0].info.Brightness = 0
	disp, err = c.SetPower("INTERNAL_1", true)
	if err != nil {
		t.Fatalf("SetPower(true): %v", err)
	}
	if disp.Brightness != 50 {
		t.Errorf("expected fallback Brightness=50, got %d", disp.Brightness)
	}
	if lastBright != 50 {
		t.Errorf("setInternalBrightness called with %d, want 50", lastBright)
	}
}
