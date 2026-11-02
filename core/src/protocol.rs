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
    /// Jump to a point in the current track, in milliseconds.
    Seek { position: u64 },
    SetShuffle { on: bool },
    SetRepeat { mode: Repeat },
    /// Control this player (a `MediaPlayer::id`) instead of the automatic pick.
    SelectPlayer { player: String },
    /// The cover art of the current track, returned as the result's `data`
    /// (base64url image bytes).
    MediaArt,
    /// Turn one of the computer's quick settings on or off.
    SetToggle { toggle: Toggle, on: bool },
    /// Move the pointer by this many pixels.
    MouseMove { dx: i32, dy: i32 },
    MouseButton { button: MouseButton, action: ButtonAction },
    /// Scroll by whole steps; positive `dy` scrolls down.
    Scroll { dx: i32, dy: i32 },
    /// Press and release a named key, with the modifiers held.
    Key {
        key: KeyName,
        #[serde(default)]
        ctrl: bool,
        #[serde(default)]
        alt: bool,
        #[serde(default)]
        shift: bool,
        #[serde(default)]
        meta: bool,
    },
    /// A shortcut: one character with modifiers held (Ctrl+C, Alt+Tab is `Key`).
    Chord {
        ch: char,
        #[serde(default)]
        ctrl: bool,
        #[serde(default)]
        alt: bool,
        #[serde(default)]
        shift: bool,
        #[serde(default)]
        meta: bool,
    },
    /// Type this text as if on the keyboard.
    TypeText { text: String },
    /// The computer's clipboard text, returned as the result's `data`.
    ClipboardGet,
    /// Put text on the computer's clipboard.
    ClipboardSet { text: String },
    Power { action: PowerAction },
    Ping,
    /// The phone forgot this desktop; the desktop drops it too.
    Unpair,
    /// Start sending a file to the desktop. `xfer` is the phone's own id for
    /// it. Chunks follow in order, each waiting for its result.
    FileSend { xfer: String, name: String, size: u64 },
    /// Next piece of an upload; `data` is base64url, `offset` must be the
    /// number of bytes already sent.
    FileChunk { xfer: String, offset: u64, data: String },
    /// Upload complete (the desktop checks the size), or the phone is done
    /// with a file the desktop offered.
    FileDone { xfer: String },
    /// Abandon an upload, or decline a file the desktop offered.
    FileCancel { xfer: String },
    /// Pull the next piece of a file the desktop offered; the piece comes
    /// back as the result's `data`.
    FileRead { xfer: String, offset: u64, len: u32 },
}

/// Most bytes in one file chunk, before base64. Keeps a frame under the
/// WebSocket message limit.
pub const FILE_CHUNK: usize = 192 * 1024;

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
    Shutdown,
    Restart,
    Logout,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum MouseButton {
    Left,
    Right,
    Middle,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ButtonAction {
    Click,
    Press,
    Release,
}

/// Keys with no character of their own.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum KeyName {
    Enter,
    Backspace,
    Tab,
    Escape,
    Space,
    Delete,
    Home,
    End,
    PageUp,
    PageDown,
    Up,
    Down,
    Left,
    Right,
}

/// Longest text one `TypeText` may carry, in characters.
pub const MAX_TYPED: usize = 1000;

/// Quick settings a phone can flip.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Toggle {
    DarkMode,
    DoNotDisturb,
    Wifi,
    Bluetooth,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct ToggleState {
    pub toggle: Toggle,
    pub on: bool,
}

/// Longest clipboard text that travels in one frame, in bytes.
pub const MAX_CLIPBOARD: usize = 256 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Repeat {
    Off,
    Track,
    All,
}

/// Desktop to phone, inside the encrypted channel.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Event {
    Welcome { host_id: String, host_name: String, os: String },
    Result {
        id: u64,
        ok: bool,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        error: Option<String>,
        /// Payload of a request that returns bytes (`FileRead`), base64url.
        #[serde(default, skip_serializing_if = "Option::is_none")]
        data: Option<String>,
    },
    /// The desktop wants to send this phone a file. The phone may pull it
    /// with `FileRead` or turn it down with `FileCancel`.
    FileOffer { xfer: String, name: String, size: u64 },
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
    /// Every player that could be controlled, for switching between them.
    #[serde(default)]
    pub players: Vec<MediaPlayer>,
    /// Only the quick settings this computer can actually read and change.
    #[serde(default)]
    pub toggles: Vec<ToggleState>,
    /// Whether the computer lets phones use its keyboard and mouse.
    #[serde(default)]
    pub remote_input: bool,
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
pub struct MediaPlayer {
    pub id: String,
    pub name: String,
    pub playing: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Media {
    /// The `MediaPlayer::id` this is about.
    #[serde(default)]
    pub id: String,
    pub player: String,
    pub title: String,
    pub artist: String,
    pub playing: bool,
    /// Milliseconds into the track and its length, when the player says.
    #[serde(default)]
    pub position: Option<u64>,
    #[serde(default)]
    pub length: Option<u64>,
    #[serde(default)]
    pub can_seek: bool,
    #[serde(default)]
    pub shuffle: Option<bool>,
    #[serde(default)]
    pub repeat: Option<Repeat>,
    /// Changes when the cover changes; fetch the picture with `MediaArt`.
    #[serde(default)]
    pub art: Option<String>,
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
    fn file_messages() {
        let r: Request = serde_json::from_str(r#"{"id":4,"type":"file_chunk","xfer":"a","offset":0,"data":"AAE"}"#).unwrap();
        assert_eq!(r.cmd, Command::FileChunk { xfer: "a".into(), offset: 0, data: "AAE".into() });
        let e = Event::Result { id: 4, ok: true, error: None, data: Some("AAE".into()) };
        assert_eq!(serde_json::to_string(&e).unwrap(), r#"{"type":"result","id":4,"ok":true,"data":"AAE"}"#);
        let old: Event = serde_json::from_str(r#"{"type":"result","id":4,"ok":true}"#).unwrap();
        assert_eq!(old, Event::Result { id: 4, ok: true, error: None, data: None });
    }

    #[test]
    fn media_controls() {
        let r: Request = serde_json::from_str(r#"{"id":1,"type":"set_repeat","mode":"track"}"#).unwrap();
        assert_eq!(r.cmd, Command::SetRepeat { mode: Repeat::Track });
        let r: Request = serde_json::from_str(r#"{"id":2,"type":"seek","position":90000}"#).unwrap();
        assert_eq!(r.cmd, Command::Seek { position: 90000 });
        let m: Media = serde_json::from_str(r#"{"player":"x","title":"t","artist":"a","playing":true}"#).unwrap();
        assert_eq!((m.position, m.shuffle, m.can_seek), (None, None, false));
    }

    #[test]
    fn toggles_and_clipboard() {
        let r: Request = serde_json::from_str(r#"{"id":1,"type":"set_toggle","toggle":"do_not_disturb","on":true}"#).unwrap();
        assert_eq!(r.cmd, Command::SetToggle { toggle: Toggle::DoNotDisturb, on: true });
        let r: Request = serde_json::from_str(r#"{"id":2,"type":"clipboard_set","text":"hi"}"#).unwrap();
        assert_eq!(r.cmd, Command::ClipboardSet { text: "hi".into() });
        assert_eq!(serde_json::to_string(&Toggle::DarkMode).unwrap(), r#""dark_mode""#);
    }

    #[test]
    fn remote_input_requests() {
        let r: Request = serde_json::from_str(r#"{"id":1,"type":"mouse_move","dx":-3,"dy":8}"#).unwrap();
        assert_eq!(r.cmd, Command::MouseMove { dx: -3, dy: 8 });
        let r: Request = serde_json::from_str(r#"{"id":2,"type":"key","key":"page_down","ctrl":true}"#).unwrap();
        assert_eq!(r.cmd, Command::Key { key: KeyName::PageDown, ctrl: true, alt: false, shift: false, meta: false });
        let r: Request = serde_json::from_str(r#"{"id":3,"type":"mouse_button","button":"right","action":"click"}"#).unwrap();
        assert_eq!(r.cmd, Command::MouseButton { button: MouseButton::Right, action: ButtonAction::Click });
    }

    #[test]
    fn hello_mode_names() {
        assert_eq!(serde_json::to_string(&HelloMode::PairQr).unwrap(), r#""pair_qr""#);
    }
}
