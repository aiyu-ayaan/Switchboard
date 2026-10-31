//! Default output device volume.

use anyhow::Result;
use switchboard_core::protocol::Volume;

#[cfg(target_os = "linux")]
mod imp {
    use super::*;
    use anyhow::{Context, bail};
    use std::process::Command;

    const SINK: &str = "@DEFAULT_AUDIO_SINK@";

    fn run(cmd: &str, args: &[&str]) -> Result<String> {
        let out = Command::new(cmd).args(args).output().with_context(|| format!("{cmd} is not installed"))?;
        if !out.status.success() {
            bail!("{cmd} failed: {}", String::from_utf8_lossy(&out.stderr).trim());
        }
        Ok(String::from_utf8_lossy(&out.stdout).into_owned())
    }

    /// `Volume: 0.40` or `Volume: 0.40 [MUTED]` (PipeWire's wpctl).
    pub(super) fn parse_wpctl(s: &str) -> Option<Volume> {
        let v: f32 = s.split_whitespace().nth(1)?.parse().ok()?;
        Some(Volume { level: (v * 100.0).round().clamp(0.0, 100.0) as u8, muted: s.contains("[MUTED]") })
    }

    /// PulseAudio fallback: first `NN%` of `pactl get-sink-volume`.
    pub(super) fn parse_pactl(vol: &str, mute: &str) -> Option<Volume> {
        let pct = vol.split_whitespace().find(|w| w.ends_with('%'))?;
        let level: u32 = pct.trim_end_matches('%').parse().ok()?;
        Some(Volume { level: level.min(100) as u8, muted: mute.contains("yes") })
    }

    pub fn get() -> Result<Volume> {
        if let Ok(s) = run("wpctl", &["get-volume", SINK]) {
            return parse_wpctl(&s).context("unexpected wpctl output");
        }
        let vol = run("pactl", &["get-sink-volume", "@DEFAULT_SINK@"])?;
        let mute = run("pactl", &["get-sink-mute", "@DEFAULT_SINK@"])?;
        parse_pactl(&vol, &mute).context("unexpected pactl output")
    }

    pub fn set_level(level: u8) -> Result<()> {
        let l = level.min(100);
        if run("wpctl", &["set-volume", SINK, &format!("{}%", l)]).is_ok() {
            return Ok(());
        }
        run("pactl", &["set-sink-volume", "@DEFAULT_SINK@", &format!("{l}%")]).map(drop)
    }

    pub fn set_mute(muted: bool) -> Result<()> {
        let m = if muted { "1" } else { "0" };
        if run("wpctl", &["set-mute", SINK, m]).is_ok() {
            return Ok(());
        }
        run("pactl", &["set-sink-mute", "@DEFAULT_SINK@", m]).map(drop)
    }
}

#[cfg(windows)]
mod imp {
    use super::*;
    use windows::Win32::Media::Audio::Endpoints::IAudioEndpointVolume;
    use windows::Win32::Media::Audio::{IMMDeviceEnumerator, MMDeviceEnumerator, eConsole, eRender};
    use windows::Win32::System::Com::{CLSCTX_ALL, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx};

    fn endpoint() -> Result<IAudioEndpointVolume> {
        unsafe {
            // Already-initialised is fine; every call runs on a blocking thread.
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            let e: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
            let dev = e.GetDefaultAudioEndpoint(eRender, eConsole)?;
            Ok(dev.Activate(CLSCTX_ALL, None)?)
        }
    }

    pub fn get() -> Result<Volume> {
        let ep = endpoint()?;
        unsafe {
            let v = ep.GetMasterVolumeLevelScalar()?;
            let m = ep.GetMute()?.as_bool();
            Ok(Volume { level: (v * 100.0).round() as u8, muted: m })
        }
    }

    pub fn set_level(level: u8) -> Result<()> {
        unsafe { Ok(endpoint()?.SetMasterVolumeLevelScalar(level.min(100) as f32 / 100.0, std::ptr::null())?) }
    }

    pub fn set_mute(muted: bool) -> Result<()> {
        unsafe { Ok(endpoint()?.SetMute(muted, std::ptr::null())?) }
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod imp {
    use super::*;
    pub fn get() -> Result<Volume> { anyhow::bail!("unsupported platform") }
    pub fn set_level(_: u8) -> Result<()> { anyhow::bail!("unsupported platform") }
    pub fn set_mute(_: bool) -> Result<()> { anyhow::bail!("unsupported platform") }
}

pub use imp::{get, set_level, set_mute};

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::imp::*;

    #[test]
    fn wpctl_output() {
        assert_eq!(parse_wpctl("Volume: 0.40\n").unwrap().level, 40);
        let m = parse_wpctl("Volume: 1.00 [MUTED]\n").unwrap();
        assert!(m.muted && m.level == 100);
        assert_eq!(parse_wpctl("Volume: 1.53").unwrap().level, 100, "boosted clamps");
    }

    #[test]
    fn pactl_output() {
        let v = parse_pactl("Volume: front-left: 26214 /  40% / -23.88 dB,   front-right: ...", "Mute: no").unwrap();
        assert_eq!((v.level, v.muted), (40, false));
    }
}
