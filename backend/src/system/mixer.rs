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

#[cfg(not(target_os = "linux"))]
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
