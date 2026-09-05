package server

// Remote unlock setup, driven from the desktop UI.
//
// Both setup steps need administrator rights — writing the password blob into
// ProgramData with a restricted DACL, and registering a task that runs as
// SYSTEM — and the daemon deliberately does not have them. So the UI does not
// perform setup; it launches `server.exe unlock setup` elevated and lets UAC
// and that console own the privileged part.
//
// The password is never typed into, passed through, or seen by the desktop
// app. It is read by the elevated console with echo off and sealed there.

import (
	"net/http"
	"os"
	"os/exec"
	"strings"
)

// unlockStatus is what the Settings pane renders.
type unlockStatus struct {
	// Supported is false off Windows, where the whole section is hidden.
	Supported bool `json:"supported"`
	// Enrolled means a password is stored, which is also exactly when the
	// host advertises the "unlock" capability to phones.
	Enrolled bool `json:"enrolled"`
}

func (s *Server) localUnlockStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, unlockStatus{
		Supported: unlockSetupSupported(),
		Enrolled:  s.control.UnlockSupported(),
	})
}

func (s *Server) localUnlockSetup(w http.ResponseWriter, r *http.Request) {
	s.launchElevatedUnlock(w, "setup")
}

func (s *Server) localUnlockDisable(w http.ResponseWriter, r *http.Request) {
	s.launchElevatedUnlock(w, "teardown")
}

// launchElevatedUnlock starts the privileged half and returns as soon as the
// request is handed to the shell. It deliberately does not wait: the elevated
// console prompts for a password and the user may take as long as they like,
// and holding the HTTP request open for that would time out long first.
func (s *Server) launchElevatedUnlock(w http.ResponseWriter, verb string) {
	if !unlockSetupSupported() {
		http.Error(w, "remote unlock is a Windows feature", http.StatusNotImplemented)
		return
	}
	exe, err := os.Executable()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	// Start-Process -Verb RunAs is what raises the UAC prompt; the daemon
	// cannot elevate itself in place.
	script := "Start-Process -FilePath '" + psQuote(exe) +
		"' -ArgumentList 'unlock','" + verb + "' -Verb RunAs"
	cmd := exec.Command("powershell", "-NoProfile", "-WindowStyle", "Hidden", "-Command", script)
	if err := cmd.Start(); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	// Nothing waits on the process, so reap it in the background rather than
	// leaving a zombie for the life of the daemon.
	go cmd.Wait()

	writeJSON(w, map[string]string{"status": "launched"})
}

// psQuote escapes a value for a PowerShell single-quoted string, where the
// only special character is the quote itself.
func psQuote(v string) string { return strings.ReplaceAll(v, "'", "''") }
