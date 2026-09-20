//go:build linux

package system

import (
	"bufio"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"sort"
	"strconv"
	"strings"

	"switchboard/backend/internal/protocol"
)

// Linux hardware and OS report, the counterpart to about_windows.go.
//
// Windows answers all of this from WMI and the registry. Linux has no single
// inventory: the distribution is in /etc/os-release, the kernel in
// /proc/version, the CPU in /proc/cpuinfo, the GPUs on the PCI bus, the
// battery in /sys/class/power_supply. Each is read from the place that owns
// it, and each is allowed to come back empty -- a virtual machine has no
// battery, a headless box has no GPU -- because a missing section is a fact
// about the host rather than a failure to report on it.
//
// Nothing here needs root. The one thing that would -- DMI, which is where the
// memory module type and slot count live -- is 0400 on every distribution, so
// those two fields are left unset rather than guessed at, and the phone hides
// the rows it has no values for.

func aboutSystem() (protocol.AboutSystemResponse, error) {
	_, totalRAM := sampleLinuxMemory()
	return protocol.AboutSystemResponse{
		OS:  linuxOSInfo(),
		CPU: linuxCPUInfo(),
		Memory: protocol.MemoryInfo{
			TotalBytes: totalRAM,
			Type:       "System RAM",
		},
		GPUs:    linuxGPUInfo(),
		Drives:  sampleLinuxDrives(),
		Battery: linuxBatteryInfo(),
	}, nil
}

// ---- operating system ----

// linuxOSInfo reports the distribution as the OS name and the kernel as the
// build.
//
// That split is the one a user recognises: "Linux Mint 22.3" is what they
// would call the operating system, and the kernel release is the build number
// underneath it, exactly as Windows shows an edition over a build. Reporting
// "Linux" as the name would be true and useless on a screen whose whole
// purpose is telling one machine from another.
func linuxOSInfo() protocol.OsInfo {
	info := protocol.OsInfo{
		Name:          osReleaseName(),
		Build:         kernelRelease(),
		UptimeSeconds: uptimeSeconds(),
	}
	if info.Name == "" {
		info.Name = "Linux"
	}
	return info
}

// osReleaseName reads the distribution name from os-release.
//
// /etc/os-release is the freedesktop standard and is present on every
// systemd-era distribution; /usr/lib/os-release is the fallback it specifies
// for images that keep /etc empty.
func osReleaseName() string {
	for _, path := range []string{"/etc/os-release", "/usr/lib/os-release"} {
		f, err := os.Open(path)
		if err != nil {
			continue
		}
		fields := map[string]string{}
		scanner := bufio.NewScanner(f)
		for scanner.Scan() {
			key, value, ok := strings.Cut(scanner.Text(), "=")
			if !ok {
				continue
			}
			// Values are shell-quoted, and whether they are quoted at all
			// varies by distribution and by field.
			fields[key] = strings.Trim(strings.TrimSpace(value), `"'`)
		}
		f.Close()

		if name := fields["PRETTY_NAME"]; name != "" {
			return name
		}
		if name := fields["NAME"]; name != "" {
			if version := fields["VERSION"]; version != "" {
				return name + " " + version
			}
			return name
		}
	}
	return ""
}

// kernelRelease returns the running kernel's release string, the Linux
// equivalent of a Windows build number.
func kernelRelease() string {
	// /proc/sys/kernel/osrelease is uname -r without needing the syscall's
	// fixed-size buffer marshalling, and is what the kernel itself reports.
	if release := readSysFile("/proc/sys/kernel/osrelease"); release != "" {
		return release
	}
	return ""
}

// uptimeSeconds reads how long the host has been up. /proc/uptime's first
// field is seconds since boot as a float.
func uptimeSeconds() uint64 {
	fields := strings.Fields(readSysFile("/proc/uptime"))
	if len(fields) == 0 {
		return 0
	}
	seconds, err := strconv.ParseFloat(fields[0], 64)
	if err != nil || seconds < 0 {
		return 0
	}
	return uint64(seconds)
}

// ---- CPU ----

// linuxCPUInfo reports the processor model and its real core topology.
//
// Physical cores are counted as distinct (physical id, core id) pairs rather
// than by counting "processor" entries, which counts hyperthreads. On the
// machine this was written on that is the difference between reporting 4 cores
// and 8, and the phone shows both numbers side by side.
func linuxCPUInfo() protocol.CpuInfo {
	info := protocol.CpuInfo{Threads: runtime.NumCPU()}

	f, err := os.Open("/proc/cpuinfo")
	if err == nil {
		defer f.Close()

		cores := map[string]bool{}
		var physicalID, coreID string
		threads := 0

		scanner := bufio.NewScanner(f)
		for scanner.Scan() {
			line := scanner.Text()
			if line == "" {
				// Blank line ends one logical processor's block.
				if physicalID != "" && coreID != "" {
					cores[physicalID+":"+coreID] = true
				}
				physicalID, coreID = "", ""
				continue
			}
			key, value, ok := strings.Cut(line, ":")
			if !ok {
				continue
			}
			key = strings.TrimSpace(key)
			value = strings.TrimSpace(value)
			switch key {
			case "processor":
				threads++
			case "model name":
				if info.Model == "" {
					info.Model = value
				}
			case "Model", "Hardware":
				// ARM boards name themselves here instead; several have no
				// "model name" line at all.
				if info.Model == "" {
					info.Model = value
				}
			case "physical id":
				physicalID = value
			case "core id":
				coreID = value
			}
		}
		if physicalID != "" && coreID != "" {
			cores[physicalID+":"+coreID] = true
		}

		if threads > 0 {
			info.Threads = threads
		}
		info.Cores = len(cores)
	}

	if info.Cores == 0 {
		// ARM and several virtualised x86 hosts publish no topology at all.
		// One core per thread is wrong only where SMT exists, and claiming
		// fewer cores than the machine has is the safer error.
		info.Cores = info.Threads
	}
	if info.Model == "" {
		info.Model = "Unknown Processor"
	}
	info.BaseClockMhz = cpuBaseClockMHz(info.Model)
	return info
}

// cpuBaseClockMHz reports the processor's nominal clock.
//
// The "cpu MHz" line in /proc/cpuinfo is deliberately not used: it is the
// current frequency, which on an idle laptop reads around 800 MHz and would
// have the phone show a different "base clock" on every refresh. cpufreq's
// base_frequency is the real nominal value where the driver publishes it, and
// the figure Intel and AMD print in the model name is the same number
// everywhere else.
func cpuBaseClockMHz(model string) int {
	if khz, ok := readSysUint("/sys/devices/system/cpu/cpu0/cpufreq/base_frequency"); ok && khz > 0 {
		return int(khz / 1000)
	}
	if mhz := clockFromModelName(model); mhz > 0 {
		return mhz
	}
	// Last resort: the maximum the governor will select. It is the boost
	// ceiling rather than the base, which overstates it, but a plausible
	// number beats an empty row.
	if khz, ok := readSysUint("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq"); ok && khz > 0 {
		return int(khz / 1000)
	}
	return 0
}

// clockFromModelName pulls the clock out of a model string such as
// "Intel(R) Core(TM) i5-10200H CPU @ 2.40GHz".
func clockFromModelName(model string) int {
	_, after, ok := strings.Cut(model, "@")
	if !ok {
		return 0
	}
	value := strings.TrimSpace(after)
	switch {
	case strings.HasSuffix(value, "GHz"):
		ghz, err := strconv.ParseFloat(strings.TrimSpace(strings.TrimSuffix(value, "GHz")), 64)
		if err != nil || ghz <= 0 {
			return 0
		}
		return int(ghz*1000 + 0.5)
	case strings.HasSuffix(value, "MHz"):
		mhz, err := strconv.ParseFloat(strings.TrimSpace(strings.TrimSuffix(value, "MHz")), 64)
		if err != nil || mhz <= 0 {
			return 0
		}
		return int(mhz + 0.5)
	}
	return 0
}

// ---- GPU ----

// linuxGPUInfo enumerates graphics adapters through DRM.
//
// /sys/class/drm is the authority for what is actually driving a display --
// the same source the display backend enumerates monitors from -- and each
// card links back to its PCI device, which carries the vendor and device IDs
// and the bound driver. What sysfs does not carry is a human-readable name, so
// the PCI slot is resolved through lspci, whose database is the one every
// desktop already uses for this.
func linuxGPUInfo() []protocol.GpuInfo {
	cards, err := filepath.Glob("/sys/class/drm/card[0-9]")
	if err != nil {
		return nil
	}
	more, _ := filepath.Glob("/sys/class/drm/card[0-9][0-9]")
	cards = append(cards, more...)
	sort.Strings(cards)
	if len(cards) == 0 {
		return nil
	}

	names := pciDeviceNames()
	seen := map[string]bool{}
	var gpus []protocol.GpuInfo

	for _, card := range cards {
		device, err := filepath.EvalSymlinks(filepath.Join(card, "device"))
		if err != nil {
			continue
		}
		slot := filepath.Base(device)
		// A card with several connectors appears once, but a hybrid laptop has
		// a real second GPU; deduplicating by PCI slot separates the two.
		if seen[slot] {
			continue
		}
		seen[slot] = true

		driver := ""
		if target, err := os.Readlink(filepath.Join(device, "driver")); err == nil {
			driver = filepath.Base(target)
		}

		name := names[slot]
		if name == "" {
			// No lspci and no database: the raw PCI IDs at least identify the
			// part to anyone who looks it up.
			vendor := strings.TrimPrefix(readSysFile(filepath.Join(device, "vendor")), "0x")
			id := strings.TrimPrefix(readSysFile(filepath.Join(device, "device")), "0x")
			if vendor == "" && id == "" {
				continue
			}
			name = "PCI " + vendor + ":" + id
		}

		gpus = append(gpus, protocol.GpuInfo{
			Name:      name,
			Driver:    driverVersion(driver),
			VRAMBytes: gpuVRAM(device),
		})
	}
	return gpus
}

// pciDeviceNames maps PCI slots to product names using lspci's database.
//
// `lspci -D -mm` needs no root: it reads the same sysfs this code does and
// looks the IDs up in pci.ids. Where pciutils is not installed the map is
// empty and callers fall back to the raw IDs.
func pciDeviceNames() map[string]string {
	out, err := exec.Command("lspci", "-D", "-mm").Output()
	if err != nil {
		return nil
	}
	names := map[string]string{}
	for _, line := range strings.Split(string(out), "\n") {
		slot, rest, ok := strings.Cut(line, " ")
		if !ok {
			continue
		}
		// The remainder is quoted fields: class, vendor, device, then the
		// subsystem pair. Vendor and device together are the readable name.
		fields := splitQuoted(rest)
		if len(fields) < 3 {
			continue
		}
		names[slot] = strings.TrimSpace(fields[1] + " " + fields[2])
	}
	return names
}

// splitQuoted pulls the double-quoted fields out of one lspci -mm line. The
// names inside routinely contain spaces, brackets and commas, so nothing
// simpler than tracking the quotes works.
func splitQuoted(s string) []string {
	var fields []string
	for {
		start := strings.IndexByte(s, '"')
		if start < 0 {
			return fields
		}
		s = s[start+1:]
		end := strings.IndexByte(s, '"')
		if end < 0 {
			return fields
		}
		fields = append(fields, s[:end])
		s = s[end+1:]
	}
}

// driverVersion reports the loaded module's version where it publishes one.
//
// Out-of-tree modules -- nvidia above all -- carry their own version, which is
// the number a user is asked for when something goes wrong. In-tree drivers
// such as i915 and amdgpu ship with the kernel and have none, so they are
// reported as the driver name against the kernel release.
func driverVersion(driver string) string {
	if driver == "" {
		return ""
	}
	if version := readSysFile(filepath.Join("/sys/module", driver, "version")); version != "" {
		return driver + " " + version
	}
	if release := kernelRelease(); release != "" {
		return driver + " (kernel " + release + ")"
	}
	return driver
}

// gpuVRAM reports dedicated video memory where the driver exposes it. amdgpu
// publishes it in sysfs; nvidia reports it only through nvidia-smi, which the
// telemetry sampler already has cached. Integrated graphics share system
// memory and honestly have none of their own.
func gpuVRAM(device string) uint64 {
	if total, ok := readSysUint(filepath.Join(device, "mem_info_vram_total")); ok {
		return total
	}
	if link, err := os.Readlink(filepath.Join(device, "driver")); err == nil && filepath.Base(link) == "nvidia" {
		if sample, ok := sampleNvidiaGPU(); ok {
			return sample.memTotal
		}
	}
	return 0
}

// ---- battery ----

// linuxBatteryInfo reports the battery's charge and health from
// /sys/class/power_supply.
//
// A desktop simply has no BAT entry, which is reported as absent rather than
// as an error -- that is what the phone renders as "running on wall power".
// The inverse was the visible bug: a laptop reported no battery at all,
// because the whole about-system call was a stub, and the phone confidently
// described a machine sitting on its own battery as a desktop workstation.
func linuxBatteryInfo() protocol.BatteryInfo {
	entries, err := os.ReadDir("/sys/class/power_supply")
	if err != nil {
		return protocol.BatteryInfo{}
	}

	var batteryDir string
	acOnline := false
	for _, entry := range entries {
		dir := filepath.Join("/sys/class/power_supply", entry.Name())
		switch readSysFile(filepath.Join(dir, "type")) {
		case "Battery":
			// A wireless mouse or keyboard also registers as a Battery here.
			// The system battery is the one with a design capacity, which a
			// peripheral reporting only a coarse level does not have.
			if batteryDir == "" && hasDesignCapacity(dir) {
				batteryDir = dir
			}
		case "Mains", "USB":
			if online, ok := readSysUint(filepath.Join(dir, "online")); ok && online == 1 {
				acOnline = true
			}
		}
	}
	if batteryDir == "" {
		return protocol.BatteryInfo{}
	}

	info := protocol.BatteryInfo{Present: true, Charging: acOnline}
	if status := readSysFile(filepath.Join(batteryDir, "status")); status == "Charging" {
		info.Charging = true
	}
	if percent, ok := readSysUint(filepath.Join(batteryDir, "capacity")); ok {
		info.Percent = clamp(int(percent), 0, 100)
	}
	if cycles, ok := readSysUint(filepath.Join(batteryDir, "cycle_count")); ok {
		info.CycleCount = int(cycles)
	}

	info.DesignCapacityMwh = batteryCapacityMwh(batteryDir, "full_design")
	info.FullCapacityMwh = batteryCapacityMwh(batteryDir, "full")
	if info.DesignCapacityMwh > 0 && info.FullCapacityMwh > 0 && info.DesignCapacityMwh > info.FullCapacityMwh {
		lost := float64(info.DesignCapacityMwh - info.FullCapacityMwh)
		info.WearPercent = lost / float64(info.DesignCapacityMwh) * 100.0
	}
	return info
}

func hasDesignCapacity(dir string) bool {
	for _, name := range []string{"energy_full_design", "charge_full_design"} {
		if v, ok := readSysUint(filepath.Join(dir, name)); ok && v > 0 {
			return true
		}
	}
	return false
}

// batteryCapacityMwh reads a capacity in milliwatt-hours.
//
// Drivers report in one of two units depending on what the firmware exposes:
// energy_* in microwatt-hours, which converts directly, or charge_* in
// microamp-hours, which is a charge and has to be multiplied by the pack
// voltage to become an energy. Reporting the charge figure as though it were
// energy -- the obvious mistake -- overstates a typical 11.6 V laptop pack by
// an order of magnitude and makes the wear calculation meaningless.
// which is the sysfs spelling of the reading wanted: "full_design" for the
// factory capacity, "full" for what the pack holds today.
func batteryCapacityMwh(dir, which string) uint64 {
	if microWh, ok := readSysUint(filepath.Join(dir, "energy_"+which)); ok && microWh > 0 {
		return microWh / 1000
	}
	microAh, ok := readSysUint(filepath.Join(dir, "charge_"+which))
	if !ok || microAh == 0 {
		return 0
	}
	// voltage_min_design is the pack's nominal voltage and is stable;
	// voltage_now swings with load and state of charge, which would make the
	// design capacity appear to change as the battery drains.
	microV, ok := readSysUint(filepath.Join(dir, "voltage_min_design"))
	if !ok || microV == 0 {
		if microV, ok = readSysUint(filepath.Join(dir, "voltage_now")); !ok || microV == 0 {
			return 0
		}
	}
	// uAh * uV = 1e-12 Wh; scale to mWh in one step, dividing before the
	// multiply so the product cannot overflow on a large pack.
	return microAh / 1000 * (microV / 1000) / 1000
}
