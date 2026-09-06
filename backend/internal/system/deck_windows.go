//go:build windows

package system

import (
	"os/exec"
	"syscall"
)

const vkShift = 0x10

func executeHotkey(chord string) error {
	keys, err := ParseHotkeyChord(chord)
	if err != nil {
		return err
	}
	if len(keys) == 0 {
		return nil
	}

	events := make([]rawInput, 0, len(keys)*2)
	for _, vk := range keys {
		events = append(events, newKeyInput(vk, false))
	}
	for i := len(keys) - 1; i >= 0; i-- {
		events = append(events, newKeyInput(keys[i], true))
	}
	return sendInputs(events...)
}

func openURL(rawURL string) error {
	cmd := exec.Command("rundll32", "url.dll,FileProtocolHandler", rawURL)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	return cmd.Start()
}

func launchApp(app string) error {
	cmd := exec.Command("cmd", "/c", "start", "", app)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	return cmd.Start()
}
