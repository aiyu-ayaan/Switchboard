//go:build windows

package server

// unlockSetupSupported gates the Settings section on the platform that has an
// implementation, separately from whether setup has been run yet.
func unlockSetupSupported() bool { return true }
