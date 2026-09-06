//go:build !windows

package system

import "switchboard/backend/internal/protocol"

// ListInstalledApps provides standard desktop fallbacks on non-Windows platforms.
func (c *Controller) ListInstalledApps() []protocol.InstalledApp {
	return []protocol.InstalledApp{
		{Name: "Terminal", Path: "terminal", Icon: "terminal"},
		{Name: "Web Browser", Path: "browser", Icon: "google"},
		{Name: "Text Editor", Path: "editor", Icon: "notes"},
		{Name: "File Manager", Path: "files", Icon: "folder"},
	}
}
