//go:build !windows

package system

func inputText(string) error    { return ErrUnsupported }
func keyboardSupported() bool   { return false }
func setClipboard(string) error { return ErrUnsupported }
func clipboardSupported() bool  { return false }
