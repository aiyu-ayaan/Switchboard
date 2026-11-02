//! Now playing and transport controls: seek, shuffle, repeat, which player to
//! control, and its cover art.

use anyhow::{Result, anyhow, bail};
use sha2::Digest;
use switchboard_core::protocol::{Media, MediaAction, MediaPlayer, Repeat};

/// What the state poll needs from the players.
#[derive(Default)]
pub struct Now {
    pub media: Option<Media>,
    pub players: Vec<MediaPlayer>,
    /// Where the cover of `media` can be fetched; the phone only ever sees its hash.
    pub art_url: Option<String>,
}

/// Short stable id for a cover URL, so the phone refetches only when it changes.
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
fn art_id(url: &str) -> String {
    sha2::Sha256::digest(url.as_bytes()).iter().take(6).map(|b| format!("{b:02x}")).collect()
}

fn percent_decode(s: &str) -> Vec<u8> {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() {
            if let Ok(v) = u8::from_str_radix(&s[i + 1..i + 3], 16) {
                out.push(v);
                i += 3;
                continue;
            }
        }
        out.push(b[i]);
        i += 1;
    }
    out
}

/// A cover must stay small enough to travel in one frame.
const MAX_ART: usize = 512 * 1024;

fn is_public(addr: &std::net::IpAddr) -> bool {
    match addr {
        std::net::IpAddr::V4(a) => !(a.is_private() || a.is_loopback() || a.is_link_local() || a.is_unspecified() || a.is_broadcast() || a.octets()[0] == 100 && (64..128).contains(&a.octets()[1])),
        std::net::IpAddr::V6(a) => !(a.is_loopback() || a.is_unspecified() || (a.segments()[0] & 0xfe00) == 0xfc00 || (a.segments()[0] & 0xffc0) == 0xfe80),
    }
}

/// Cover bytes from a `file://` or public `http(s)://` URL. Blocking.
pub fn fetch_art(url: &str) -> Result<Vec<u8>> {
    use std::io::Read;
    if let Some(path) = url.strip_prefix("file://") {
        let path = String::from_utf8(percent_decode(path))?;
        let mut f = std::fs::File::open(&path)?;
        let mut buf = Vec::new();
        f.by_ref().take(MAX_ART as u64 + 1).read_to_end(&mut buf)?;
        if buf.len() > MAX_ART {
            bail!("cover is too large");
        }
        return Ok(buf);
    }
    let rest = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://")).ok_or_else(|| anyhow!("unsupported cover address"))?;
    // The address comes from whatever the player (or a web page) put in its
    // metadata: never fetch from this machine's own network.
    let authority = rest.split(['/', '?', '#']).next().unwrap_or("");
    let host_port = if authority.contains(':') && !authority.starts_with('[') { authority.to_owned() } else if url.starts_with("https") { format!("{authority}:443") } else { format!("{authority}:80") };
    use std::net::ToSocketAddrs;
    let addrs: Vec<_> = host_port.to_socket_addrs()?.collect();
    if addrs.is_empty() || !addrs.iter().all(|a| is_public(&a.ip())) {
        bail!("cover is not on the public internet");
    }
    let resp = ureq::AgentBuilder::new().timeout(std::time::Duration::from_secs(5)).redirects(0).build().get(url).call()?;
    let mut buf = Vec::new();
    resp.into_reader().take(MAX_ART as u64 + 1).read_to_end(&mut buf)?;
    if buf.len() > MAX_ART {
        bail!("cover is too large");
    }
    Ok(buf)
}

#[cfg(target_os = "linux")]
mod imp {
    use super::*;
    use std::collections::HashMap;
    use zbus::zvariant::{ObjectPath, OwnedObjectPath, OwnedValue, Value};

    const PREFIX: &str = "org.mpris.MediaPlayer2.";
    const PATH: &str = "/org/mpris/MediaPlayer2";
    const ROOT: &str = "org.mpris.MediaPlayer2";
    const PLAYER: &str = "org.mpris.MediaPlayer2.Player";

    async fn prop<T: TryFrom<OwnedValue>>(c: &zbus::Connection, dest: &str, iface: &str, name: &str) -> Option<T> {
        let p = zbus::fdo::PropertiesProxy::builder(c).destination(dest).ok()?.path(PATH).ok()?.build().await.ok()?;
        let v = p.get(zbus::names::InterfaceName::try_from(iface).ok()?, name).await.ok()?;
        T::try_from(v).ok()
    }

    async fn set_prop(c: &zbus::Connection, dest: &str, name: &str, value: Value<'_>) -> Result<()> {
        let p = zbus::fdo::PropertiesProxy::builder(c).destination(dest)?.path(PATH)?.build().await?;
        p.set(zbus::names::InterfaceName::try_from(PLAYER)?, name, value).await?;
        Ok(())
    }

    fn int(v: &OwnedValue) -> Option<i64> {
        let c = || v.try_clone().ok();
        i64::try_from(c()?).ok().or_else(|| u64::try_from(c()?).ok().map(|n| n as i64)).or_else(|| i32::try_from(c()?).ok().map(i64::from)).or_else(|| u32::try_from(c()?).ok().map(i64::from))
    }

    fn text(v: &OwnedValue) -> Option<String> {
        String::try_from(v.try_clone().ok()?).ok()
    }

    /// Every MPRIS player with its display name and playback status.
    async fn players(c: &zbus::Connection) -> Vec<(String, String, String)> {
        let Ok(reply) = c.call_method(Some("org.freedesktop.DBus"), "/org/freedesktop/DBus", Some("org.freedesktop.DBus"), "ListNames", &()).await else { return vec![] };
        let Ok(names) = reply.body().deserialize::<Vec<String>>() else { return vec![] };
        let mut out = Vec::new();
        for n in names.into_iter().filter(|n| n.starts_with(PREFIX)) {
            let status: String = prop(c, &n, PLAYER, "PlaybackStatus").await.unwrap_or_default();
            let name = prop::<String>(c, &n, ROOT, "Identity").await.unwrap_or_else(|| n.trim_start_matches(PREFIX).split('.').next().unwrap_or("Player").to_owned());
            out.push((n, name, status));
        }
        out
    }

    /// The player to control: the one the user chose if it is still there,
    /// else one that is playing, else one that is paused, else any.
    fn pick<'a>(all: &'a [(String, String, String)], selected: Option<&str>) -> Option<&'a (String, String, String)> {
        if let Some(s) = selected.and_then(|s| all.iter().find(|p| p.0 == s)) {
            return Some(s);
        }
        all.iter().min_by_key(|p| match p.2.as_str() {
            "Playing" => 0,
            "Paused" => 1,
            _ => 2,
        })
    }

    pub async fn now_playing(selected: Option<String>) -> Now {
        let Ok(c) = zbus::Connection::session().await else { return Now::default() };
        let all = players(&c).await;
        let list = all.iter().map(|p| MediaPlayer { id: p.0.clone(), name: p.1.clone(), playing: p.2 == "Playing" }).collect();
        let Some((bus, name, status)) = pick(&all, selected.as_deref()) else { return Now { players: list, ..Now::default() } };
        let meta: HashMap<String, OwnedValue> = prop(&c, bus, PLAYER, "Metadata").await.unwrap_or_default();
        let artist = meta
            .get("xesam:artist")
            .and_then(|v| Vec::<String>::try_from(v.try_clone().ok()?).ok())
            .map(|a| a.join(", "))
            .or_else(|| meta.get("xesam:artist").and_then(text))
            .unwrap_or_default();
        let art_url = meta.get("mpris:artUrl").and_then(text).filter(|u| !u.is_empty());
        let repeat = prop::<String>(&c, bus, PLAYER, "LoopStatus").await.map(|s| match s.as_str() {
            "Track" => Repeat::Track,
            "Playlist" => Repeat::All,
            _ => Repeat::Off,
        });
        let micros = |v: Option<i64>| v.filter(|n| *n >= 0).map(|n| (n / 1000) as u64);
        let media = Media {
            id: bus.clone(),
            player: name.clone(),
            title: meta.get("xesam:title").and_then(text).unwrap_or_default(),
            artist,
            playing: status == "Playing",
            position: micros(prop::<i64>(&c, bus, PLAYER, "Position").await),
            length: micros(meta.get("mpris:length").and_then(int)),
            can_seek: prop::<bool>(&c, bus, PLAYER, "CanSeek").await.unwrap_or(false),
            shuffle: prop::<bool>(&c, bus, PLAYER, "Shuffle").await,
            repeat,
            art: art_url.as_deref().map(art_id),
        };
        Now { media: Some(media), players: list, art_url }
    }

    async fn target(c: &zbus::Connection, selected: Option<&str>) -> Result<String> {
        let all = players(c).await;
        pick(&all, selected).map(|p| p.0.clone()).ok_or_else(|| anyhow!("nothing is playing"))
    }

    pub async fn control(selected: Option<String>, action: MediaAction) -> Result<()> {
        let c = zbus::Connection::session().await?;
        let bus = target(&c, selected.as_deref()).await?;
        let method = match action {
            MediaAction::PlayPause => "PlayPause",
            MediaAction::Next => "Next",
            MediaAction::Previous => "Previous",
        };
        c.call_method(Some(bus.as_str()), PATH, Some(PLAYER), method, &()).await?;
        Ok(())
    }

    pub async fn seek(selected: Option<String>, position_ms: u64) -> Result<()> {
        let c = zbus::Connection::session().await?;
        let bus = target(&c, selected.as_deref()).await?;
        if !prop::<bool>(&c, &bus, PLAYER, "CanSeek").await.unwrap_or(false) {
            bail!("this player cannot seek");
        }
        let micros = (position_ms.min(i64::MAX as u64 / 1000) * 1000) as i64;
        let meta: HashMap<String, OwnedValue> = prop(&c, &bus, PLAYER, "Metadata").await.unwrap_or_default();
        let track = meta.get("mpris:trackid").and_then(|v| OwnedObjectPath::try_from(v.try_clone().ok()?).ok());
        match track {
            Some(t) => {
                c.call_method(Some(bus.as_str()), PATH, Some(PLAYER), "SetPosition", &(ObjectPath::from(&t), micros)).await?;
            }
            None => {
                let now = prop::<i64>(&c, &bus, PLAYER, "Position").await.unwrap_or(0);
                c.call_method(Some(bus.as_str()), PATH, Some(PLAYER), "Seek", &(micros - now,)).await?;
            }
        }
        Ok(())
    }

    pub async fn set_shuffle(selected: Option<String>, on: bool) -> Result<()> {
        let c = zbus::Connection::session().await?;
        let bus = target(&c, selected.as_deref()).await?;
        set_prop(&c, &bus, "Shuffle", Value::from(on)).await
    }

    pub async fn set_repeat(selected: Option<String>, mode: Repeat) -> Result<()> {
        let c = zbus::Connection::session().await?;
        let bus = target(&c, selected.as_deref()).await?;
        let s = match mode {
            Repeat::Off => "None",
            Repeat::Track => "Track",
            Repeat::All => "Playlist",
        };
        set_prop(&c, &bus, "LoopStatus", Value::from(s)).await
    }
}

#[cfg(windows)]
mod imp {
    use super::*;
    use windows::Foundation::TimeSpan;
    use windows::Media::Control::{
        GlobalSystemMediaTransportControlsSession as Session, GlobalSystemMediaTransportControlsSessionManager as Manager,
        GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
    };
    use windows::Media::MediaPlaybackAutoRepeatMode as Auto;

    fn app_name(s: &Session) -> String {
        let app = s.SourceAppUserModelId().map(|h| h.to_string()).unwrap_or_default();
        app.rsplit(['!', '\\', '.']).find(|p| !p.is_empty() && *p != "exe").unwrap_or("Player").to_owned()
    }

    fn app_id(s: &Session) -> String {
        s.SourceAppUserModelId().map(|h| h.to_string()).unwrap_or_default()
    }

    fn chosen(mgr: &Manager, selected: &Option<String>) -> Option<Session> {
        if let Some(sel) = selected {
            if let Ok(all) = mgr.GetSessions() {
                if let Some(s) = all.into_iter().find(|s| &app_id(s) == sel) {
                    return Some(s);
                }
            }
        }
        mgr.GetCurrentSession().ok()
    }

    const TICKS_PER_MS: i64 = 10_000;

    pub async fn now_playing(selected: Option<String>) -> Now {
        tokio::task::spawn_blocking(move || -> Option<Now> {
            let mgr = Manager::RequestAsync().ok()?.get().ok()?;
            let players: Vec<MediaPlayer> = mgr
                .GetSessions()
                .ok()?
                .into_iter()
                .map(|s| MediaPlayer {
                    playing: s.GetPlaybackInfo().ok().and_then(|i| i.PlaybackStatus().ok()) == Some(Status::Playing),
                    name: app_name(&s),
                    id: app_id(&s),
                })
                .collect();
            let s = chosen(&mgr, &selected)?;
            let props = s.TryGetMediaPropertiesAsync().ok()?.get().ok()?;
            let info = s.GetPlaybackInfo().ok()?;
            let timeline = s.GetTimelineProperties().ok();
            let ms = |t: TimeSpan| (t.Duration / TICKS_PER_MS).max(0) as u64;
            let length = timeline.as_ref().and_then(|t| Some(ms(t.EndTime().ok()?) - ms(t.StartTime().ok()?))).filter(|n| *n > 0);
            let media = Media {
                id: app_id(&s),
                player: app_name(&s),
                title: props.Title().map(|h| h.to_string()).unwrap_or_default(),
                artist: props.Artist().map(|h| h.to_string()).unwrap_or_default(),
                playing: info.PlaybackStatus().ok()? == Status::Playing,
                position: timeline.as_ref().and_then(|t| t.Position().ok()).map(ms),
                length,
                can_seek: info.Controls().ok().and_then(|c| c.IsPlaybackPositionEnabled().ok()).unwrap_or(false),
                shuffle: info.IsShuffleActive().ok().and_then(|r| r.Value().ok()),
                repeat: info.AutoRepeatMode().ok().and_then(|r| r.Value().ok()).map(|m| match m {
                    Auto::Track => Repeat::Track,
                    Auto::List => Repeat::All,
                    _ => Repeat::Off,
                }),
                art: None,
            };
            Some(Now { media: Some(media), players, art_url: None })
        })
        .await
        .ok()
        .flatten()
        .unwrap_or_default()
    }

    fn with_session<T: Send + 'static>(selected: Option<String>, f: impl FnOnce(Session) -> Result<T> + Send + 'static) -> tokio::task::JoinHandle<Result<T>> {
        tokio::task::spawn_blocking(move || {
            let mgr = Manager::RequestAsync()?.get()?;
            f(chosen(&mgr, &selected).ok_or_else(|| anyhow!("nothing is playing"))?)
        })
    }

    fn accepted(ok: bool) -> Result<()> {
        if ok { Ok(()) } else { Err(anyhow!("the player refused")) }
    }

    pub async fn control(selected: Option<String>, action: MediaAction) -> Result<()> {
        with_session(selected, move |s| {
            accepted(match action {
                MediaAction::PlayPause => s.TryTogglePlayPauseAsync()?.get()?,
                MediaAction::Next => s.TrySkipNextAsync()?.get()?,
                MediaAction::Previous => s.TrySkipPreviousAsync()?.get()?,
            })
        })
        .await?
    }

    pub async fn seek(selected: Option<String>, position_ms: u64) -> Result<()> {
        with_session(selected, move |s| accepted(s.TryChangePlaybackPositionAsync((position_ms as i64).saturating_mul(TICKS_PER_MS))?.get()?)).await?
    }

    pub async fn set_shuffle(selected: Option<String>, on: bool) -> Result<()> {
        with_session(selected, move |s| accepted(s.TryChangeShuffleActiveAsync(on)?.get()?)).await?
    }

    pub async fn set_repeat(selected: Option<String>, mode: Repeat) -> Result<()> {
        let m = match mode {
            Repeat::Off => Auto::None,
            Repeat::Track => Auto::Track,
            Repeat::All => Auto::List,
        };
        with_session(selected, move |s| accepted(s.TryChangeAutoRepeatModeAsync(m)?.get()?)).await?
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod imp {
    use super::*;
    pub async fn now_playing(_: Option<String>) -> Now { Now::default() }
    pub async fn control(_: Option<String>, _: MediaAction) -> Result<()> { bail!("unsupported") }
    pub async fn seek(_: Option<String>, _: u64) -> Result<()> { bail!("unsupported") }
    pub async fn set_shuffle(_: Option<String>, _: bool) -> Result<()> { bail!("unsupported") }
    pub async fn set_repeat(_: Option<String>, _: Repeat) -> Result<()> { bail!("unsupported") }
}

pub use imp::{control, now_playing, seek, set_repeat, set_shuffle};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cover_urls_are_decoded_and_hashed() {
        assert_eq!(percent_decode("/tmp/a%20b.png"), b"/tmp/a b.png");
        assert_eq!(percent_decode("100%"), b"100%");
        assert_eq!(art_id("x"), art_id("x"));
        assert_ne!(art_id("x"), art_id("y"));
    }

    #[test]
    fn covers_never_come_from_this_machines_network() {
        for u in ["http://127.0.0.1/a.png", "http://192.168.1.1/a.png", "http://localhost:8080/a", "https://[::1]/a", "ftp://x/a", "http://10.0.0.5/a"] {
            assert!(fetch_art(u).is_err(), "{u}");
        }
        assert!(is_public(&"8.8.8.8".parse().unwrap()));
        assert!(!is_public(&"100.64.0.1".parse().unwrap()));
    }

    #[test]
    fn local_cover_file_is_read_and_capped() {
        let dir = std::env::temp_dir().join(format!("sb-art-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("c ver.png");
        std::fs::write(&p, b"png").unwrap();
        assert_eq!(fetch_art(&format!("file://{}", p.to_str().unwrap().replace(' ', "%20"))).unwrap(), b"png");
        std::fs::write(&p, vec![0u8; MAX_ART + 1]).unwrap();
        assert!(fetch_art(&format!("file://{}", p.to_str().unwrap().replace(' ', "%20"))).is_err());
    }
}
