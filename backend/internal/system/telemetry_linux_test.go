//go:build linux

package system

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

// TestSampleLinuxCPUNeedsTwoReads pins the shape of a delta counter: /proc/stat
// is cumulative since boot, so the first read can only establish a baseline.
// Reporting a number from it would show every freshly started daemon as having
// the machine's whole lifetime average pinned to the gauge.
func TestSampleLinuxCPUNeedsTwoReads(t *testing.T) {
	linuxTelemetryMu.Lock()
	haveCPUPrev = false
	linuxTelemetryMu.Unlock()

	if first := sampleLinuxCPU(); first != 0 {
		t.Errorf("first sample = %v, want 0 while establishing a baseline", first)
	}

	// Give the kernel something to count. A busy loop is the only way to be
	// sure the counters actually move within the test's lifetime.
	deadline := time.Now().Add(60 * time.Millisecond)
	for time.Now().Before(deadline) {
	}

	second := sampleLinuxCPU()
	if second < 0 || second > 100 {
		t.Errorf("second sample = %v, outside 0-100", second)
	}
}

// TestSampleLinuxMemoryUsesAvailable is the bug worth a test of its own.
// Linux spends every idle page on cache, so MemFree on a healthy desktop sits
// near zero; computing used as total-free would report a machine with 20 GB
// spare as being at 95%.
func TestSampleLinuxMemoryUsesAvailable(t *testing.T) {
	used, total := sampleLinuxMemory()
	if total == 0 {
		t.Fatal("no total memory reported")
	}
	if used == 0 {
		t.Error("no used memory reported")
	}
	if used > total {
		t.Errorf("used %d exceeds total %d", used, total)
	}

	free := readMeminfoField(t, "MemFree")
	available := readMeminfoField(t, "MemAvailable")
	if available <= free {
		t.Skip("host reports no reclaimable cache; the two formulas agree here")
	}
	if naive := total - free; used >= naive {
		t.Errorf("used = %d, which is the MemFree formula (%d): cache is being counted as in use", used, naive)
	}
}

func readMeminfoField(t *testing.T, key string) uint64 {
	t.Helper()
	b, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range strings.Split(string(b), "\n") {
		name, value, ok := strings.Cut(line, ":")
		if !ok || name != key {
			continue
		}
		fields := strings.Fields(value)
		if len(fields) == 0 {
			continue
		}
		if kb, err := strconv.ParseUint(fields[0], 10, 64); err == nil {
			return kb * 1024
		}
	}
	return 0
}

// TestReadProcStatHandlesSpacedComm covers the parse that silently corrupts
// every later field when done naively. A process named "(Web Content)" --
// Firefox spawns several -- has a space inside the parenthesised comm, so
// splitting the line on whitespace shifts utime and stime by one and reports
// CPU for exactly the processes most worth watching.
func TestReadProcStatHandlesSpacedComm(t *testing.T) {
	cases := []struct {
		name     string
		raw      string
		wantName string
		wantCPU  uint64
	}{
		{
			name:     "ordinary name",
			raw:      "1234 (bash) S 1 1234 1234 0 -1 4194304 100 200 0 0 11 22 0 0 20 0 1 0 999",
			wantName: "bash",
			wantCPU:  33,
		},
		{
			name:     "space inside comm",
			raw:      "1234 (Web Content) S 1 1234 1234 0 -1 4194304 100 200 0 0 11 22 0 0 20 0 1 0 999",
			wantName: "Web Content",
			wantCPU:  33,
		},
		{
			name:     "closing paren inside comm",
			raw:      "1234 (a) b) S 1 1234 1234 0 -1 4194304 100 200 0 0 11 22 0 0 20 0 1 0 999",
			wantName: "a) b",
			wantCPU:  33,
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			dir := t.TempDir()
			if err := os.WriteFile(filepath.Join(dir, "stat"), []byte(tc.raw), 0o600); err != nil {
				t.Fatal(err)
			}
			name, cpu, ok := readProcStat(dir)
			if !ok {
				t.Fatal("readProcStat rejected a well-formed line")
			}
			if name != tc.wantName {
				t.Errorf("name = %q, want %q", name, tc.wantName)
			}
			if cpu != tc.wantCPU {
				t.Errorf("cpu ticks = %d, want %d", cpu, tc.wantCPU)
			}
		})
	}

	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "stat"), []byte("garbage with no parens"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, _, ok := readProcStat(dir); ok {
		t.Error("readProcStat accepted a line with no comm field")
	}
}

// TestUnescapeMount covers the kernel's octal escaping in /proc/mounts. A
// drive mounted at "/mnt/My Disk" is written "/mnt/My\040Disk", and statfs on
// the literal string fails, dropping the drive off the list entirely.
func TestUnescapeMount(t *testing.T) {
	cases := []struct{ in, want string }{
		{"/", "/"},
		{"/home", "/home"},
		{`/mnt/My\040Disk`, "/mnt/My Disk"},
		{`/mnt/tab\011here`, "/mnt/tab\there"},
		{`/mnt/back\134slash`, `/mnt/back\slash`},
		{`/mnt/trailing\`, `/mnt/trailing\`},
		{`/mnt/bad\99x`, `/mnt/bad\99x`},
	}
	for _, tc := range cases {
		if got := unescapeMount(tc.in); got != tc.want {
			t.Errorf("unescapeMount(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

// TestSampleLinuxDrivesExcludesPseudoFilesystems guards the allow list. A
// desktop's /proc/mounts carries forty-odd entries, nearly all of them kernel
// bookkeeping, and listing them would bury the real disks under a page of
// tmpfs and squashfs rows.
func TestSampleLinuxDrivesExcludesPseudoFilesystems(t *testing.T) {
	drives := sampleLinuxDrives()
	if len(drives) == 0 {
		t.Skip("no real filesystems mounted")
	}
	for _, d := range drives {
		if d.TotalBytes == 0 {
			t.Errorf("drive %q has zero capacity", d.Device)
		}
		if d.FreeBytes > d.TotalBytes {
			t.Errorf("drive %q reports more free (%d) than total (%d)", d.Device, d.FreeBytes, d.TotalBytes)
		}
		if d.Label == "" {
			t.Errorf("drive %q has no label", d.Device)
		}
		t.Logf("drive %s (%s): %d/%d bytes free", d.Device, d.Label, d.FreeBytes, d.TotalBytes)
	}
}

// TestSampleLinuxNetworkSkipsVirtualInterfaces pins the rule that keeps a host
// running containers or VMs from reporting several times its real throughput:
// a packet leaving the box is seen on the veth, again on the bridge, and again
// on the physical NIC.
func TestSampleLinuxNetworkSkipsVirtualInterfaces(t *testing.T) {
	linuxTelemetryMu.Lock()
	prevNetAt = time.Time{}
	linuxTelemetryMu.Unlock()

	_, _, totalRx, totalTx := sampleLinuxNetwork()

	// Sum every non-loopback interface the naive way, and check the backend
	// did not do that on a host that has virtual interfaces to double count.
	naiveRx, naiveTx, virtual := naiveNetworkTotals(t)
	if !virtual {
		t.Skip("host has no virtual interfaces to double count")
	}
	if totalRx > naiveRx || totalTx > naiveTx {
		t.Fatalf("physical totals (%d/%d) exceed the sum of all interfaces (%d/%d)", totalRx, totalTx, naiveRx, naiveTx)
	}
	t.Logf("physical rx/tx %d/%d vs all-interface %d/%d", totalRx, totalTx, naiveRx, naiveTx)
}

func naiveNetworkTotals(t *testing.T) (rx, tx uint64, sawVirtual bool) {
	t.Helper()
	b, err := os.ReadFile("/proc/net/dev")
	if err != nil {
		t.Fatal(err)
	}
	for _, line := range strings.Split(string(b), "\n") {
		name, rest, ok := strings.Cut(line, ":")
		if !ok {
			continue
		}
		name = strings.TrimSpace(name)
		if name == "" || name == "lo" {
			continue
		}
		if _, err := os.Stat(filepath.Join("/sys/class/net", name, "device")); err != nil {
			sawVirtual = true
		}
		fields := strings.Fields(rest)
		if len(fields) < 9 {
			continue
		}
		r, _ := strconv.ParseUint(fields[0], 10, 64)
		w, _ := strconv.ParseUint(fields[8], 10, 64)
		rx += r
		tx += w
	}
	return rx, tx, sawVirtual
}

// TestCPUThermalIsPlausible checks the sensor pick rather than any particular
// value: a hwmon input that has never been read, or one being unplugged,
// returns a number no silicon survives, and a dashboard showing 0 C or 200 C
// is worse than one showing nothing.
func TestCPUThermalIsPlausible(t *testing.T) {
	temp := sampleLinuxThermal()
	if temp == nil {
		t.Skip("host exposes no CPU thermal sensor")
	}
	if *temp < 1 || *temp > 150 {
		t.Errorf("CPU temperature %.1f C is not plausible", *temp)
	}
	t.Logf("CPU temperature: %.1f C (from %s)", *temp, cpuThermalPath())
}
