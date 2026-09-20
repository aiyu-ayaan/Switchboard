//go:build !windows && !linux

package system

import "switchboard/backend/internal/protocol"

// Display control has a Windows backend (display_windows.go) and a Linux one
// (display_linux.go). Everything else -- macOS, the BSDs -- still reports the
// capability as absent, which the UI renders as "no controllable displays"
// rather than as a failure.

type displayController struct{}

func newDisplayController() *displayController { return &displayController{} }

func (c *displayController) List() ([]protocol.Display, error) { return nil, ErrUnsupported }
func (c *displayController) Refresh() error                    { return ErrUnsupported }
func (c *displayController) Close()                            {}

func (c *displayController) SetBrightness(string, int) (protocol.Display, error) {
	return protocol.Display{}, ErrUnsupported
}

func (c *displayController) SetContrast(string, int) (protocol.Display, error) {
	return protocol.Display{}, ErrUnsupported
}

func (c *displayController) SetPower(string, bool) (protocol.Display, error) {
	return protocol.Display{}, ErrUnsupported
}
