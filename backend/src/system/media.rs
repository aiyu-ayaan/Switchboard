//! Now playing and transport controls for whichever player is active.

use anyhow::Result;
use switchboard_core::protocol::{Media, MediaAction};

#[cfg(target_os = "linux")]
mod imp {
    use super::*;
    use anyhow::anyhow;
    use std::collections::HashMap;
    use zbus::zvariant::OwnedValue;

    const PREFIX: &str = "org.mpris.MediaPlayer2.";
    const PATH: &str = "/org/mpris/MediaPlayer2";
    const PLAYER: &str = "org.mpris.MediaPlayer2.Player";

    async fn prop<T: TryFrom<OwnedValue>>(c: &zbus::Connection, dest: &str, iface: &str, name: &str) -> Option<T> {
        let p = zbus::fdo::PropertiesProxy::builder(c).destination(dest).ok()?.path(PATH).ok()?.build().await.ok()?;
        let v = p.get(zbus::names::InterfaceName::try_from(iface).ok()?, name).await.ok()?;
        T::try_from(v).ok()
    }

    /// The player to show: one that is playing, else one that is paused,
    /// else any player at all.
    async fn pick(c: &zbus::Connection) -> Option<(String, String)> {
        let reply = c
            .call_method(Some("org.freedesktop.DBus"), "/org/freedesktop/DBus", Some("org.freedesktop.DBus"), "ListNames", &())
            .await
            .ok()?;
        let names: Vec<String> = reply.body().deserialize().ok()?;
        let mut best: Option<(u8, String, String)> = None;
        for n in names.into_iter().filter(|n| n.starts_with(PREFIX)) {
            let status: String = prop(c, &n, PLAYER, "PlaybackStatus").await.unwrap_or_default();
            let rank = match status.as_str() {
                "Playing" => 0,
                "Paused" => 1,
                _ => 2,
            };
            if best.as_ref().is_none_or(|b| rank < b.0) {
                best = Some((rank, n, status));
            }
        }
        best.map(|(_, n, s)| (n, s))
    }

    pub async fn now_playing() -> Option<Media> {
        let c = zbus::Connection::session().await.ok()?;
        let (name, status) = pick(&c).await?;
        let meta: HashMap<String, OwnedValue> = prop(&c, &name, PLAYER, "Metadata").await.unwrap_or_default();
        let title = meta.get("xesam:title").and_then(|v| String::try_from(v.try_clone().ok()?).ok()).unwrap_or_default();
        let artist = meta
            .get("xesam:artist")
            .and_then(|v| Vec::<String>::try_from(v.try_clone().ok()?).ok())
            .map(|a| a.join(", "))
            .unwrap_or_default();
        let player = prop::<String>(&c, &name, "org.mpris.MediaPlayer2", "Identity")
            .await
            .unwrap_or_else(|| name.trim_start_matches(PREFIX).split('.').next().unwrap_or("Player").to_owned());
        Some(Media { player, title, artist, playing: status == "Playing" })
    }

    pub async fn control(action: MediaAction) -> Result<()> {
        let c = zbus::Connection::session().await?;
        let (name, _) = pick(&c).await.ok_or_else(|| anyhow!("nothing is playing"))?;
        let method = match action {
            MediaAction::PlayPause => "PlayPause",
            MediaAction::Next => "Next",
            MediaAction::Previous => "Previous",
        };
        c.call_method(Some(name.as_str()), PATH, Some(PLAYER), method, &()).await?;
        Ok(())
    }
}

#[cfg(windows)]
mod imp {
    use super::*;
    use anyhow::anyhow;
    use windows::Media::Control::{
        GlobalSystemMediaTransportControlsSessionManager as Manager,
        GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
    };

    pub async fn now_playing() -> Option<Media> {
        tokio::task::spawn_blocking(|| -> Option<Media> {
            let mgr = Manager::RequestAsync().ok()?.get().ok()?;
            let s = mgr.GetCurrentSession().ok()?;
            let props = s.TryGetMediaPropertiesAsync().ok()?.get().ok()?;
            let playing = s.GetPlaybackInfo().ok()?.PlaybackStatus().ok()? == Status::Playing;
            let app = s.SourceAppUserModelId().map(|h| h.to_string()).unwrap_or_default();
            let player = app.rsplit(['!', '\\', '.']).find(|p| !p.is_empty() && *p != "exe").unwrap_or("Player").to_owned();
            Some(Media {
                player,
                title: props.Title().map(|h| h.to_string()).unwrap_or_default(),
                artist: props.Artist().map(|h| h.to_string()).unwrap_or_default(),
                playing,
            })
        })
        .await
        .ok()
        .flatten()
    }

    pub async fn control(action: MediaAction) -> Result<()> {
        tokio::task::spawn_blocking(move || -> Result<()> {
            let s = Manager::RequestAsync()?.get()?.GetCurrentSession().map_err(|_| anyhow!("nothing is playing"))?;
            let ok = match action {
                MediaAction::PlayPause => s.TryTogglePlayPauseAsync()?.get()?,
                MediaAction::Next => s.TrySkipNextAsync()?.get()?,
                MediaAction::Previous => s.TrySkipPreviousAsync()?.get()?,
            };
            if ok { Ok(()) } else { Err(anyhow!("the player refused")) }
        })
        .await?
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod imp {
    use super::*;
    pub async fn now_playing() -> Option<Media> { None }
    pub async fn control(_: MediaAction) -> Result<()> { anyhow::bail!("unsupported") }
}

pub use imp::{control, now_playing};
