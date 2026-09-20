//go:build linux

package system

import (
	"bufio"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"switchboard/backend/internal/protocol"
)

// Linux resource telemetry, the counterpart to telemetry_windows.go.
//
// Where the Windows backend goes through kernel32, iphlpapi and WMI, this one
// reads procfs and sysfs. That is the whole difference in character: the
// numbers are already computed by the kernel and exposed as text, so almost
// everything here is a file read and a parse rather than a call that can fail
// in interesting ways.
//
// The sampling cadence is the same, and for the same reason. Live telemetry
// ticks every 2s, so anything read here is paid for 30 times a minute on the
// user's own machine. The cheap counters -- CPU, memory, network, disk -- are
// four small file reads and are taken every tick. The rest are not, and keep
// their own TTLs:
//
//   - nvidia-smi is a process spawn (~100ms of host CPU) and is dropped
//     entirely once it is clear the machine has no NVIDIA GPU. The AMD path
//     costs nothing, being two sysfs reads, but shares the cache for symmetry.
//   - the process table opens three files per process across ~400 of them.
//   - drive capacity does not move in 2s, and statfs on a stale network mount
//     can block for as long as the kernel's timeout.
//
// Thermals are the one place cheaper than Windows: a hwmon read is a file
// read, not a WMI round trip, so there is no miss counter here. The sensor is
// located once and then read directly.
const (
	linuxGPUTTL    = 10 * time.Second
	linuxProcsTTL  = 6 * time.Second
	linuxDrivesTTL = 60 * time.Second
	// nvidiaTimeout bounds the spawn. A wedged driver must not stall the
	// telemetry loop, and with it the live push to the phone.
	nvidiaTimeout = 2 * time.Second
)

// diskstats and the block layer always report in 512-byte sectors regardless
// of the device's real sector size. This is a kernel ABI constant, not a
// property of the disk, and reading the disk's own logical_block_size here
// would produce numbers several times too large on a 4Kn drive.
const diskSectorSize = 512

var (
	linuxTelemetryMu sync.Mutex

	prevCPUIdle  uint64
	prevCPUTotal uint64
	haveCPUPrev  bool

	prevNetAt time.Time
	prevNetRx uint64
	prevNetTx uint64

	prevDiskAt    time.Time
	prevDiskRead  uint64
	prevDiskWrite uint64

	linuxCachedGPU    linuxGPUSample
	linuxCachedGPUAt  time.Time
	linuxGPUAbsent    bool
	linuxCachedProcs  []protocol.ProcessItem
	linuxCachedData   []protocol.DataUsageItem
	linuxCachedProcAt time.Time
	// prevProcCPU carries each process's cumulative CPU jiffies between
	// samples, which is the only way to report a rate rather than a lifetime
	// total: /proc/<pid>/stat counts since the process started, so a long-lived
	// idle daemon would otherwise outrank a compiler at full tilt.
	prevProcCPU       map[int]uint64
	prevProcCPUAt     time.Time
	linuxCachedDrv    []protocol.DriveItem
	linuxCachedDrv2At time.Time

	// thermalPathOnce resolves the CPU temperature sensor once per daemon. The
	// hwmon numbering is not stable across boots but does not move within one.
	thermalPathOnce sync.Once
	thermalPath     string
)

type linuxGPUSample struct {
	util     float64
	temp     *float64
	memUsed  uint64
	memTotal uint64
	ok       bool
}

// ---- small file helpers ----

// readSysFile reads a one-line sysfs or procfs value. Every one of these can
// vanish between being listed and being read -- a process exits, a device is
// unplugged -- so the error is never interesting, only the absence.
func readSysFile(path string) string {
	b, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return strings.TrimSpace(string(b))
}

func readSysUint(path string) (uint64, bool) {
	s := readSysFile(path)
	if s == "" {
		return 0, false
	}
	v, err := strconv.ParseUint(s, 10, 64)
	return v, err == nil
}

// ---- CPU ----

// sampleLinuxCPU reads aggregate CPU utilisation from /proc/stat.
//
// The counters are cumulative jiffies since boot, so a single read says
// nothing; utilisation is the ratio of idle to total time between two reads.
// iowait counts as idle, matching what every Linux system monitor shows and
// what the Windows backend reports: a machine blocked on a disk is not busy.
func sampleLinuxCPU() float64 {
	f, err := os.Open("/proc/stat")
	if err != nil {
		return 0
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	if !scanner.Scan() {
		return 0
	}
	fields := strings.Fields(scanner.Text())
	if len(fields) < 5 || fields[0] != "cpu" {
		return 0
	}

	var total, idle uint64
	for i, raw := range fields[1:] {
		v, err := strconv.ParseUint(raw, 10, 64)
		if err != nil {
			continue
		}
		// The first eight fields are user, nice, system, idle, iowait, irq,
		// softirq and steal. Anything past those is the guest accounting,
		// which the kernel already counts inside user and nice; adding it
		// would double count on a host running VMs.
		if i >= 8 {
			break
		}
		total += v
		if i == 3 || i == 4 { // idle, iowait
			idle += v
		}
	}
	if total == 0 {
		return 0
	}

	prevIdle, prevTotal, had := prevCPUIdle, prevCPUTotal, haveCPUPrev
	prevCPUIdle, prevCPUTotal, haveCPUPrev = idle, total, true
	if !had || total <= prevTotal {
		return 0
	}

	deltaTotal := total - prevTotal
	deltaIdle := idle - prevIdle
	if deltaIdle > deltaTotal {
		return 0
	}
	pct := 100.0 * (1.0 - float64(deltaIdle)/float64(deltaTotal))
	if pct < 0 {
		pct = 0
	}
	if pct > 100 {
		pct = 100
	}
	return pct
}

// ---- memory ----

// sampleLinuxMemory reports used and total physical memory.
//
// Used is total minus MemAvailable, not total minus MemFree. Linux spends
// every otherwise idle page on cache, so MemFree on a healthy machine is close
// to zero and reporting against it would show every Linux host as permanently
// out of memory. MemAvailable is the kernel's own estimate of what a new
// allocation could actually get, which is the number a user means.
func sampleLinuxMemory() (used, total uint64) {
	f, err := os.Open("/proc/meminfo")
	if err != nil {
		return 0, 0
	}
	defer f.Close()

	var available, free, buffers, cached uint64
	haveAvailable := false

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		key, value, ok := strings.Cut(scanner.Text(), ":")
		if !ok {
			continue
		}
		fields := strings.Fields(value)
		if len(fields) == 0 {
			continue
		}
		kb, err := strconv.ParseUint(fields[0], 10, 64)
		if err != nil {
			continue
		}
		switch key {
		case "MemTotal":
			total = kb * 1024
		case "MemAvailable":
			available, haveAvailable = kb*1024, true
		case "MemFree":
			free = kb * 1024
		case "Buffers":
			buffers = kb * 1024
		case "Cached":
			cached = kb * 1024
		}
	}
	if total == 0 {
		return 0, 0
	}
	// MemAvailable has been in the kernel since 3.14; the reconstruction is
	// for the handful of container runtimes that synthesise a partial
	// meminfo, where its absence would otherwise report the host as full.
	if !haveAvailable {
		available = free + buffers + cached
	}
	if available > total {
		available = total
	}
	return total - available, total
}

// ---- network ----

// sampleLinuxNetwork rates the interface counters over the time actually
// elapsed since the previous read, so a delayed loop reports the real average
// rather than a spike.
//
// Only interfaces backed by real hardware are counted, which is what the
// `device` symlink in sysfs marks. The alternative -- summing everything that
// is not loopback -- double counts badly on any machine running containers or
// VMs, where a packet leaving the box is seen once on the veth or tap, again
// on the bridge, and again on the physical NIC. A host with no hardware
// interface at all falls back to counting everything but loopback, so a setup
// that is entirely virtual still reports something.
func sampleLinuxNetwork() (rxBps, txBps, totalRx, totalTx uint64) {
	f, err := os.Open("/proc/net/dev")
	if err != nil {
		return 0, 0, 0, 0
	}
	defer f.Close()

	var physRx, physTx, anyRx, anyTx uint64
	havePhysical := false

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		name, rest, ok := strings.Cut(scanner.Text(), ":")
		if !ok {
			continue // the two header lines
		}
		name = strings.TrimSpace(name)
		if name == "" || name == "lo" {
			continue
		}
		fields := strings.Fields(rest)
		if len(fields) < 9 {
			continue
		}
		rx, err1 := strconv.ParseUint(fields[0], 10, 64)
		tx, err2 := strconv.ParseUint(fields[8], 10, 64)
		if err1 != nil || err2 != nil {
			continue
		}
		anyRx += rx
		anyTx += tx
		if _, err := os.Stat(filepath.Join("/sys/class/net", name, "device")); err == nil {
			havePhysical = true
			physRx += rx
			physTx += tx
		}
	}

	totalRx, totalTx = physRx, physTx
	if !havePhysical {
		totalRx, totalTx = anyRx, anyTx
	}

	now := time.Now()
	first := prevNetAt.IsZero()
	elapsed := now.Sub(prevNetAt).Seconds()
	prevNetAt = now
	if first {
		prevNetRx, prevNetTx = totalRx, totalTx
		return 0, 0, totalRx, totalTx
	}

	// Counters go backwards when an interface is removed, or when a 32-bit
	// counter on an old driver wraps. Either way the delta is meaningless and
	// reporting zero beats reporting a terabyte per second.
	var deltaRx, deltaTx uint64
	if totalRx >= prevNetRx {
		deltaRx = totalRx - prevNetRx
	}
	if totalTx >= prevNetTx {
		deltaTx = totalTx - prevNetTx
	}
	prevNetRx, prevNetTx = totalRx, totalTx

	if elapsed > 0 {
		rxBps = uint64(float64(deltaRx) / elapsed)
		txBps = uint64(float64(deltaTx) / elapsed)
	}
	return rxBps, txBps, totalRx, totalTx
}

// ---- disk ----

// sampleLinuxDisk rates block I/O from /proc/diskstats.
//
// Only whole disks are counted, identified by having an entry under
// /sys/block. Partitions are listed alongside their parent and every byte
// would be counted twice; device-mapper and loop devices sit on top of a real
// disk and would be counted twice for the same reason.
func sampleLinuxDisk() (readBps, writeBps uint64) {
	f, err := os.Open("/proc/diskstats")
	if err != nil {
		return 0, 0
	}
	defer f.Close()

	var sectorsRead, sectorsWritten uint64
	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 10 {
			continue
		}
		name := fields[2]
		if strings.HasPrefix(name, "loop") || strings.HasPrefix(name, "dm-") ||
			strings.HasPrefix(name, "ram") || strings.HasPrefix(name, "zram") {
			continue
		}
		if _, err := os.Stat(filepath.Join("/sys/block", name)); err != nil {
			continue // a partition, listed under its parent disk
		}
		r, err1 := strconv.ParseUint(fields[5], 10, 64)
		w, err2 := strconv.ParseUint(fields[9], 10, 64)
		if err1 != nil || err2 != nil {
			continue
		}
		sectorsRead += r
		sectorsWritten += w
	}

	totalRead := sectorsRead * diskSectorSize
	totalWrite := sectorsWritten * diskSectorSize

	now := time.Now()
	first := prevDiskAt.IsZero()
	elapsed := now.Sub(prevDiskAt).Seconds()
	prevDiskAt = now
	if first {
		prevDiskRead, prevDiskWrite = totalRead, totalWrite
		return 0, 0
	}

	var deltaRead, deltaWrite uint64
	if totalRead >= prevDiskRead {
		deltaRead = totalRead - prevDiskRead
	}
	if totalWrite >= prevDiskWrite {
		deltaWrite = totalWrite - prevDiskWrite
	}
	prevDiskRead, prevDiskWrite = totalRead, totalWrite

	if elapsed > 0 {
		readBps = uint64(float64(deltaRead) / elapsed)
		writeBps = uint64(float64(deltaWrite) / elapsed)
	}
	return readBps, writeBps
}

// ---- drives ----

// realFilesystems are the on-disk filesystems worth showing as a drive.
//
// An allow list rather than a deny list: /proc/mounts on a desktop carries
// upwards of forty entries, nearly all of them kernel bookkeeping (proc,
// sysfs, cgroup2, tmpfs, overlay, the per-snap squashfs mounts), and a deny
// list would need extending every time a distribution invented another one.
var realFilesystems = map[string]bool{
	"ext2": true, "ext3": true, "ext4": true,
	"btrfs": true, "xfs": true, "f2fs": true, "jfs": true, "reiserfs": true,
	"zfs": true, "bcachefs": true,
	"vfat": true, "exfat": true, "msdos": true,
	"ntfs": true, "ntfs3": true, "fuseblk": true,
}

// sampleLinuxDrives lists mounted volumes with their capacity.
func sampleLinuxDrives() []protocol.DriveItem {
	f, err := os.Open("/proc/mounts")
	if err != nil {
		return nil
	}
	defer f.Close()

	labels := diskLabels()
	seen := map[string]bool{}
	var drives []protocol.DriveItem

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		fields := strings.Fields(scanner.Text())
		if len(fields) < 3 {
			continue
		}
		device, mount, fstype := fields[0], unescapeMount(fields[1]), fields[2]
		if !realFilesystems[fstype] || !strings.HasPrefix(device, "/dev/") {
			continue
		}
		// bind mounts and btrfs subvolumes put the same device on several
		// mount points; the first is the one a user thinks of as the drive.
		if seen[device] {
			continue
		}
		seen[device] = true

		var st syscall.Statfs_t
		if err := syscall.Statfs(mount, &st); err != nil {
			continue
		}
		blockSize := uint64(st.Bsize)
		total := st.Blocks * blockSize
		if total == 0 {
			continue
		}
		// Bavail, not Bfree: the difference is the reserve only root may use,
		// and counting it as free would promise space an ordinary write cannot
		// have. On a default ext4 that reserve is 5% of the disk.
		free := st.Bavail * blockSize

		label := labels[device]
		if label == "" {
			label = filepath.Base(device)
		}
		drives = append(drives, protocol.DriveItem{
			Device:     mount,
			Label:      label,
			TotalBytes: total,
			FreeBytes:  free,
		})
	}

	sort.Slice(drives, func(i, j int) bool { return drives[i].Device < drives[j].Device })
	return drives
}

// diskLabels maps device nodes to their filesystem labels, so a drive shows as
// "Data" rather than "sdb1". udev maintains the symlinks; a system without it
// simply yields no labels and the device name is used instead.
func diskLabels() map[string]string {
	entries, err := os.ReadDir("/dev/disk/by-label")
	if err != nil {
		return nil
	}
	labels := make(map[string]string, len(entries))
	for _, entry := range entries {
		target, err := filepath.EvalSymlinks(filepath.Join("/dev/disk/by-label", entry.Name()))
		if err != nil {
			continue
		}
		// udev escapes characters that are awkward in a filename; \x20 for a
		// space is by far the most common and the only one worth undoing.
		labels[target] = strings.ReplaceAll(entry.Name(), `\x20`, " ")
	}
	return labels
}

// unescapeMount undoes the octal escaping the kernel applies to mount points
// in /proc/mounts, where a space is written \040. Without it a drive mounted
// at "/mnt/My Disk" is stat'ed under a path that does not exist.
func unescapeMount(s string) string {
	if !strings.Contains(s, `\`) {
		return s
	}
	var b strings.Builder
	for i := 0; i < len(s); i++ {
		if s[i] == '\\' && i+3 < len(s) {
			if v, err := strconv.ParseUint(s[i+1:i+4], 8, 8); err == nil {
				b.WriteByte(byte(v))
				i += 3
				continue
			}
		}
		b.WriteByte(s[i])
	}
	return b.String()
}

// ---- processes ----

// sampleLinuxProcesses walks /proc for the process and data-usage tables.
//
// CPU is a rate computed against the previous walk rather than the lifetime
// total in /proc/<pid>/stat, because a total makes every long-running daemon
// look busier than whatever is actually consuming the machine right now.
func sampleLinuxProcesses() ([]protocol.ProcessItem, []protocol.DataUsageItem) {
	entries, err := os.ReadDir("/proc")
	if err != nil {
		return nil, nil
	}

	now := time.Now()
	elapsed := now.Sub(prevProcCPUAt).Seconds()
	havePrev := !prevProcCPUAt.IsZero() && elapsed > 0
	current := make(map[int]uint64, len(entries))
	pageSize := uint64(os.Getpagesize())
	ticks := float64(clockTicksPerSecond)

	var procs []protocol.ProcessItem
	var dataItems []protocol.DataUsageItem

	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}
		pid, err := strconv.Atoi(entry.Name())
		if err != nil {
			continue
		}
		dir := filepath.Join("/proc", entry.Name())

		name, cpuTicks, ok := readProcStat(dir)
		if !ok {
			continue // exited between the listing and the read
		}
		current[pid] = cpuTicks

		var cpuPct float64
		if havePrev {
			if prev, seen := prevProcCPU[pid]; seen && cpuTicks >= prev {
				cpuPct = float64(cpuTicks-prev) / ticks / elapsed * 100
			}
		}

		// statm's second field is resident set size in pages, which is the
		// closest analogue to the working set the Windows backend reports.
		var ramBytes uint64
		if fields := strings.Fields(readSysFile(filepath.Join(dir, "statm"))); len(fields) >= 2 {
			if pages, err := strconv.ParseUint(fields[1], 10, 64); err == nil {
				ramBytes = pages * pageSize
			}
		}
		if ramBytes > 0 {
			procs = append(procs, protocol.ProcessItem{
				Name:     name,
				PID:      pid,
				CPU:      cpuPct,
				RAMBytes: ramBytes,
			})
		}

		// /proc/<pid>/io is readable only by the process owner, so this table
		// covers the user's own programs and silently omits system daemons.
		// That is the right half to show anyway: it is the user's browser and
		// editor they want accounted for, and the daemon deliberately does not
		// run as root to see the rest.
		rx, tx := readProcIO(dir)
		if total := rx + tx; total > 0 {
			dataItems = append(dataItems, protocol.DataUsageItem{
				Name:       name,
				PID:        pid,
				RxBytes:    rx,
				TxBytes:    tx,
				TotalBytes: total,
			})
		}
	}

	prevProcCPU = current
	prevProcCPUAt = now

	sort.Slice(procs, func(i, j int) bool { return procs[i].RAMBytes > procs[j].RAMBytes })
	if len(procs) > 5 {
		procs = procs[:5]
	}
	sort.Slice(dataItems, func(i, j int) bool { return dataItems[i].TotalBytes > dataItems[j].TotalBytes })
	if len(dataItems) > 8 {
		dataItems = dataItems[:8]
	}
	return procs, dataItems
}

// clockTicksPerSecond is the unit /proc/<pid>/stat counts CPU time in. It is
// USER_HZ, which sysconf(_SC_CLK_TCK) reports and which has been 100 on every
// Linux port since the 2.6 era; reading it properly needs cgo, and the daemon
// is built without it.
const clockTicksPerSecond = 100

// readProcStat pulls the command name and cumulative CPU ticks out of
// /proc/<pid>/stat.
//
// The comm field is parsed from the last ')' rather than by splitting on
// spaces, because it is the process's own executable name in parentheses and
// may contain both -- "(Web Content)" and "(a) b)" are both legal. Splitting
// naively shifts every later field and silently misreports CPU for exactly the
// processes most worth seeing.
func readProcStat(dir string) (name string, cpuTicks uint64, ok bool) {
	raw := readSysFile(filepath.Join(dir, "stat"))
	open := strings.IndexByte(raw, '(')
	close := strings.LastIndexByte(raw, ')')
	if open < 0 || close < open {
		return "", 0, false
	}
	name = raw[open+1 : close]

	fields := strings.Fields(raw[close+1:])
	// After comm the fields are state, ppid, pgrp, session, tty_nr, tpgid,
	// flags, minflt, cminflt, majflt, cmajflt, utime, stime: utime is the
	// twelfth, counting state as the first.
	if len(fields) < 13 {
		return name, 0, true
	}
	utime, err1 := strconv.ParseUint(fields[11], 10, 64)
	stime, err2 := strconv.ParseUint(fields[12], 10, 64)
	if err1 != nil || err2 != nil {
		return name, 0, true
	}
	return name, utime + stime, true
}

// readProcIO returns bytes this process has read and written, matching what
// GetProcessIoCounters reports on Windows: all I/O, not only network.
func readProcIO(dir string) (read, written uint64) {
	f, err := os.Open(filepath.Join(dir, "io"))
	if err != nil {
		return 0, 0
	}
	defer f.Close()

	scanner := bufio.NewScanner(f)
	for scanner.Scan() {
		key, value, ok := strings.Cut(scanner.Text(), ":")
		if !ok {
			continue
		}
		v, err := strconv.ParseUint(strings.TrimSpace(value), 10, 64)
		if err != nil {
			continue
		}
		switch key {
		case "rchar":
			read = v
		case "wchar":
			written = v
		}
	}
	return read, written
}

// ---- thermals ----

// cpuThermalPath locates the CPU package temperature sensor.
//
// The driver names are checked in order of how directly they report the CPU:
// coretemp is Intel's per-package sensor, k10temp and zenpower are AMD's, and
// acpitz is the motherboard's own thermal zone, which is a fair bit cooler
// than the die but is all some machines expose. Within a chip sensor the
// package reading is preferred over a single core's, since one core spiking is
// not the temperature a user means.
func cpuThermalPath() string {
	thermalPathOnce.Do(func() {
		entries, err := os.ReadDir("/sys/class/hwmon")
		if err != nil {
			return
		}
		byDriver := map[string]string{}
		for _, entry := range entries {
			dir := filepath.Join("/sys/class/hwmon", entry.Name())
			byDriver[readSysFile(filepath.Join(dir, "name"))] = dir
		}
		for _, driver := range []string{"coretemp", "k10temp", "zenpower", "cpu_thermal", "acpitz"} {
			dir, ok := byDriver[driver]
			if !ok {
				continue
			}
			if path := packageTempInput(dir); path != "" {
				thermalPath = path
				return
			}
		}
	})
	return thermalPath
}

// packageTempInput picks the whole-chip input within one hwmon device.
func packageTempInput(dir string) string {
	inputs, err := filepath.Glob(filepath.Join(dir, "temp*_input"))
	if err != nil || len(inputs) == 0 {
		return ""
	}
	sort.Strings(inputs)
	for _, input := range inputs {
		label := readSysFile(strings.TrimSuffix(input, "_input") + "_label")
		// "Package id 0" on Intel, "Tctl" on AMD: the die reading the vendor
		// intends to be read.
		if strings.HasPrefix(label, "Package id") || label == "Tctl" || label == "Tdie" {
			return input
		}
	}
	// No labels at all, as on acpitz and the ARM cpu_thermal zone. The lowest
	// numbered input is the device's primary one by convention.
	return inputs[0]
}

// sampleLinuxThermal reads the CPU temperature in degrees Celsius. hwmon
// reports millidegrees.
func sampleLinuxThermal() *float64 {
	path := cpuThermalPath()
	if path == "" {
		return nil
	}
	milli, ok := readSysUint(path)
	if !ok || milli == 0 {
		return nil
	}
	c := float64(milli) / 1000.0
	// A sensor that has not been read since boot, or one being hot-unplugged,
	// occasionally returns a value no silicon survives. Showing 0°C or 200°C
	// on a dashboard is worse than showing nothing.
	if c < 1 || c > 150 {
		return nil
	}
	return &c
}

// ---- GPU ----

// sampleLinuxGPU reads GPU utilisation, preferring the vendor tool where one
// exists and falling back to the kernel's own counters.
func sampleLinuxGPU() linuxGPUSample {
	if s, ok := sampleNvidiaGPU(); ok {
		return s
	}
	return sampleAmdGPU()
}

// sampleNvidiaGPU shells out to nvidia-smi, exactly as the Windows backend
// does: the proprietary driver exposes nothing in sysfs, and NVML needs cgo.
func sampleNvidiaGPU() (linuxGPUSample, bool) {
	ctx, cancel := context.WithTimeout(context.Background(), nvidiaTimeout)
	defer cancel()

	out, err := exec.CommandContext(ctx, "nvidia-smi",
		"--query-gpu=utilization.gpu,temperature.gpu,memory.used,memory.total",
		"--format=csv,noheader,nounits").Output()
	if err != nil {
		return linuxGPUSample{}, false
	}
	// A machine with two cards prints a line each; the first is the one the
	// display is on often enough, and a dashboard with one GPU gauge has to
	// choose something.
	line, _, _ := strings.Cut(strings.TrimSpace(string(out)), "\n")
	parts := strings.Split(line, ",")
	if len(parts) < 4 {
		return linuxGPUSample{}, false
	}
	util, _ := strconv.ParseFloat(strings.TrimSpace(parts[0]), 64)
	temp, tempErr := strconv.ParseFloat(strings.TrimSpace(parts[1]), 64)
	usedMiB, _ := strconv.ParseUint(strings.TrimSpace(parts[2]), 10, 64)
	totalMiB, _ := strconv.ParseUint(strings.TrimSpace(parts[3]), 10, 64)

	sample := linuxGPUSample{
		util:     util,
		memUsed:  usedMiB * 1024 * 1024,
		memTotal: totalMiB * 1024 * 1024,
		ok:       true,
	}
	if tempErr == nil && temp > 0 {
		sample.temp = &temp
	}
	return sample, true
}

// sampleAmdGPU reads the amdgpu driver's counters from sysfs. They cost two
// file reads, so unlike the NVIDIA path there is nothing here worth avoiding.
//
// Intel has no equivalent: i915 exposes utilisation only through the perf
// subsystem, which needs a privileged open and a sampling thread, and the
// integrated GPU is not what anyone is watching this gauge for.
func sampleAmdGPU() linuxGPUSample {
	cards, err := filepath.Glob("/sys/class/drm/card[0-9]/device/gpu_busy_percent")
	if err != nil || len(cards) == 0 {
		return linuxGPUSample{}
	}
	sort.Strings(cards)
	dev := filepath.Dir(cards[0])

	busy, ok := readSysUint(cards[0])
	if !ok {
		return linuxGPUSample{}
	}
	sample := linuxGPUSample{util: float64(busy), ok: true}
	sample.memUsed, _ = readSysUint(filepath.Join(dev, "mem_info_vram_used"))
	sample.memTotal, _ = readSysUint(filepath.Join(dev, "mem_info_vram_total"))

	if hwmons, err := filepath.Glob(filepath.Join(dev, "hwmon", "hwmon*", "temp1_input")); err == nil && len(hwmons) > 0 {
		if milli, ok := readSysUint(hwmons[0]); ok && milli > 0 {
			c := float64(milli) / 1000.0
			if c > 1 && c < 150 {
				sample.temp = &c
			}
		}
	}
	return sample
}

// cachedLinuxGPU samples at most once per TTL, and stops for good once a host
// has shown it has no readable GPU: the NVIDIA probe is a process spawn and a
// machine without the card will not grow one.
func cachedLinuxGPU() linuxGPUSample {
	if linuxGPUAbsent || time.Since(linuxCachedGPUAt) < linuxGPUTTL {
		return linuxCachedGPU
	}
	linuxCachedGPUAt = time.Now()
	sample := sampleLinuxGPU()
	if !sample.ok {
		linuxGPUAbsent = true
		linuxCachedGPU = linuxGPUSample{}
		return linuxCachedGPU
	}
	linuxCachedGPU = sample
	return linuxCachedGPU
}

func cachedLinuxProcesses() ([]protocol.ProcessItem, []protocol.DataUsageItem) {
	if time.Since(linuxCachedProcAt) < linuxProcsTTL {
		return linuxCachedProcs, linuxCachedData
	}
	linuxCachedProcAt = time.Now()
	linuxCachedProcs, linuxCachedData = sampleLinuxProcesses()
	return linuxCachedProcs, linuxCachedData
}

func cachedLinuxDrives() []protocol.DriveItem {
	if time.Since(linuxCachedDrv2At) < linuxDrivesTTL {
		return linuxCachedDrv
	}
	linuxCachedDrv2At = time.Now()
	linuxCachedDrv = sampleLinuxDrives()
	return linuxCachedDrv
}

func sampleMetrics(full bool) (protocol.MetricPoint, []protocol.ProcessItem, []protocol.DriveItem, []protocol.DataUsageItem, error) {
	linuxTelemetryMu.Lock()
	defer linuxTelemetryMu.Unlock()

	cpu := sampleLinuxCPU()
	ramUsed, ramTotal := sampleLinuxMemory()
	rxBps, txBps, totalRx, totalTx := sampleLinuxNetwork()
	diskRead, diskWrite := sampleLinuxDisk()

	var (
		drives    []protocol.DriveItem
		procs     []protocol.ProcessItem
		dataUsage []protocol.DataUsageItem
		cpuTemp   *float64
		gpu       linuxGPUSample
	)
	if full {
		drives = cachedLinuxDrives()
		procs, dataUsage = cachedLinuxProcesses()
		cpuTemp = sampleLinuxThermal()
		gpu = cachedLinuxGPU()
	}

	return protocol.MetricPoint{
		Timestamp:   time.Now().Unix(),
		CPU:         cpu,
		RAMUsed:     ramUsed,
		RAMTotal:    ramTotal,
		GPU:         gpu.util,
		GPUMemUsed:  gpu.memUsed,
		GPUMemTotal: gpu.memTotal,
		DiskRead:    diskRead,
		DiskWrite:   diskWrite,
		NetRx:       rxBps,
		NetTx:       txBps,
		NetTotalRx:  totalRx,
		NetTotalTx:  totalTx,
		CPUTemp:     cpuTemp,
		GPUTemp:     gpu.temp,
	}, procs, drives, dataUsage, nil
}
