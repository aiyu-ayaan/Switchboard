//go:build !windows && !linux

package system

// Air mouse stubs. The Windows implementation, which injects through
// SendInput, lives in input_windows.go.

func inputSupported() bool { return false }

func moveMouse(float64, float64) error { return ErrUnsupported }

func mouseButton(string, string) error { return ErrUnsupported }

func scrollMouse(float64, float64, bool) error { return ErrUnsupported }

func shellGesture(string) error { return ErrUnsupported }
