//go:build linux

package system

import (
	"bytes"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"golang.org/x/sys/unix"
)

// Finding a connector's I2C bus when the driver does not say which it is.
//
// i915, amdgpu and nouveau link each DRM connector to its bus (`ddc`), and
// connectorBus follows that link. NVIDIA's proprietary driver does not: its
// connectors carry an EDID but no link, while its buses appear as ordinary
// /dev/i2c-N adapters named "NVIDIA i2c adapter N". Without a way to pair the
// two, every monitor on an NVIDIA card was invisible, which on a hybrid laptop
// is every external monitor.
//
// The pairing used here is the one ddcutil uses: read the EDID each candidate
// bus returns and keep the bus whose EDID is the connector's, byte for byte.
// Two limits keep it from being the speculative bus scan connectorBus refuses
// to do. Only adapters belonging to the connector's own GPU are considered,
// and the only thing sent to them is an EDID read from the 0x50 EEPROM -- the
// request every graphics driver makes of every monitor at hotplug. DDC/CI
// itself is only ever spoken on the one bus that matched.

const (
	edidAddress = 0x50
	edidBlock   = 128
	i2cDevices  = "/sys/bus/i2c/devices"
)

var edidHeader = []byte{0x00, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x00}

// busByEDID returns the /dev/i2c-N on the connector's GPU whose monitor
// reports the connector's EDID. used holds buses already paired with another
// connector, so two identical monitors cannot both claim the first match.
func busByEDID(dir string, used map[string]bool) (string, error) {
	want, err := os.ReadFile(filepath.Join(dir, "edid"))
	if err != nil || len(want) < edidBlock || !bytes.HasPrefix(want, edidHeader) {
		return "", fmt.Errorf("no EDID for %s", filepath.Base(dir))
	}
	// connector -> card -> PCI device.
	gpu, err := filepath.EvalSymlinks(filepath.Join(dir, "device", "device"))
	if err != nil {
		return "", fmt.Errorf("no GPU for %s: %w", filepath.Base(dir), err)
	}

	var lastErr error
	for _, bus := range gpuBuses(gpu) {
		if used[bus] {
			continue
		}
		edid, err := readEDID(bus)
		if err != nil {
			lastErr = err
			continue
		}
		if bytes.Equal(edid, want[:edidBlock]) {
			return bus, nil
		}
	}
	if lastErr != nil {
		return "", fmt.Errorf("no i2c bus for %s: %w", filepath.Base(dir), lastErr)
	}
	return "", fmt.Errorf("no i2c bus for %s", filepath.Base(dir))
}

// gpuBuses lists the i2c-dev nodes whose adapter is a child of the GPU, in
// bus order.
func gpuBuses(gpu string) []string {
	entries, err := os.ReadDir(i2cDevices)
	if err != nil {
		return nil
	}
	var buses []string
	for _, entry := range entries {
		name := entry.Name()
		if !strings.HasPrefix(name, "i2c-") {
			continue
		}
		resolved, err := filepath.EvalSymlinks(filepath.Join(i2cDevices, name))
		if err != nil || filepath.Dir(resolved) != gpu {
			continue
		}
		buses = append(buses, filepath.Join("/dev", name))
	}
	sort.Slice(buses, func(i, j int) bool { return busNumber(buses[i]) < busNumber(buses[j]) })
	return buses
}

func busNumber(path string) int {
	var n int
	fmt.Sscanf(filepath.Base(path), "i2c-%d", &n)
	return n
}

// readEDID reads the first EDID block from a bus: set the EEPROM's offset to
// zero, then read 128 bytes.
func readEDID(path string) ([]byte, error) {
	fd, err := unix.Open(path, unix.O_RDWR|unix.O_CLOEXEC, 0)
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	defer unix.Close(fd)
	if err := unix.IoctlSetInt(fd, i2cSlave, edidAddress); err != nil {
		if err := unix.IoctlSetInt(fd, i2cSlaveForce, edidAddress); err != nil {
			return nil, fmt.Errorf("address %s at 0x%02x: %w", path, edidAddress, err)
		}
	}
	if _, err := unix.Write(fd, []byte{0}); err != nil {
		return nil, fmt.Errorf("edid offset on %s: %w", path, err)
	}
	edid := make([]byte, edidBlock)
	n, err := unix.Read(fd, edid)
	if err != nil {
		return nil, fmt.Errorf("edid read on %s: %w", path, err)
	}
	if n != edidBlock || !bytes.HasPrefix(edid, edidHeader) {
		return nil, fmt.Errorf("no EDID on %s", path)
	}
	return edid, nil
}
