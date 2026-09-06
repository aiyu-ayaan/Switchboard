//go:build !windows

package system

func executeHotkey(string) error { return ErrUnsupported }
func openURL(string) error       { return ErrUnsupported }
func launchApp(string) error     { return ErrUnsupported }
