//go:build linux

package system

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"

	"switchboard/backend/internal/protocol"
)

// Linux display control, the counterpart to display_windows.go.
//
// The split is the same one Windows makes, for the same physical reason:
//
//   - External monitors speak DDC/CI over their I2C bus, and carry their own
//     brightness and contrast ranges, which are frequently not 0-100. See
//     ddcci_linux.go.
//   - Internal laptop panels have no such bus and are driven through the
//     kernel's backlight class, brightness only -- which is why the built-in
//     display gets a single slider here exactly as it does on Windows. See
//     backlight_linux.go.
//
// What differs is enumeration. There is no EnumDisplayMonitors; /sys/class/drm
// is the authority, and it is a better one -- each connector states whether
// something is plugged into it, carries that panel's EDID, and links to the
// I2C bus that reaches it. Nothing has to be probed to find out what it is.

const drmRoot = "/sys/class/drm"

// A DRM connector directory: "card0-DP-1", "card1-eDP-1". The card prefix is
// the GPU, which is why the connector name alone is not unique on a laptop with
// a discrete GPU as well.
var drmConnector = regexp.MustCompile(`^card\d+-(.+)$`)

// Connector types that are soldered to the machine. The kernel spells them
// consistently, and the prefix is enough: "eDP-1", "LVDS-1", "DSI-1".
var internalConnectors = []string{"eDP", "LVDS", "DSI"}

// panel is one controllable display plus whatever drives it.
type panel struct {
	info protocol.Display

	// external panels only: the open DDC/CI bus.
	bus *ddcBus
	// internal panels only: the kernel backlight device.
	back *backlight

	// saved brightness when turned off, for restoring an internal panel.
	savedBrightness int
}

// displayController caches the enumerated panels and their open buses.
//
// Opening an i2c bus and probing it costs a round trip per VCP code at the
// spec's 50ms floor, so enumeration is far too slow to repeat per write; a
// brightness drag issues many writes a second. Buses stay open until the
// topology changes, exactly as the Windows backend holds its monitor handles.
type displayController struct {
	mu     sync.Mutex
	panels []*panel
	loaded bool

	// reloadFn is the re-enumeration withPanel falls back on, indirected only
	// so the retry branch can be exercised without a real hotplug.
	reloadFn func() error

	// permissionWarned keeps the "add yourself to the i2c group" advice to one
	// line per daemon rather than one per enumeration.
	permissionWarned bool
}

func newDisplayController() *displayController {
	c := &displayController{}
	c.reloadFn = c.reload
	return c
}

// List returns the current panels, enumerating on first use.
func (c *displayController) List() ([]protocol.Display, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.loaded {
		if err := c.reload(); err != nil {
			return nil, err
		}
	}
	out := make([]protocol.Display, 0, len(c.panels))
	for _, p := range c.panels {
		out = append(out, p.info)
	}
	return out, nil
}

// Refresh drops open buses and re-enumerates. Call after a display hotplug.
func (c *displayController) Refresh() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.reload()
}

func (c *displayController) reload() error {
	c.release()
	panels, err := c.enumeratePanels()
	if err != nil {
		return err
	}
	// No panel at all is a failure rather than an empty list: the caller turns
	// it into an absent "display" capability, and a machine whose only monitor
	// is behind an unreadable i2c bus should not claim it can drive displays.
	if len(panels) == 0 {
		return ErrUnsupported
	}
	c.panels = panels
	c.loaded = true
	return nil
}

func (c *displayController) release() {
	for _, p := range c.panels {
		if p.bus != nil {
			p.bus.Close()
		}
	}
	c.panels = nil
}

// Close releases every open bus.
func (c *displayController) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.release()
	c.loaded = false
}

func (c *displayController) find(id string) (*panel, error) {
	if !c.loaded {
		if err := c.reload(); err != nil {
			return nil, err
		}
	}
	for _, p := range c.panels {
		if p.info.ID == id {
			return p, nil
		}
	}
	return nil, fmt.Errorf("display %q not found", id)
}

// withPanel applies op to the panel named by id, re-enumerating once and
// retrying if either the lookup or the write fails.
//
// The Windows backend does this because a mode change invalidates its monitor
// handles. Here the failure is the mirror image: a DPMS-off panel, a monitor
// that was switched to another input by a KVM, or a suspend-resume that
// renumbered the i2c buses all leave an open file descriptor that writes into
// nothing. Re-enumerating on failure is what makes the cache self-healing, and
// it covers every entry point -- the phone, the desktop sliders and the deck
// keys all write through here.
func (c *displayController) withPanel(id string, op func(*panel) error) (protocol.Display, error) {
	p, err := c.find(id)
	if err == nil {
		if err = op(p); err == nil {
			return p.info, nil
		}
	}

	// reload closes the bus op just failed against and opens a fresh one, so
	// the panel pointer has to be looked up again rather than reused.
	if reloadErr := c.reloadFn(); reloadErr != nil {
		return protocol.Display{}, err
	}
	p, findErr := c.find(id)
	if findErr != nil {
		return protocol.Display{}, findErr
	}
	if err := op(p); err != nil {
		return protocol.Display{}, err
	}
	return p.info, nil
}

// SetBrightness clamps to the panel's reported capability range and writes it.
func (c *displayController) SetBrightness(id string, value int) (protocol.Display, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	// Clamping sits inside op so a retry re-clamps against the range the
	// freshly enumerated panel reports.
	return c.withPanel(id, func(p *panel) error {
		v := clamp(value, p.info.MinBright, p.info.MaxBright)
		if err := p.setBrightness(v); err != nil {
			return err
		}
		p.info.Brightness = v
		return nil
	})
}

// SetContrast writes VCP 0x12. Internal panels have no contrast control.
func (c *displayController) SetContrast(id string, value int) (protocol.Display, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	return c.withPanel(id, func(p *panel) error {
		if !p.info.HasContrast || p.bus == nil {
			return fmt.Errorf("display %q has no contrast control", p.info.Name)
		}
		v := clamp(value, p.info.MinContrast, p.info.MaxContrast)
		if err := p.bus.set(vcpContrast, v); err != nil {
			return fmt.Errorf("set contrast on %s: %w", p.info.Name, err)
		}
		p.info.Contrast = v
		return nil
	})
}

// SetPower toggles the display power state.
func (c *displayController) SetPower(id string, on bool) (protocol.Display, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	return c.withPanel(id, func(p *panel) error {
		if p.bus != nil {
			// VCP 0xD6: 1 is on, 4 is "off, and wake on input" -- the deepest
			// state a monitor will come back out of by itself.
			value := 4
			if on {
				value = 1
			}
			if err := p.bus.set(vcpPower, value); err != nil {
				return fmt.Errorf("set power on %s: %w", p.info.Name, err)
			}
			p.info.Power = on
			return nil
		}

		// An internal panel has no power VCP. Taking the backlight to its
		// minimum is what "off" means for one, and the level it was at has to
		// be remembered here because the hardware will not remember it.
		if !on {
			if p.info.Brightness > 0 {
				p.savedBrightness = p.info.Brightness
			}
			if err := p.setBrightness(p.info.MinBright); err != nil {
				return err
			}
			p.info.Brightness = p.info.MinBright
		} else {
			saved := p.savedBrightness
			if saved <= 0 {
				saved = 50
			}
			if err := p.setBrightness(saved); err != nil {
				return err
			}
			p.info.Brightness = saved
		}
		p.info.Power = on
		return nil
	})
}

func (p *panel) setBrightness(value int) error {
	if p.back != nil {
		return p.back.SetPercent(value)
	}
	if p.bus == nil {
		return ErrUnsupported
	}
	if err := p.bus.set(vcpBrightness, value); err != nil {
		return fmt.Errorf("set brightness on %s: %w", p.info.Name, err)
	}
	return nil
}

// --- Enumeration --------------------------------------------------------------

// enumeratePanels walks the connected DRM connectors and builds a panel for
// each one it can actually drive.
func (c *displayController) enumeratePanels() ([]*panel, error) {
	entries, err := os.ReadDir(drmRoot)
	if err != nil {
		// No DRM at all: a headless server, or a container without /sys.
		return nil, ErrUnsupported
	}

	names := make([]string, 0, len(entries))
	for _, entry := range entries {
		if drmConnector.MatchString(entry.Name()) {
			names = append(names, entry.Name())
		}
	}
	// ReadDir is already sorted, but the order panels appear in is what the UI
	// lists them in, so it is pinned rather than inherited.
	sort.Strings(names)

	backlights := discoverBacklights()
	usedBacklight := make(map[string]bool)
	permissionDenied := false

	var panels []*panel
	for _, connector := range names {
		dir := filepath.Join(drmRoot, connector)
		if status := readTrimmed(filepath.Join(dir, "status")); status != "connected" {
			continue
		}

		kind := drmConnector.FindStringSubmatch(connector)[1]
		name := connectorName(dir, kind)

		if isInternalConnector(kind) {
			if p := newInternalPanel(connector, name, backlights, usedBacklight); p != nil {
				panels = append(panels, p)
			}
			continue
		}

		p, err := externalPanel(dir, connector, name)
		if err != nil {
			if i2cPermissionHint(err) {
				permissionDenied = true
			}
			continue
		}
		if p != nil {
			panels = append(panels, p)
		}
	}

	if permissionDenied && !c.permissionWarned {
		c.permissionWarned = true
		log.Printf("external monitors are connected but /dev/i2c-* cannot be opened; " +
			"add yourself to the 'i2c' group (see scripts/install-linux.sh --i2c-setup) to control them")
	}
	return panels, nil
}

// newInternalPanel pairs a built-in connector with a kernel backlight device.
//
// Preferring the backlight that links back to this very connector, because a
// laptop with a discrete GPU can present two internal-looking connectors and
// only one of them is the panel with a backlight behind it.
func newInternalPanel(connector, name string, backlights []backlight, used map[string]bool) *panel {
	var chosen *backlight
	for i := range backlights {
		if used[backlights[i].name] {
			continue
		}
		if backlights[i].connector == connector {
			chosen = &backlights[i]
			break
		}
	}
	// No link to follow on an older kernel: the first unclaimed device, in the
	// order discoverBacklights ranked them.
	if chosen == nil {
		for i := range backlights {
			if !used[backlights[i].name] {
				chosen = &backlights[i]
				break
			}
		}
	}
	if chosen == nil {
		return nil
	}
	used[chosen.name] = true

	level, err := chosen.Percent()
	if err != nil {
		return nil
	}
	if name == "" {
		name = "Built-in display"
	}
	return &panel{
		back: chosen,
		info: protocol.Display{
			ID:         connector,
			Name:       name,
			Internal:   true,
			Brightness: level,
			MinBright:  0,
			// Percent, not the panel's raw range: the raw maximum is a
			// hardware detail (120000 on one Intel panel) that no slider should
			// ever show. backlight.SetPercent does the scaling.
			MaxBright: 100,
			Power:     level > 0,
		},
	}
}

// externalPanel opens the connector's I2C bus and asks the monitor what it can
// do. Returns (nil, nil) when the bus is readable but nothing on it speaks
// DDC/CI, which is an ordinary outcome and not worth reporting.
func externalPanel(dir, connector, name string) (*panel, error) {
	busPath, err := connectorBus(dir)
	if err != nil {
		return nil, err
	}

	bus, err := openDDCBus(busPath)
	if err != nil {
		return nil, err
	}

	// Brightness is the one code a panel must implement to be worth listing. A
	// monitor that will not answer it is not one these sliders can drive.
	current, max, err := bus.get(vcpBrightness)
	if err != nil || max <= 0 {
		bus.Close()
		return nil, nil
	}

	if name == "" {
		name = connector
	}
	p := &panel{
		bus: bus,
		info: protocol.Display{
			ID:         connector,
			Name:       name,
			Brightness: current,
			MinBright:  0,
			MaxBright:  max,
			Power:      true,
		},
	}

	if curC, maxC, err := bus.get(vcpContrast); err == nil && maxC > 0 {
		p.info.HasContrast = true
		p.info.Contrast = curC
		p.info.MinContrast = 0
		p.info.MaxContrast = maxC
	}

	if power, _, err := bus.get(vcpPower); err == nil {
		p.info.Power = power == 1
	}
	return p, nil
}

// connectorBus finds the /dev/i2c-N that reaches this connector's monitor.
//
// The `ddc` link is the kernel telling us directly, and is present on i915,
// amdgpu and nouveau. The i2c-dev subdirectory is the older spelling of the
// same fact. Guessing by scanning every bus on the machine is deliberately not
// a fallback: writing DDC/CI packets at whatever else is on the I2C bus is not
// something to do speculatively.
func connectorBus(dir string) (string, error) {
	candidates := []string{
		filepath.Join(dir, "ddc", "i2c-dev"),
		filepath.Join(dir, "i2c-dev"),
	}
	for _, base := range candidates {
		entries, err := os.ReadDir(base)
		if err != nil {
			continue
		}
		for _, entry := range entries {
			if strings.HasPrefix(entry.Name(), "i2c-") {
				return filepath.Join("/dev", entry.Name()), nil
			}
		}
	}

	// "card1-eDP-1/ddc -> i2c-6", the plain symlink form.
	if target, err := os.Readlink(filepath.Join(dir, "ddc")); err == nil {
		if base := filepath.Base(target); strings.HasPrefix(base, "i2c-") {
			return filepath.Join("/dev", base), nil
		}
	}
	return "", fmt.Errorf("no i2c bus for %s", filepath.Base(dir))
}

// connectorName reads the model name out of the connector's EDID, so the UI
// shows "EK240Y P6" rather than "DP-1". Falls back to the connector type.
func connectorName(dir, kind string) string {
	edid, err := os.ReadFile(filepath.Join(dir, "edid"))
	// An unplugged or unreadable connector leaves a zero-length file rather
	// than failing the read.
	if err == nil && len(edid) >= 128 {
		if name := edidMonitorName(edid); name != "" {
			return name
		}
	}
	if isInternalConnector(kind) {
		return "Built-in display"
	}
	return kind
}

func isInternalConnector(kind string) bool {
	for _, prefix := range internalConnectors {
		if strings.HasPrefix(kind, prefix) {
			return true
		}
	}
	return false
}

func readTrimmed(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(data))
}
