//go:build linux

package system

import (
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"

	"github.com/godbus/dbus/v5"
)

// Internal laptop panels, which have no DDC/CI bus to talk to.
//
// The kernel exposes them at /sys/class/backlight/<device>, and writing the
// `brightness` file is the whole of setting one -- except that the file is
// root-owned on every mainstream distribution, and Switchboard has no business
// asking for root to move a slider.
//
// So there are two ways in, tried in order:
//
//   - The sysfs file itself, which works where a udev rule has handed the
//     `video` group write access (some distributions ship one; scripts/
//     install-linux.sh can install one).
//   - logind's SetBrightness, which takes the write on behalf of whoever owns
//     the active session. No root, no configuration, and it is how GNOME and
//     KDE move their own brightness sliders.
//
// The first is a plain write and the second is a round trip to the system bus,
// which is why it is the fallback rather than the only path.

const backlightRoot = "/sys/class/backlight"

// backlight is one kernel backlight device.
type backlight struct {
	// name is the directory under /sys/class/backlight, and also the device
	// name logind wants.
	name string
	// max is the panel's own top raw value, which is hardware-specific --
	// 120000 on one Intel panel here, 255 or 100 elsewhere. Never shown to a
	// caller; the wire protocol carries percent.
	max int
	// connector is the DRM connector this panel hangs off ("card1-eDP-1"), or
	// "" when the link is not there to follow.
	connector string
}

// discoverBacklights lists the kernel's backlight devices, best first.
func discoverBacklights() []backlight {
	entries, err := os.ReadDir(backlightRoot)
	if err != nil {
		return nil
	}

	var found []backlight
	for _, entry := range entries {
		dir := filepath.Join(backlightRoot, entry.Name())
		max, err := readIntFile(filepath.Join(dir, "max_brightness"))
		// A device reporting a zero or missing maximum cannot be scaled into a
		// percentage, and there is nothing sensible to show for it.
		if err != nil || max <= 0 {
			continue
		}
		found = append(found, backlight{
			name:      entry.Name(),
			max:       max,
			connector: backlightConnector(dir),
		})
	}

	// A machine can expose the same panel two or three times over -- the
	// firmware interface, the platform vendor's own, and the raw registers.
	// They are ordered by how well they survive a suspend or a hotkey press,
	// which is the order the kernel's own consumers prefer.
	rank := map[string]int{"firmware": 0, "platform": 1, "raw": 2}
	sort.SliceStable(found, func(i, j int) bool {
		ri, ok := rank[backlightType(found[i].name)]
		if !ok {
			ri = 3
		}
		rj, ok := rank[backlightType(found[j].name)]
		if !ok {
			rj = 3
		}
		return ri < rj
	})
	return found
}

func backlightType(name string) string {
	value, err := os.ReadFile(filepath.Join(backlightRoot, name, "type"))
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(value))
}

// backlightConnector follows the `device` link back to the DRM connector the
// panel belongs to, so the built-in display can be named after its connector
// and matched to the one enumerated from /sys/class/drm.
func backlightConnector(dir string) string {
	target, err := os.Readlink(filepath.Join(dir, "device"))
	if err != nil {
		return ""
	}
	base := filepath.Base(target)
	// The link points at the connector on a modern kernel ("../../card1-eDP-1")
	// and at the PCI device on an older one, which is no use here.
	if strings.Contains(base, "-") && strings.HasPrefix(base, "card") {
		return base
	}
	return ""
}

// Percent reads the panel's current level as 0-100.
//
// `actual_brightness` is what the hardware is really at, which can differ from
// `brightness` after a hotkey or a resume; it is the honest one to show.
func (b backlight) Percent() (int, error) {
	dir := filepath.Join(backlightRoot, b.name)
	raw, err := readIntFile(filepath.Join(dir, "actual_brightness"))
	if err != nil {
		raw, err = readIntFile(filepath.Join(dir, "brightness"))
		if err != nil {
			return 0, err
		}
	}
	return clamp(int((float64(raw)/float64(b.max))*100+0.5), 0, 100), nil
}

// SetPercent writes a 0-100 level, scaled to the panel's own range.
func (b backlight) SetPercent(percent int) error {
	percent = clamp(percent, 0, 100)
	raw := int((float64(percent) / 100 * float64(b.max)) + 0.5)
	// Rounding must not turn a non-zero request into a black screen on a panel
	// whose range is small.
	if raw == 0 && percent > 0 {
		raw = 1
	}

	path := filepath.Join(backlightRoot, b.name, "brightness")
	writeErr := os.WriteFile(path, []byte(strconv.Itoa(raw)), 0o644)
	if writeErr == nil {
		return nil
	}

	if logindErr := setBrightnessViaLogind("backlight", b.name, raw); logindErr != nil {
		// Both paths reported, because which one a person can fix depends
		// entirely on their machine.
		return fmt.Errorf("set brightness on %s: writing %s: %w; via logind: %v",
			b.name, path, writeErr, logindErr)
	}
	return nil
}

// --- logind ------------------------------------------------------------------

var (
	logindOnce sync.Once
	logindConn *dbus.Conn
	logindErr  error
)

// systemBus dials the system bus once and keeps the connection.
func systemBus() (*dbus.Conn, error) {
	logindOnce.Do(func() {
		logindConn, logindErr = dbus.SystemBus()
	})
	return logindConn, logindErr
}

// setBrightnessViaLogind asks logind to make the write.
//
// It permits it only for the session that currently owns the seat, which is the
// right rule: a user at the keyboard can dim their own screen, and one logged in
// over SSH cannot dim somebody else's.
func setBrightnessViaLogind(subsystem, name string, value int) error {
	conn, err := systemBus()
	if err != nil {
		return fmt.Errorf("system bus: %w", err)
	}

	path, err := logindSessionPath(conn)
	if err != nil {
		return err
	}

	call := conn.Object("org.freedesktop.login1", path).Call(
		"org.freedesktop.login1.Session.SetBrightness", 0,
		subsystem, name, uint32(value))
	if call.Err != nil {
		return fmt.Errorf("logind SetBrightness: %w", call.Err)
	}
	return nil
}

// logindSessionPath finds the session object this process belongs to.
//
// By PID rather than by the XDG_SESSION_ID environment variable: the daemon is
// started by Electron, which inherits whatever the desktop environment set, and
// a session id is exactly the kind of variable that goes missing on the way
// through. The cgroup the kernel puts the process in does not.
func logindSessionPath(conn *dbus.Conn) (dbus.ObjectPath, error) {
	manager := conn.Object("org.freedesktop.login1", "/org/freedesktop/login1")

	var path dbus.ObjectPath
	err := manager.Call("org.freedesktop.login1.Manager.GetSessionByPID", 0,
		uint32(os.Getpid())).Store(&path)
	if err == nil {
		return path, nil
	}

	// A daemon started outside any session -- from a systemd user unit, or
	// anything that escaped the session's cgroup -- still has a session to
	// borrow if the environment named one.
	if id := os.Getenv("XDG_SESSION_ID"); id != "" {
		if err := manager.Call("org.freedesktop.login1.Manager.GetSession", 0, id).Store(&path); err == nil {
			return path, nil
		}
	}

	// Last, this user's own graphical session, whichever process is asking.
	// logind still applies its own rule on top -- the write only lands if that
	// session is the active one -- so borrowing it cannot reach a screen this
	// user is not sitting at.
	if display, derr := logindUserDisplay(conn); derr == nil {
		return display, nil
	}

	return "", fmt.Errorf("logind: this process is not in a login session: %w", err)
}

// logindUserDisplay reads the `Display` property off this uid's user object:
// the session logind considers their primary graphical one.
func logindUserDisplay(conn *dbus.Conn) (dbus.ObjectPath, error) {
	manager := conn.Object("org.freedesktop.login1", "/org/freedesktop/login1")

	var user dbus.ObjectPath
	if err := manager.Call("org.freedesktop.login1.Manager.GetUser", 0,
		uint32(os.Getuid())).Store(&user); err != nil {
		return "", err
	}

	// The property is a (session id, object path) pair; only the path is used.
	value, err := conn.Object("org.freedesktop.login1", user).
		GetProperty("org.freedesktop.login1.User.Display")
	if err != nil {
		return "", err
	}
	fields, ok := value.Value().([]interface{})
	if !ok || len(fields) < 2 {
		return "", fmt.Errorf("logind: unexpected User.Display value %v", value)
	}
	path, ok := fields[1].(dbus.ObjectPath)
	if !ok || path == "" {
		return "", fmt.Errorf("logind: this user has no graphical session")
	}
	return path, nil
}

func readIntFile(path string) (int, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return 0, err
	}
	return strconv.Atoi(strings.TrimSpace(string(data)))
}
