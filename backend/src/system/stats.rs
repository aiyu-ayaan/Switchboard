//! CPU, memory, uptime and battery.

use std::sync::Mutex;

use switchboard_core::protocol::{Battery, Stats};
use sysinfo::{MemoryRefreshKind, RefreshKind, System};

pub struct Sampler {
    sys: Mutex<System>,
}

impl Default for Sampler {
    fn default() -> Self {
        let sys = System::new_with_specifics(
            RefreshKind::nothing().with_cpu(sysinfo::CpuRefreshKind::nothing().with_cpu_usage()).with_memory(MemoryRefreshKind::nothing().with_ram()),
        );
        Self { sys: Mutex::new(sys) }
    }
}

impl Sampler {
    /// CPU usage is the average since the previous call, so the first sample
    /// after start reads 0.
    pub fn sample(&self) -> Stats {
        let mut s = self.sys.lock().unwrap();
        s.refresh_cpu_usage();
        s.refresh_memory_specifics(MemoryRefreshKind::nothing().with_ram());
        Stats {
            cpu: s.global_cpu_usage(),
            mem_used: s.used_memory(),
            mem_total: s.total_memory(),
            uptime: System::uptime(),
            battery: battery(),
        }
    }
}

#[cfg(target_os = "linux")]
fn battery() -> Option<Battery> {
    let dir = std::fs::read_dir("/sys/class/power_supply").ok()?.flatten().map(|e| e.path()).find(|p| {
        std::fs::read_to_string(p.join("type")).is_ok_and(|t| t.trim() == "Battery")
    })?;
    let percent: u8 = std::fs::read_to_string(dir.join("capacity")).ok()?.trim().parse().ok()?;
    let status = std::fs::read_to_string(dir.join("status")).unwrap_or_default();
    Some(Battery { percent: percent.min(100), charging: matches!(status.trim(), "Charging" | "Full") })
}

#[cfg(windows)]
fn battery() -> Option<Battery> {
    use windows::Win32::System::Power::{GetSystemPowerStatus, SYSTEM_POWER_STATUS};
    let mut st = SYSTEM_POWER_STATUS::default();
    unsafe { GetSystemPowerStatus(&mut st).ok()? };
    // 128 = no system battery, 255 = unknown.
    if st.BatteryFlag & 128 != 0 || st.BatteryLifePercent == 255 {
        return None;
    }
    Some(Battery { percent: st.BatteryLifePercent.min(100), charging: st.ACLineStatus == 1 })
}

#[cfg(not(any(target_os = "linux", windows)))]
fn battery() -> Option<Battery> {
    None
}
