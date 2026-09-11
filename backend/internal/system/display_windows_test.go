package system

import (
	"errors"
	"testing"

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
