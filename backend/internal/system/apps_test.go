package system

import (
	"testing"
)

func TestListInstalledApps(t *testing.T) {
	c := &Controller{}
	apps := c.ListInstalledApps()
	if len(apps) == 0 {
		t.Fatal("expected installed apps to not be empty")
	}

	foundNotepad := false
	for _, app := range apps {
		if app.Name == "Notepad" || app.Name == "Terminal" {
			foundNotepad = true
			break
		}
	}
	if !foundNotepad {
		t.Errorf("expected common app like Notepad or Terminal to be found, got %d apps", len(apps))
	}
}
