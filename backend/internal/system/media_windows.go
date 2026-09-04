//go:build windows

package system

import (
	"fmt"

	"golang.org/x/sys/windows"
)

// Media transport is driven by synthesising the dedicated multimedia key
// presses. Windows routes these to whichever application currently holds the
// System Media Transport Controls session, so Spotify, browsers and native
// players all respond without per-app integration.
var procKeybdEvent = user32.NewProc("keybd_event")

const (
	vkMediaNextTrack = 0xB0
	vkMediaPrevTrack = 0xB1
	vkMediaStop      = 0xB2
	vkMediaPlayPause = 0xB3

	keyEventExtendedKey = 0x0001
	keyEventKeyUp       = 0x0002
)

// mediaKeys maps protocol actions to virtual key codes. Play and pause both
// map to the toggle key: Windows exposes no separate play or pause key, and
// the transport controls are stateful on the application side.
var mediaKeys = map[string]uintptr{
	"play":   vkMediaPlayPause,
	"pause":  vkMediaPlayPause,
	"toggle": vkMediaPlayPause,
	"next":   vkMediaNextTrack,
	"prev":   vkMediaPrevTrack,
	"stop":   vkMediaStop,
}

func sendMediaCommand(action string) error {
	vk, ok := mediaKeys[action]
	if !ok {
		return fmt.Errorf("media: unsupported action %q", action)
	}
	if err := tapKey(vk); err != nil {
		return fmt.Errorf("media: %s: %w", action, err)
	}
	return nil
}

func tapKey(vk uintptr) error {
	if r, _, err := procKeybdEvent.Call(vk, 0, keyEventExtendedKey, 0); r == 0 && err != windows.ERROR_SUCCESS {
		return err
	}
	if r, _, err := procKeybdEvent.Call(vk, 0, keyEventExtendedKey|keyEventKeyUp, 0); r == 0 && err != windows.ERROR_SUCCESS {
		return err
	}
	return nil
}

// mediaSupported reports whether this platform can drive media transport.
func mediaSupported() bool { return true }
