//go:build !windows && !linux

package system

// Remote typing stub. Windows injects through SendInput (text_windows.go) and
// Linux through XTEST (input_linux.go).

func inputText(string) error  { return ErrUnsupported }
func keyboardSupported() bool { return false }
