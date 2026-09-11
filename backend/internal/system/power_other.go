//go:build !windows

package system

func powerAction(string, int) error { return ErrUnsupported }
func powerSupported() bool          { return false }
