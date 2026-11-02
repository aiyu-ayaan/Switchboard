//! Wire messages. The handshake hellos travel as plaintext JSON text frames;
//! everything after them is JSON inside encrypted binary frames.
//!
//! The Android app mirrors these types in Kotlin (`net/Protocol.kt`); keep the
//! two in step when a field changes.

use serde::{Deserialize, Serialize};

pub const PROTOCOL_VERSION: u32 = 1;
pub const DEFAULT_PORT: u16 = 9427;
pub const MDNS_SERVICE: &str = "_switchboard._tcp.local.";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HelloMode {
    /// First contact with a QR token as the pairing secret.
    PairQr,
    /// First contact with a typed code as the pairing secret.
    PairCode,
    /// A device the host already trusts.
    Resume,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClientHello {
    pub v: u32,
    pub mode: HelloMode,
    pub device_id: String,
    pub device_name: String,
    /// Base64url (no padding) X25519 public keys.
    pub static_pk: String,
    pub eph_pk: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ServerHello {
    Accept { v: u32, host_id: String, host_name: String, eph_pk: String },
    Reject { reason: RejectReason },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RejectReason {
    /// Resume from a device that was forgotten or never paired.
    Unknown,
    /// Pairing attempt while the desktop is not showing a pairing code.
    NotPairing,
    Version,
    Malformed,
}

/// Phone to desktop, inside the encrypted channel.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct Request {
    pub id: u64,
    #[serde(flatten)]
    pub cmd: Command,
}

#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Command {
    /// The first encrypted frame; proves the keys match.
    Hello,
    GetState,
    SetVolume { level: u8 },
    SetMute { muted: bool },
    /// Make this output device (an `AudioOutput::id`) the default.
    SetOutput { output: String },
    SetAppVolume { app: String, level: u8 },
    SetAppMute { app: String, muted: bool },
    SetBrightness { display: String, level: u8 },
    SetContrast { display: String, level: u8 },
    Media { action: MediaAction },
    Power { action: PowerAction },
    Ping,
    /// The phone forgot this desktop; the desktop drops it too.
    Unpair,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MediaAction {
    PlayPause,
    Next,
    Previous,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PowerAction {
    Lock,
    Sleep,
    DisplayOff,
}

/// Desktop to phone, inside the encrypted channel.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Event {
    Welcome { host_id: String, host_name: String, os: String },
    Result { id: u64, ok: bool, #[serde(skip_serializing_if = "Option::is_none")] error: Option<String> },
    State(Box<HostState>),
    /// The desktop forgot this phone; it should drop its saved keys.
    Revoked,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct HostState {
    pub volume: Option<Volume>,
    #[serde(default)]
    pub outputs: Vec<AudioOutput>,
    #[serde(default)]
    pub apps: Vec<AudioApp>,
    pub displays: Vec<Display>,
    pub media: Option<Media>,
    pub stats: Stats,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Volume {
    pub level: u8,
    pub muted: bool,
}

/// A speaker, headphone or HDMI output the computer can play through.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AudioOutput {
    pub id: String,
    pub name: String,
    pub default: bool,
}

/// One program's audio stream, with its own volume.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AudioApp {
    pub id: String,
    pub name: String,
    /// What it is playing, when it says (a browser tab title).
    pub detail: String,
    pub level: u8,
    pub muted: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DisplayKind {
    /// Laptop panel driven through the OS backlight.
    Internal,
    /// External monitor driven over DDC/CI.
    External,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Display {
    pub id: String,
    pub name: String,
    pub kind: DisplayKind,
    pub brightness: Option<u8>,
    pub contrast: Option<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Media {
    pub player: String,
    pub title: String,
    pub artist: String,
    pub playing: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
pub struct Stats {
    pub cpu: f32,
    pub mem_used: u64,
    pub mem_total: u64,
    pub uptime: u64,
    pub battery: Option<Battery>,
}

#[derive(Debug, Clone, Copy, PartialEq, Serialize, Deserialize)]
pub struct Battery {
    pub percent: u8,
    pub charging: bool,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn request_shape_is_flat() {
        let r = Request { id: 3, cmd: Command::SetVolume { level: 40 } };
        let j = serde_json::to_string(&r).unwrap();
        assert_eq!(j, r#"{"id":3,"type":"set_volume","level":40}"#);
        assert_eq!(serde_json::from_str::<Request>(&j).unwrap(), r);
    }

    #[test]
    fn media_request() {
        let r: Request = serde_json::from_str(r#"{"id":1,"type":"media","action":"play_pause"}"#).unwrap();
        assert_eq!(r.cmd, Command::Media { action: MediaAction::PlayPause });
    }

    #[test]
    fn state_event_shape() {
        let e = Event::State(Box::default());
        let j = serde_json::to_string(&e).unwrap();
        assert!(j.starts_with(r#"{"type":"state","volume":null"#), "{j}");
    }

    #[test]
    fn mixer_requests() {
        let r: Request = serde_json::from_str(r#"{"id":2,"type":"set_app_volume","app":"202","level":30}"#).unwrap();
        assert_eq!(r.cmd, Command::SetAppVolume { app: "202".into(), level: 30 });
        let r: Request = serde_json::from_str(r#"{"id":3,"type":"set_output","output":"alsa_output.x"}"#).unwrap();
        assert_eq!(r.cmd, Command::SetOutput { output: "alsa_output.x".into() });
    }

    #[test]
    fn old_state_without_mixer_still_parses() {
        let j = r#"{"type":"state","volume":null,"displays":[],"media":null,"stats":{"cpu":0.0,"mem_used":0,"mem_total":0,"uptime":0,"battery":null}}"#;
        assert!(matches!(serde_json::from_str::<Event>(j).unwrap(), Event::State(s) if s.apps.is_empty()));
    }

    #[test]
    fn hello_mode_names() {
        assert_eq!(serde_json::to_string(&HelloMode::PairQr).unwrap(), r#""pair_qr""#);
    }
}
