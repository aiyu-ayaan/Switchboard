//go:build !windows

package system

// Clipboard stub. The Windows implementation lives in clipboard_windows.go.
//
// There is no Linux backend yet, and unlike the other controls that is not
// simply a matter of writing one. X11 has no clipboard to write to: the
// selection lives in the owning client, which must stay running and answer
// SelectionRequest events for as long as it holds it. Supporting it means the
// daemon owning a selection and serving an X event loop, which is a different
// shape of work from the stateless injection in input_linux.go.

func setClipboard(string) error { return ErrUnsupported }

func clipboardSupported() bool { return false }
