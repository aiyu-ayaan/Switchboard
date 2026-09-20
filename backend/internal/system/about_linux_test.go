//go:build linux

package system

import (
	"os"
	"path/filepath"
	"testing"
)

// TestLinuxOSInfoNamesTheDistribution guards the field the phone shows first.
// Reporting "Linux" would be true and useless on a screen whose whole job is
// telling one machine from another, and an empty name is what made the mobile
// About screen fall back to calling this host Windows.
func TestLinuxOSInfoNamesTheDistribution(t *testing.T) {
	info := linuxOSInfo()
	if info.Name == "" {
		t.Error("OS name is empty")
	}
	if info.Build == "" {
		t.Error("OS build is empty; the kernel release should fill it")
	}
	if info.UptimeSeconds == 0 {
		t.Error("uptime is zero on a running host")
	}
	t.Logf("OS: %s, build %s, up %ds", info.Name, info.Build, info.UptimeSeconds)
}

// TestLinuxCPUInfoSeparatesCoresFromThreads is the invariant the phone renders
// as "N Physical Cores (M Threads)". Counting "processor" lines for both --
// the obvious reading of /proc/cpuinfo -- reports an SMT machine as having
// twice the cores it has.
func TestLinuxCPUInfoSeparatesCoresFromThreads(t *testing.T) {
	info := linuxCPUInfo()
	if info.Model == "" || info.Model == "Unknown Processor" {
		t.Errorf("CPU model = %q", info.Model)
	}
	if info.Cores <= 0 {
		t.Errorf("cores = %d", info.Cores)
	}
	if info.Threads < info.Cores {
		t.Errorf("threads %d is below cores %d", info.Threads, info.Cores)
	}
	t.Logf("CPU: %s, %d cores / %d threads, base %d MHz", info.Model, info.Cores, info.Threads, info.BaseClockMhz)
}

// TestClockFromModelName covers the fallback for hosts whose cpufreq driver
// publishes no base_frequency, which is most AMD parts and every VM.
func TestClockFromModelName(t *testing.T) {
	cases := []struct {
		model string
		want  int
	}{
		{"Intel(R) Core(TM) i5-10200H CPU @ 2.40GHz", 2400},
		{"Intel(R) Xeon(R) CPU E5-2680 v4 @ 2.40GHz", 2400},
		{"Some CPU @ 800MHz", 800},
		{"AMD Ryzen 9 5900X 12-Core Processor", 0},
		{"Cortex-A72", 0},
		{"Broken @ GHz", 0},
	}
	for _, tc := range cases {
		if got := clockFromModelName(tc.model); got != tc.want {
			t.Errorf("clockFromModelName(%q) = %d, want %d", tc.model, got, tc.want)
		}
	}
}

// TestCPUBaseClockIsNominal pins the reason "cpu MHz" from /proc/cpuinfo is
// not used: it is the instantaneous frequency, so an idle laptop would report
// a different "base clock" on every refresh.
func TestCPUBaseClockIsNominal(t *testing.T) {
	info := linuxCPUInfo()
	if info.BaseClockMhz == 0 {
		t.Skip("host publishes no nominal clock")
	}
	second := linuxCPUInfo()
	if second.BaseClockMhz != info.BaseClockMhz {
		t.Errorf("base clock moved between reads: %d then %d -- a live frequency is being reported",
			info.BaseClockMhz, second.BaseClockMhz)
	}
}

// TestSplitQuoted covers the lspci -mm parse. Device names routinely carry
// spaces, brackets, slashes and commas -- "TU117M [GeForce GTX 1650 Mobile /
// Max-Q]" -- so nothing short of tracking the quotes survives them.
func TestSplitQuoted(t *testing.T) {
	line := `"VGA compatible controller" "NVIDIA Corporation" "TU117M [GeForce GTX 1650 Mobile / Max-Q]" -ra1 -p00 "ASUSTeK Computer Inc." "TU117M"`
	got := splitQuoted(line)
	want := []string{
		"VGA compatible controller",
		"NVIDIA Corporation",
		"TU117M [GeForce GTX 1650 Mobile / Max-Q]",
		"ASUSTeK Computer Inc.",
		"TU117M",
	}
	if len(got) != len(want) {
		t.Fatalf("got %d fields %q, want %d", len(got), got, len(want))
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("field %d = %q, want %q", i, got[i], want[i])
		}
	}

	if fields := splitQuoted(`no quotes here`); fields != nil {
		t.Errorf("unquoted line yielded %q", fields)
	}
	if fields := splitQuoted(`"unterminated`); fields != nil {
		t.Errorf("unterminated quote yielded %q", fields)
	}
}

// TestLinuxGPUInfoNamesAdapters checks that a host with a graphics card
// reports something a human can read, and that a hybrid laptop reports both
// GPUs rather than deduplicating them into one.
func TestLinuxGPUInfoNamesAdapters(t *testing.T) {
	gpus := linuxGPUInfo()
	if len(gpus) == 0 {
		t.Skip("host has no DRM device")
	}
	for _, gpu := range gpus {
		if gpu.Name == "" {
			t.Error("GPU has no name")
		}
		t.Logf("GPU: %s | driver %s | VRAM %d bytes", gpu.Name, gpu.Driver, gpu.VRAMBytes)
	}
}

// TestBatteryCapacityMwhConvertsChargeUnits is the conversion most worth a
// test. Drivers report either energy_* in microwatt-hours or charge_* in
// microamp-hours, and treating a charge as an energy overstates a typical
// 11.6 V laptop pack by an order of magnitude, which makes the wear figure --
// the one number this card exists for -- meaningless.
func TestBatteryCapacityMwhConvertsChargeUnits(t *testing.T) {
	t.Run("energy is microwatt-hours", func(t *testing.T) {
		dir := t.TempDir()
		writeSysValues(t, dir, map[string]string{"energy_full_design": "48000000"})
		if got := batteryCapacityMwh(dir, "full_design"); got != 48000 {
			t.Errorf("got %d mWh, want 48000", got)
		}
	})

	t.Run("charge is microamp-hours times volts", func(t *testing.T) {
		dir := t.TempDir()
		writeSysValues(t, dir, map[string]string{
			"charge_full_design": "4110000",  // 4.11 Ah
			"voltage_min_design": "11678000", // 11.678 V
		})
		// 4.11 Ah * 11.678 V = 47.99 Wh
		got := batteryCapacityMwh(dir, "full_design")
		if got < 47000 || got > 49000 {
			t.Errorf("got %d mWh, want roughly 48000", got)
		}
	})

	t.Run("energy wins where both are published", func(t *testing.T) {
		dir := t.TempDir()
		writeSysValues(t, dir, map[string]string{
			"energy_full_design": "48000000",
			"charge_full_design": "4110000",
			"voltage_min_design": "11678000",
		})
		if got := batteryCapacityMwh(dir, "full_design"); got != 48000 {
			t.Errorf("got %d mWh, want the energy reading 48000", got)
		}
	})

	t.Run("voltage_now stands in for a missing design voltage", func(t *testing.T) {
		dir := t.TempDir()
		writeSysValues(t, dir, map[string]string{
			"charge_full": "4110000",
			"voltage_now": "11678000",
		})
		if got := batteryCapacityMwh(dir, "full"); got == 0 {
			t.Error("got 0 mWh with a usable voltage_now")
		}
	})

	t.Run("nothing published", func(t *testing.T) {
		if got := batteryCapacityMwh(t.TempDir(), "full_design"); got != 0 {
			t.Errorf("got %d mWh from an empty directory", got)
		}
	})
}

// TestLinuxBatteryInfoOnThisHost checks the live read is self-consistent. It
// covers both shapes: a desktop reports absent, which is what the phone
// renders as wall power, and a laptop must report a plausible charge.
func TestLinuxBatteryInfoOnThisHost(t *testing.T) {
	info := linuxBatteryInfo()
	if !info.Present {
		t.Skip("host has no system battery")
	}
	if info.Percent < 0 || info.Percent > 100 {
		t.Errorf("charge %d%% outside 0-100", info.Percent)
	}
	if info.WearPercent < 0 || info.WearPercent > 100 {
		t.Errorf("wear %.1f%% outside 0-100", info.WearPercent)
	}
	if info.DesignCapacityMwh > 0 && info.FullCapacityMwh > info.DesignCapacityMwh*2 {
		t.Errorf("full capacity %d mWh dwarfs the design capacity %d mWh: the charge/energy units are being mixed",
			info.FullCapacityMwh, info.DesignCapacityMwh)
	}
	t.Logf("battery: %d%% charging=%v wear=%.1f%% cycles=%d design=%d mWh full=%d mWh",
		info.Percent, info.Charging, info.WearPercent, info.CycleCount,
		info.DesignCapacityMwh, info.FullCapacityMwh)
}

// TestLinuxBatteryIgnoresPeripherals covers the trap in power_supply: a
// wireless mouse or keyboard registers as type Battery. Taking the first one
// found would have the phone report the host's charge as the mouse's.
func TestLinuxBatteryIgnoresPeripherals(t *testing.T) {
	mouse := t.TempDir()
	writeSysValues(t, mouse, map[string]string{"type": "Battery", "capacity": "55"})
	if hasDesignCapacity(mouse) {
		t.Error("a peripheral reporting only a coarse level was taken for a system battery")
	}

	laptop := t.TempDir()
	writeSysValues(t, laptop, map[string]string{
		"type":               "Battery",
		"capacity":           "88",
		"charge_full_design": "4110000",
	})
	if !hasDesignCapacity(laptop) {
		t.Error("a pack with a design capacity was not recognised")
	}
}

func writeSysValues(t *testing.T, dir string, values map[string]string) {
	t.Helper()
	for name, value := range values {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(value+"\n"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
}
