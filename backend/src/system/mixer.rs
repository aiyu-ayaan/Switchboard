//! Per-program volume and the choice of output device.

use anyhow::Result;
use switchboard_core::protocol::{AudioApp, AudioOutput};

#[cfg(target_os = "linux")]
mod imp {
    use super::*;
    use anyhow::{Context, bail};
    use serde_json::Value;
    use std::process::Command;

    // `pactl` speaks to PulseAudio and to PipeWire's Pulse server alike.
    fn pactl(args: &[&str]) -> Result<String> {
        let out = Command::new("pactl").args(args).output().context("pactl is not installed")?;
        if !out.status.success() {
            bail!("pactl failed: {}", String::from_utf8_lossy(&out.stderr).trim());
        }
        Ok(String::from_utf8_lossy(&out.stdout).into_owned())
    }

    fn list(kind: &str) -> Result<Value> {
        Ok(serde_json::from_str(&pactl(&["-f", "json", "list", kind])?)?)
    }

    pub(super) fn parse_outputs(sinks: &Value, default: &str) -> Vec<AudioOutput> {
        sinks
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|s| {
                let id = s["name"].as_str()?;
                let name = s["description"].as_str().unwrap_or(id);
                Some(AudioOutput { id: id.into(), name: name.into(), default: id == default })
            })
            .collect()
    }

    /// Loudest channel, as a percentage clamped to the slider's range.
    fn level(v: &Value) -> u8 {
        v["volume"]
            .as_object()
            .into_iter()
            .flat_map(|m| m.values())
            .filter_map(|c| c["value_percent"].as_str()?.trim_end_matches('%').trim().parse::<u32>().ok())
            .max()
            .unwrap_or(0)
            .min(100) as u8
    }

    pub(super) fn parse_apps(inputs: &Value) -> Vec<AudioApp> {
        inputs
            .as_array()
            .into_iter()
            .flatten()
            .filter_map(|s| {
                let p = &s["properties"];
                let name = p["application.name"].as_str().or(p["media.name"].as_str()).unwrap_or("Unknown");
                let media = p["media.name"].as_str().unwrap_or("");
                Some(AudioApp {
                    id: s["index"].as_u64()?.to_string(),
                    name: name.into(),
                    // Many apps name the stream after themselves; only show a real title.
                    detail: if media == name { String::new() } else { media.into() },
                    level: level(s),
                    muted: s["mute"].as_bool().unwrap_or(false),
                })
            })
            .collect()
    }

    pub fn outputs() -> Result<Vec<AudioOutput>> {
        let default = pactl(&["get-default-sink"])?;
        Ok(parse_outputs(&list("sinks")?, default.trim()))
    }

    pub fn apps() -> Result<Vec<AudioApp>> {
        Ok(parse_apps(&list("sink-inputs")?))
    }

    /// Only accepts the digits of a stream index: the id comes off the network.
    fn stream(app: &str) -> Result<&str> {
        if app.is_empty() || !app.bytes().all(|b| b.is_ascii_digit()) {
            bail!("unknown program");
        }
        Ok(app)
    }

    pub fn set_app_volume(app: &str, level: u8) -> Result<()> {
        pactl(&["set-sink-input-volume", stream(app)?, &format!("{}%", level.min(100))]).map(drop)
    }

    pub fn set_app_mute(app: &str, muted: bool) -> Result<()> {
        pactl(&["set-sink-input-mute", stream(app)?, if muted { "1" } else { "0" }]).map(drop)
    }

    pub fn set_output(output: &str) -> Result<()> {
        // Must be a sink we listed, so a phone cannot smuggle in a flag.
        if !outputs()?.iter().any(|o| o.id == output) {
            bail!("unknown output device");
        }
        pactl(&["set-default-sink", output])?;
        // Programs already playing stay on the old device otherwise.
        for a in apps().unwrap_or_default() {
            let _ = pactl(&["move-sink-input", &a.id, output]);
        }
        Ok(())
    }
}

#[cfg(windows)]
#[allow(non_snake_case)]
mod imp {
    use super::*;
    use anyhow::{anyhow, bail};
    use std::ffi::c_void;
    use windows::Win32::Devices::FunctionDiscovery::PKEY_Device_FriendlyName;
    use windows::Win32::Foundation::{CloseHandle, BOOL, FALSE, TRUE};
    use windows::Win32::Media::Audio::{
        AudioSessionStateExpired, DEVICE_STATE_ACTIVE, IAudioSessionControl2, IAudioSessionManager2, IMMDevice, IMMDeviceEnumerator, ISimpleAudioVolume,
        MMDeviceEnumerator, eCommunications, eConsole, eMultimedia, eRender, ERole,
    };
    use windows::Win32::System::Com::{CLSCTX_ALL, COINIT_MULTITHREADED, CoCreateInstance, CoInitializeEx, CoTaskMemFree, STGM_READ};
    use windows::Win32::System::Threading::{OpenProcess, PROCESS_NAME_WIN32, PROCESS_QUERY_LIMITED_INFORMATION, QueryFullProcessImageNameW};
    use windows::core::{GUID, HRESULT, Interface, PCWSTR, PWSTR};

    /// The Windows audio policy interface that switches the default device. It
    /// has no public header; the layout is the one every sound-switching tool uses.
    #[windows::core::interface("f8679f50-850a-41cf-9c72-430f290290c8")]
    unsafe trait IPolicyConfig: windows::core::IUnknown {
        fn GetMixFormat(&self, device: PCWSTR, format: *mut *mut c_void) -> HRESULT;
        fn GetDeviceFormat(&self, device: PCWSTR, default: i32, format: *mut *mut c_void) -> HRESULT;
        fn ResetDeviceFormat(&self, device: PCWSTR) -> HRESULT;
        fn SetDeviceFormat(&self, device: PCWSTR, endpoint: *const c_void, mix: *const c_void) -> HRESULT;
        fn GetProcessingPeriod(&self, device: PCWSTR, default: i32, default_period: *mut i64, min_period: *mut i64) -> HRESULT;
        fn SetProcessingPeriod(&self, device: PCWSTR, period: *const i64) -> HRESULT;
        fn GetShareMode(&self, device: PCWSTR, mode: *mut c_void) -> HRESULT;
        fn SetShareMode(&self, device: PCWSTR, mode: *const c_void) -> HRESULT;
        fn GetPropertyValue(&self, device: PCWSTR, fx: i32, key: *const c_void, value: *mut c_void) -> HRESULT;
        fn SetPropertyValue(&self, device: PCWSTR, fx: i32, key: *const c_void, value: *const c_void) -> HRESULT;
        fn SetDefaultEndpoint(&self, device: PCWSTR, role: ERole) -> HRESULT;
        fn SetEndpointVisibility(&self, device: PCWSTR, visible: i32) -> HRESULT;
    }

    const POLICY_CONFIG_CLIENT: GUID = GUID::from_u128(0x870af99c_171d_4f9e_af0d_e63df40c2bc9);

    fn enumerator() -> Result<IMMDeviceEnumerator> {
        unsafe {
            // Already-initialised is fine; every call runs on a blocking thread.
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            Ok(CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?)
        }
    }

    fn device_id(d: &IMMDevice) -> Result<String> {
        unsafe {
            let p = d.GetId()?;
            let s = p.to_string();
            CoTaskMemFree(Some(p.0 as *const c_void));
            Ok(s?)
        }
    }

    fn friendly_name(d: &IMMDevice) -> Option<String> {
        unsafe {
            let store = d.OpenPropertyStore(STGM_READ).ok()?;
            // The friendly name is a wide string inside the variant, which frees itself.
            let s = store.GetValue(&PKEY_Device_FriendlyName).ok()?.to_string();
            (!s.is_empty()).then_some(s)
        }
    }

    pub fn outputs() -> Result<Vec<AudioOutput>> {
        unsafe {
            let e = enumerator()?;
            let default = device_id(&e.GetDefaultAudioEndpoint(eRender, eConsole)?)?;
            let all = e.EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE)?;
            let mut out = Vec::new();
            for i in 0..all.GetCount()? {
                let d = all.Item(i)?;
                let id = device_id(&d)?;
                out.push(AudioOutput { name: friendly_name(&d).unwrap_or_else(|| id.clone()), default: id == default, id });
            }
            Ok(out)
        }
    }

    pub fn set_output(id: &str) -> Result<()> {
        // Only a device the system lists: the id comes off the network.
        if !outputs()?.iter().any(|o| o.id == id) {
            bail!("unknown output device");
        }
        unsafe {
            let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            let policy: IPolicyConfig = CoCreateInstance(&POLICY_CONFIG_CLIENT, None, CLSCTX_ALL)?;
            let wide: Vec<u16> = id.encode_utf16().chain(std::iter::once(0)).collect();
            for role in [eConsole, eMultimedia, eCommunications] {
                policy.SetDefaultEndpoint(PCWSTR(wide.as_ptr()), role).ok()?;
            }
        }
        Ok(())
    }

    struct Session {
        id: String,
        name: String,
        detail: String,
        volume: ISimpleAudioVolume,
    }

    fn exe_name(pid: u32) -> Option<String> {
        unsafe {
            let h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid).ok()?;
            let mut buf = [0u16; 520];
            let mut len = buf.len() as u32;
            let ok = QueryFullProcessImageNameW(h, PROCESS_NAME_WIN32, PWSTR(buf.as_mut_ptr()), &mut len).is_ok();
            let _ = CloseHandle(h);
            if !ok {
                return None;
            }
            let path = String::from_utf16_lossy(&buf[..len as usize]);
            let file = path.rsplit('\\').next()?;
            Some(file.trim_end_matches(".exe").to_owned())
        }
    }

    /// Programs with an audio session on the default output device.
    fn sessions() -> Result<Vec<Session>> {
        unsafe {
            let e = enumerator()?;
            let dev = e.GetDefaultAudioEndpoint(eRender, eConsole)?;
            let mgr: IAudioSessionManager2 = dev.Activate(CLSCTX_ALL, None)?;
            let list = mgr.GetSessionEnumerator()?;
            let mut out = Vec::new();
            for i in 0..list.GetCount()? {
                let ctl = list.GetSession(i)?;
                let ctl2: IAudioSessionControl2 = ctl.cast()?;
                // System sounds and expired sessions are not programs the person is using.
                if ctl2.IsSystemSoundsSession().is_ok() && ctl2.IsSystemSoundsSession().0 == 0 {
                    continue;
                }
                if ctl.GetState().ok() == Some(AudioSessionStateExpired) {
                    continue;
                }
                let id = {
                    let p = ctl2.GetSessionInstanceIdentifier()?;
                    let s = p.to_string();
                    CoTaskMemFree(Some(p.0 as *const c_void));
                    s?
                };
                let shown = ctl.GetDisplayName().ok().and_then(|p| {
                    let s = p.to_string().ok();
                    CoTaskMemFree(Some(p.0 as *const c_void));
                    s
                });
                let exe = ctl2.GetProcessId().ok().and_then(exe_name);
                let name = exe.clone().or(shown.clone().filter(|s| !s.is_empty())).unwrap_or_else(|| "Program".into());
                let detail = shown.filter(|s| !s.is_empty() && Some(s) != exe.as_ref()).unwrap_or_default();
                out.push(Session { id, name, detail, volume: ctl.cast()? });
            }
            Ok(out)
        }
    }

    pub fn apps() -> Result<Vec<AudioApp>> {
        unsafe {
            sessions()?
                .into_iter()
                .map(|s| {
                    let level = (s.volume.GetMasterVolume()? * 100.0).round().clamp(0.0, 100.0) as u8;
                    let muted = s.volume.GetMute()?.as_bool();
                    Ok(AudioApp { id: s.id, name: s.name, detail: s.detail, level, muted })
                })
                .collect()
        }
    }

    fn find(app: &str) -> Result<Session> {
        sessions()?.into_iter().find(|s| s.id == app).ok_or_else(|| anyhow!("that program stopped playing"))
    }

    pub fn set_app_volume(app: &str, level: u8) -> Result<()> {
        unsafe { Ok(find(app)?.volume.SetMasterVolume(level.min(100) as f32 / 100.0, std::ptr::null())?) }
    }

    pub fn set_app_mute(app: &str, muted: bool) -> Result<()> {
        let flag: BOOL = if muted { TRUE } else { FALSE };
        unsafe { Ok(find(app)?.volume.SetMute(flag, std::ptr::null())?) }
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod imp {
    use super::*;
    pub fn outputs() -> Result<Vec<AudioOutput>> { Ok(Vec::new()) }
    pub fn apps() -> Result<Vec<AudioApp>> { Ok(Vec::new()) }
    pub fn set_app_volume(_: &str, _: u8) -> Result<()> { anyhow::bail!("unsupported platform") }
    pub fn set_app_mute(_: &str, _: bool) -> Result<()> { anyhow::bail!("unsupported platform") }
    pub fn set_output(_: &str) -> Result<()> { anyhow::bail!("unsupported platform") }
}

pub use imp::{apps, outputs, set_app_mute, set_app_volume, set_output};

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::imp::*;
    use serde_json::json;

    #[test]
    fn sinks_mark_the_default() {
        let s = json!([{"name":"a","description":"Speakers"},{"name":"b","description":"HDMI"}]);
        let o = parse_outputs(&s, "b");
        assert_eq!((o[0].default, o[1].default), (false, true));
        assert_eq!(o[1].name, "HDMI");
    }

    #[test]
    fn stream_volume_and_names() {
        let s = json!([
            {"index":202,"mute":true,"volume":{"front-left":{"value_percent":"40%"},"front-right":{"value_percent":"55%"}},
             "properties":{"application.name":"Firefox","media.name":"Lo-fi radio"}},
            {"index":203,"mute":false,"volume":{"mono":{"value_percent":"153%"}},
             "properties":{"application.name":"mpv","media.name":"mpv"}},
        ]);
        let a = parse_apps(&s);
        assert_eq!((a[0].id.as_str(), a[0].level, a[0].muted, a[0].detail.as_str()), ("202", 55, true, "Lo-fi radio"));
        assert_eq!((a[1].level, a[1].detail.as_str()), (100, ""));
    }

    #[test]
    fn stream_ids_must_be_digits() {
        assert!(set_app_volume("--help", 10).is_err());
        assert!(set_app_mute("", true).is_err());
    }
}
