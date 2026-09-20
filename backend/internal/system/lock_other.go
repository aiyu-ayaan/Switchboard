//go:build !windows

package system

// Session lock stubs. The Windows implementation lives in lock_windows.go.

func lockSystem() error { return ErrUnsupported }

func lockSupported() bool { return false }

func isLocked() bool { return false }
