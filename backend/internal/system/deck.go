package system

import (
	"fmt"
	"strings"

	"switchboard/backend/internal/protocol"
)

// ExecuteDeckAction executes a Stream Deck key action on the host system.
func (c *Controller) ExecuteDeckAction(action protocol.DeckAction) error {
	switch action.Type {
	case "url":
		if action.Value == "" {
			return fmt.Errorf("deck: empty url")
		}
		return openURL(action.Value)

	case "hotkey":
		if action.Value == "" {
			return fmt.Errorf("deck: empty hotkey chord")
		}
		return executeHotkey(action.Value)

	case "media":
		switch action.Value {
		case "vol_up":
			vol, err := c.Volume()
			if err != nil {
				return err
			}
			newLevel := clamp(vol.Level+5, 0, 100)
			_, err = c.SetVolume(newLevel, false)
			return err
		case "vol_down":
			vol, err := c.Volume()
			if err != nil {
				return err
			}
			newLevel := clamp(vol.Level-5, 0, 100)
			_, err = c.SetVolume(newLevel, false)
			return err
		case "mute":
			vol, err := c.Volume()
			if err != nil {
				return err
			}
			_, err = c.SetVolume(vol.Level, !vol.Muted)
			return err
		default:
			return c.Media(action.Value)
		}

	case "system":
		switch action.Value {
		case "lock":
			return c.Lock()
		case "screenshot":
			return executeHotkey("win+shift+s")
		case "bright_up":
			displays, err := c.Displays()
			if err != nil || len(displays) == 0 {
				return err
			}
			d := displays[0]
			_, err = c.SetBrightness(d.ID, clamp(d.Brightness+10, d.MinBright, d.MaxBright))
			return err
		case "bright_down":
			displays, err := c.Displays()
			if err != nil || len(displays) == 0 {
				return err
			}
			d := displays[0]
			_, err = c.SetBrightness(d.ID, clamp(d.Brightness-10, d.MinBright, d.MaxBright))
			return err
		default:
			return fmt.Errorf("deck: unknown system action %q", action.Value)
		}

	case "app":
		if action.Value == "" {
			return fmt.Errorf("deck: empty app name or command")
		}
		return launchApp(action.Value)

	case "page":
		// Page navigation is managed at client layer or synchronized in state.
		return nil

	default:
		return fmt.Errorf("deck: unsupported action type %q", action.Type)
	}
}

// ParseHotkeyChord maps a human-readable key combination like "ctrl+c" or "win+d"
// into Windows virtual key codes.
func ParseHotkeyChord(chord string) ([]uint16, error) {
	parts := strings.Split(chord, "+")
	keys := make([]uint16, 0, len(parts))
	for _, raw := range parts {
		part := strings.TrimSpace(strings.ToLower(raw))
		if part == "" {
			continue
		}
		switch part {
		case "ctrl", "control":
			keys = append(keys, 0x11)
		case "shift":
			keys = append(keys, 0x10)
		case "alt", "opt", "option":
			keys = append(keys, 0x12)
		case "win", "windows", "cmd", "command", "meta", "super":
			keys = append(keys, 0x5B)
		case "tab":
			keys = append(keys, 0x09)
		case "enter", "return":
			keys = append(keys, 0x0D)
		case "esc", "escape":
			keys = append(keys, 0x1B)
		case "space":
			keys = append(keys, 0x20)
		case "backspace":
			keys = append(keys, 0x08)
		case "delete", "del":
			keys = append(keys, 0x2E)
		case "insert":
			keys = append(keys, 0x2D)
		case "home":
			keys = append(keys, 0x24)
		case "end":
			keys = append(keys, 0x23)
		case "pageup", "pgup":
			keys = append(keys, 0x21)
		case "pagedown", "pgdn":
			keys = append(keys, 0x22)
		case "left":
			keys = append(keys, 0x25)
		case "up":
			keys = append(keys, 0x26)
		case "right":
			keys = append(keys, 0x27)
		case "down":
			keys = append(keys, 0x28)
		case "printscreen", "prtscn":
			keys = append(keys, 0x2C)
		case "f1":
			keys = append(keys, 0x70)
		case "f2":
			keys = append(keys, 0x71)
		case "f3":
			keys = append(keys, 0x72)
		case "f4":
			keys = append(keys, 0x73)
		case "f5":
			keys = append(keys, 0x74)
		case "f6":
			keys = append(keys, 0x75)
		case "f7":
			keys = append(keys, 0x76)
		case "f8":
			keys = append(keys, 0x77)
		case "f9":
			keys = append(keys, 0x78)
		case "f10":
			keys = append(keys, 0x79)
		case "f11":
			keys = append(keys, 0x7A)
		case "f12":
			keys = append(keys, 0x7B)
		default:
			if len(part) == 1 {
				ch := part[0]
				if ch >= 'a' && ch <= 'z' {
					keys = append(keys, uint16(ch-'a'+0x41))
				} else if ch >= '0' && ch <= '9' {
					keys = append(keys, uint16(ch-'0'+0x30))
				} else {
					return nil, fmt.Errorf("deck: unsupported hotkey key %q", part)
				}
			} else {
				return nil, fmt.Errorf("deck: unsupported hotkey key %q", part)
			}
		}
	}
	return keys, nil
}
