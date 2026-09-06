//go:build windows

package system

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"switchboard/backend/internal/protocol"
)

// ListInstalledApps discovers applications from Start Menu shortcut folders and common system utilities.
func (c *Controller) ListInstalledApps() []protocol.InstalledApp {
	return ListWindowsApps()
}

// ListWindowsApps scans common Windows directories for installed application shortcuts and built-ins.
func ListWindowsApps() []protocol.InstalledApp {
	seen := make(map[string]bool)
	var apps []protocol.InstalledApp

	// Built-in standard Windows utilities
	builtins := []struct {
		name string
		cmd  string
		icon string
	}{
		{"Notepad", "notepad", "notes"},
		{"Calculator", "calc", "code"},
		{"Terminal", "wt", "terminal"},
		{"Command Prompt", "cmd", "terminal"},
		{"PowerShell", "powershell", "terminal"},
		{"File Explorer", "explorer", "folder"},
		{"Task Manager", "taskmgr", "monitor"},
		{"Snipping Tool", "snippingtool", "camera"},
		{"Paint", "mspaint", "code"},
		{"Settings", "ms-settings:", "settings"},
	}

	for _, b := range builtins {
		seen[strings.ToLower(b.name)] = true
		apps = append(apps, protocol.InstalledApp{
			Name: b.name,
			Path: b.cmd,
			Icon: b.icon,
		})
	}

	// Directories to scan for Start Menu shortcuts. os.ExpandEnv understands
	// $VARIABLE syntax, not Windows' %VARIABLE% form. The old paths therefore
	// never resolved on Windows and the picker could only show the built-ins.
	// Start Menu entries are the launchable, user-facing app catalogue Windows
	// itself presents, including per-user installs such as VS Code and Chrome.
	dirs := []string{
		filepath.Join(os.Getenv("ProgramData"), "Microsoft", "Windows", "Start Menu", "Programs"),
		filepath.Join(os.Getenv("AppData"), "Microsoft", "Windows", "Start Menu", "Programs"),
	}

	for _, dir := range dirs {
		if dir == "" {
			continue
		}
		if _, err := os.Stat(dir); err != nil {
			continue
		}
		_ = filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
			if err != nil || d == nil || d.IsDir() {
				return nil
			}
			if !strings.EqualFold(filepath.Ext(d.Name()), ".lnk") {
				return nil
			}

			// Clean name: "Google Chrome.lnk" -> "Google Chrome"
			baseName := strings.TrimSuffix(d.Name(), filepath.Ext(d.Name()))
			lowerName := strings.ToLower(baseName)

			// Filter out uninstallers, helpers, documentation
			if strings.Contains(lowerName, "uninstall") ||
				strings.Contains(lowerName, "help") ||
				strings.Contains(lowerName, "readme") ||
				strings.Contains(lowerName, "website") ||
				strings.Contains(lowerName, "documentation") ||
				strings.Contains(lowerName, "release notes") {
				return nil
			}

			if seen[lowerName] {
				return nil
			}
			seen[lowerName] = true

			icon := guessIcon(lowerName)
			apps = append(apps, protocol.InstalledApp{
				Name: baseName,
				Path: path,
				Icon: icon,
			})
			return nil
		})
	}

	sort.Slice(apps, func(i, j int) bool {
		return strings.ToLower(apps[i].Name) < strings.ToLower(apps[j].Name)
	})

	return apps
}

func guessIcon(name string) string {
	switch {
	case strings.Contains(name, "chrome"), strings.Contains(name, "edge"), strings.Contains(name, "firefox"), strings.Contains(name, "brave"), strings.Contains(name, "browser"):
		return "google"
	case strings.Contains(name, "code"), strings.Contains(name, "studio"), strings.Contains(name, "sublime"), strings.Contains(name, "intellij"), strings.Contains(name, "pycharm"), strings.Contains(name, "editor"):
		return "code"
	case strings.Contains(name, "spotify"), strings.Contains(name, "music"), strings.Contains(name, "itunes"), strings.Contains(name, "audio"):
		return "spotify"
	case strings.Contains(name, "youtube"):
		return "youtube"
	case strings.Contains(name, "note"), strings.Contains(name, "obsidian"), strings.Contains(name, "notion"), strings.Contains(name, "word"), strings.Contains(name, "writer"):
		return "notes"
	case strings.Contains(name, "folder"), strings.Contains(name, "explorer"), strings.Contains(name, "files"):
		return "folder"
	case strings.Contains(name, "term"), strings.Contains(name, "prompt"), strings.Contains(name, "shell"), strings.Contains(name, "bash"), strings.Contains(name, "git"):
		return "terminal"
	case strings.Contains(name, "settings"), strings.Contains(name, "config"), strings.Contains(name, "control"):
		return "settings"
	case strings.Contains(name, "camera"), strings.Contains(name, "photo"), strings.Contains(name, "snip"):
		return "camera"
	case strings.Contains(name, "calendar"):
		return "calendar"
	default:
		return "code"
	}
}
