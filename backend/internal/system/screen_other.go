//go:build !windows && !linux

package system

// Screen sharing stubs. The capture backends live in screen_windows.go and
// screen_linux.go.

func screenSupported() bool { return false }

func captureScreen() (*ScreenFrame, error) { return nil, ErrUnsupported }

func pointerTo(float64, float64) error { return ErrUnsupported }

func pressKey(string) error { return ErrUnsupported }
