//! Display brightness and contrast: the laptop panel through the OS backlight,
//! external monitors over DDC/CI.
//!
//! DDC/CI is slow (tens of milliseconds a command, with mandatory gaps) and a
//! phone slider sends many values a second. Writes are coalesced: each request
//! records the latest wanted value, and whichever task gets the bus writes
//! only the newest one. Values superseded while waiting are never sent.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use anyhow::{Result, anyhow};
use switchboard_core::protocol::{Display, DisplayKind};

pub const VCP_BRIGHTNESS: u8 = 0x10;
pub const VCP_CONTRAST: u8 = 0x12;
const RESCAN_EVERY: Duration = Duration::from_secs(60);

#[derive(Default)]
struct Cache {
    list: Vec<Display>,
    scanned: Option<Instant>,
}

#[derive(Clone, Default)]
pub struct Displays {
    cache: Arc<Mutex<Cache>>,
    /// Latest wanted value per (display, feature), taken by the writer.
    pending: Arc<Mutex<HashMap<(String, u8), u8>>>,
    /// Serialises access to the DDC buses.
    bus: Arc<Mutex<()>>,
}

impl Displays {
    /// Cached list; rescans in the background-friendly blocking pool when stale.
    pub async fn list(&self) -> Vec<Display> {
        let stale = self.cache.lock().unwrap().scanned.is_none_or(|t| t.elapsed() > RESCAN_EVERY);
        if stale {
            let me = self.clone();
            let found = tokio::task::spawn_blocking(move || {
                let _bus = me.bus.lock().unwrap();
                imp::scan()
            })
            .await
            .unwrap_or_default();
            let mut c = self.cache.lock().unwrap();
            c.list = found;
            c.scanned = Some(Instant::now());
        }
        // The backlight is cheap to read and changes from the keyboard too.
        // Read off the async threads: on Windows this is a WMI query.
        let cached = self.cache.lock().unwrap().list.clone();
        let mut list = cached.clone();
        tokio::task::spawn_blocking(move || {
            for d in list.iter_mut().filter(|d| d.kind == DisplayKind::Internal) {
                d.brightness = imp::backlight_percent(&d.id).or(d.brightness);
            }
            list
        })
        .await
        .unwrap_or(cached)
    }

    pub async fn set(&self, id: &str, vcp: u8, level: u8) -> Result<()> {
        let level = level.min(100);
        let kind = self
            .cache
            .lock()
            .unwrap()
            .list
            .iter()
            .find(|d| d.id == id)
            .map(|d| d.kind)
            .ok_or_else(|| anyhow!("unknown display {id}"))?;

        if kind == DisplayKind::Internal {
            if vcp != VCP_BRIGHTNESS {
                return Err(anyhow!("the built-in display has no contrast control"));
            }
            imp::set_backlight(id, level).await?;
        } else {
            let key = (id.to_owned(), vcp);
            self.pending.lock().unwrap().insert(key.clone(), level);
            let me = self.clone();
            tokio::task::spawn_blocking(move || -> Result<()> {
                let _bus = me.bus.lock().unwrap();
                // A newer request may already have written our value.
                let Some(v) = me.pending.lock().unwrap().remove(&key) else { return Ok(()) };
                imp::ddc_set(&key.0, key.1, v)
            })
            .await??;
        }

        let mut c = self.cache.lock().unwrap();
        if let Some(d) = c.list.iter_mut().find(|d| d.id == id) {
            match vcp {
                VCP_CONTRAST => d.contrast = Some(level),
                _ => d.brightness = Some(level),
            }
        }
        Ok(())
    }
}

/// Monitor name from an EDID block: the 0xFC display descriptor, else the
/// three-letter manufacturer ID.
pub fn edid_name(edid: &[u8]) -> Option<String> {
    if edid.len() < 128 || edid[..8] != [0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0] {
        return None;
    }
    for off in [54, 72, 90, 108] {
        let d = &edid[off..off + 18];
        if d[..3] == [0, 0, 0] && d[3] == 0xFC {
            let name: String = d[5..].iter().take_while(|&&b| b != 0x0A).map(|&b| b as char).collect();
            let name = name.trim().to_owned();
            if !name.is_empty() {
                return Some(name);
            }
        }
    }
    let m = u16::from_be_bytes([edid[8], edid[9]]);
    let ch = |s: u16| (b'A' - 1 + (s & 0x1F) as u8) as char;
    Some(format!("{}{}{} monitor", ch(m >> 10), ch(m >> 5), ch(m)))
}

fn percent(cur: u32, max: u32) -> u8 {
    if max == 0 { 0 } else { ((cur as f64 * 100.0 / max as f64).round() as u32).min(100) as u8 }
}

#[cfg(target_os = "linux")]
mod imp {
    use super::*;
    use ddc::{Ddc, Edid};
    use std::fs;

    fn backlight_dirs() -> Vec<std::path::PathBuf> {
        fs::read_dir("/sys/class/backlight").map(|r| r.flatten().map(|e| e.path()).collect()).unwrap_or_default()
    }

    fn read_u32(p: &std::path::Path) -> Option<u32> {
        fs::read_to_string(p).ok()?.trim().parse().ok()
    }

    pub fn backlight_percent(id: &str) -> Option<u8> {
        let dev = id.strip_prefix("backlight:")?;
        let base = std::path::Path::new("/sys/class/backlight").join(dev);
        Some(percent(read_u32(&base.join("brightness"))?, read_u32(&base.join("max_brightness"))?))
    }

    /// Writes go through logind, which lets the active session set the
    /// backlight without root or a udev rule.
    pub async fn set_backlight(id: &str, level: u8) -> Result<()> {
        let dev = id.strip_prefix("backlight:").ok_or_else(|| anyhow!("not a backlight"))?;
        let max = read_u32(&std::path::Path::new("/sys/class/backlight").join(dev).join("max_brightness"))
            .ok_or_else(|| anyhow!("backlight {dev} vanished"))?;
        // Never write zero: a black panel looks like a dead laptop.
        let raw = ((max as u64 * level as u64) / 100).max(1) as u32;
        let conn = zbus::Connection::system().await?;
        conn.call_method(
            Some("org.freedesktop.login1"),
            "/org/freedesktop/login1/session/auto",
            Some("org.freedesktop.login1.Session"),
            "SetBrightness",
            &("backlight", dev, raw),
        )
        .await?;
        Ok(())
    }

    fn open(bus: &str) -> Result<ddc_i2c::I2cDeviceDdc> {
        Ok(ddc_i2c::from_i2c_device(format!("/dev/{bus}"))?)
    }

    fn get_pct(ddc: &mut ddc_i2c::I2cDeviceDdc, vcp: u8) -> Option<u8> {
        let v = ddc.get_vcp_feature(vcp).ok()?;
        Some(percent(v.value() as u32, v.maximum() as u32))
    }

    pub fn scan() -> Vec<Display> {
        let mut out: Vec<Display> = backlight_dirs()
            .into_iter()
            .filter_map(|p| p.file_name().map(|n| n.to_string_lossy().into_owned()))
            .take(1) // one panel; a second entry is the same panel through another driver
            .map(|dev| {
                let id = format!("backlight:{dev}");
                Display {
                    brightness: backlight_percent(&id),
                    id,
                    name: "Built-in display".into(),
                    kind: DisplayKind::Internal,
                    contrast: None,
                }
            })
            .collect();

        let mut buses: Vec<String> = fs::read_dir("/dev")
            .map(|r| r.flatten().map(|e| e.file_name().to_string_lossy().into_owned()).filter(|n| n.starts_with("i2c-")).collect())
            .unwrap_or_default();
        buses.sort_by_key(|b| b[4..].parse::<u32>().unwrap_or(u32::MAX));

        for bus in buses {
            let Ok(mut ddc) = open(&bus) else { continue };
            let mut edid = [0u8; 128];
            if ddc.read_edid(0, &mut edid).is_err() {
                continue;
            }
            let Some(name) = edid_name(&edid) else { continue };
            // A monitor that answers VCP 0x10 speaks DDC/CI; the laptop panel's
            // bus has an EDID but no DDC and drops out here.
            let Some(brightness) = get_pct(&mut ddc, VCP_BRIGHTNESS) else { continue };
            let contrast = get_pct(&mut ddc, VCP_CONTRAST);
            out.push(Display { id: format!("ddc:{bus}"), name, kind: DisplayKind::External, brightness: Some(brightness), contrast });
        }
        out
    }

    pub fn ddc_set(id: &str, vcp: u8, level: u8) -> Result<()> {
        let bus = id.strip_prefix("ddc:").ok_or_else(|| anyhow!("not a DDC display"))?;
        let mut ddc = open(bus)?;
        let max = ddc.get_vcp_feature(vcp).map(|v| v.maximum()).unwrap_or(100).max(1);
        let raw = (max as u32 * level as u32 / 100) as u16;
        ddc.set_vcp_feature(vcp, raw).map_err(|e| anyhow!("DDC write failed: {e}"))
    }
}

#[cfg(windows)]
mod imp {
    use super::*;
    use ddc::Ddc;

    use serde::{Deserialize, Serialize};
    use wmi::{COMLibrary, WMIConnection};

    // The laptop panel is not a DDC/CI monitor; Windows exposes its backlight
    // through WMI in the root\wmi namespace.
    const INTERNAL: &str = "wmi:backlight";

    #[derive(Deserialize)]
    #[allow(non_snake_case)]
    struct WmiMonitorBrightness {
        CurrentBrightness: u8,
    }

    #[derive(Deserialize)]
    #[allow(non_snake_case)]
    struct WmiMonitorBrightnessMethods {
        __Path: String,
    }

    #[derive(Serialize)]
    #[allow(non_snake_case)]
    struct SetBrightness {
        Timeout: u32,
        Brightness: u8,
    }

    fn wmi() -> Result<WMIConnection> {
        Ok(WMIConnection::with_namespace_path("ROOT\\WMI", COMLibrary::new()?)?)
    }

    /// `None` on a desktop: the class has no active instance there.
    pub fn backlight_percent(id: &str) -> Option<u8> {
        if id != INTERNAL {
            return None;
        }
        let r: Vec<WmiMonitorBrightness> = wmi().ok()?.raw_query("SELECT CurrentBrightness FROM WmiMonitorBrightness WHERE Active = TRUE").ok()?;
        r.first().map(|b| b.CurrentBrightness.min(100))
    }

    pub async fn set_backlight(_: &str, level: u8) -> Result<()> {
        tokio::task::spawn_blocking(move || -> Result<()> {
            let con = wmi()?;
            let panels: Vec<WmiMonitorBrightnessMethods> = con.raw_query("SELECT __Path FROM WmiMonitorBrightnessMethods WHERE Active = TRUE")?;
            let panel = panels.first().ok_or_else(|| anyhow!("no laptop display found"))?;
            // Never write zero: a black panel looks like a dead laptop.
            con.exec_instance_method::<WmiMonitorBrightnessMethods, _, ()>("WmiSetBrightness", &panel.__Path, SetBrightness { Timeout: 0, Brightness: level.clamp(1, 100) })?;
            Ok(())
        })
        .await?
    }

    pub fn scan() -> Vec<Display> {
        let mut out = Vec::new();
        if let Some(b) = backlight_percent(INTERNAL) {
            out.push(Display { id: INTERNAL.into(), name: "Built-in display".into(), kind: DisplayKind::Internal, brightness: Some(b), contrast: None });
        }
        let Ok(mons) = ddc_winapi::Monitor::enumerate() else { return out };
        out.extend(mons.into_iter()
            .enumerate()
            .filter_map(|(i, mut m)| {
                let b = m.get_vcp_feature(VCP_BRIGHTNESS).ok()?;
                let c = m.get_vcp_feature(VCP_CONTRAST).ok();
                let desc = m.description();
                Some(Display {
                    id: format!("ddc:{i}"),
                    name: if desc.is_empty() { format!("Monitor {}", i + 1) } else { desc },
                    kind: DisplayKind::External,
                    brightness: Some(percent(b.value() as u32, b.maximum() as u32)),
                    contrast: c.map(|c| percent(c.value() as u32, c.maximum() as u32)),
                })
            }));
        out
    }

    pub fn ddc_set(id: &str, vcp: u8, level: u8) -> Result<()> {
        let i: usize = id.strip_prefix("ddc:").and_then(|s| s.parse().ok()).ok_or_else(|| anyhow!("bad id"))?;
        let mut m = ddc_winapi::Monitor::enumerate()?.into_iter().nth(i).ok_or_else(|| anyhow!("monitor gone"))?;
        let max = m.get_vcp_feature(vcp).map(|v| v.maximum()).unwrap_or(100).max(1);
        m.set_vcp_feature(vcp, (max as u32 * level as u32 / 100) as u16).map_err(|e| anyhow!("DDC write failed: {e}"))
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod imp {
    use super::*;
    pub fn backlight_percent(_: &str) -> Option<u8> { None }
    pub async fn set_backlight(_: &str, _: u8) -> Result<()> { Err(anyhow!("unsupported")) }
    pub fn scan() -> Vec<Display> { vec![] }
    pub fn ddc_set(_: &str, _: u8, _: u8) -> Result<()> { Err(anyhow!("unsupported")) }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn edid_with(desc: Option<&str>) -> [u8; 128] {
        let mut e = [0u8; 128];
        e[..8].copy_from_slice(&[0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0]);
        // "ACR": A=1, C=3, R=18 -> 0b0_00001_00011_10010
        e[8..10].copy_from_slice(&0x0472u16.to_be_bytes());
        if let Some(n) = desc {
            e[54..59].copy_from_slice(&[0, 0, 0, 0xFC, 0]);
            for (i, b) in n.bytes().enumerate() {
                e[59 + i] = b;
            }
            e[59 + n.len()] = 0x0A;
        }
        e
    }

    #[test]
    fn edid_descriptor_name() {
        assert_eq!(edid_name(&edid_with(Some("EK240Y P6"))).as_deref(), Some("EK240Y P6"));
    }

    #[test]
    fn edid_manufacturer_fallback() {
        assert_eq!(edid_name(&edid_with(None)).as_deref(), Some("ACR monitor"));
    }

    #[test]
    fn edid_rejects_garbage() {
        assert_eq!(edid_name(&[1u8; 128]), None);
    }

    #[test]
    fn percent_rounds() {
        assert_eq!(percent(60000, 120000), 50);
        assert_eq!(percent(1, 0), 0);
    }
}
