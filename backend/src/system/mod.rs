//! The machine the phone controls.

pub mod clipboard;
pub mod displays;
pub mod input;
pub mod media;
pub mod mixer;
pub mod power;
pub mod stats;
pub mod toggles;
pub mod volume;

use anyhow::{Result, anyhow};
use switchboard_core::protocol::{Command, HostState};

#[derive(Default)]
pub struct System {
    pub displays: displays::Displays,
    stats: stats::Sampler,
    /// The player the user picked; `None` follows whichever is playing.
    player: std::sync::Mutex<Option<String>>,
    /// Where the cover of the track in the last snapshot lives.
    art_url: std::sync::Mutex<Option<String>>,
    clipboard: std::sync::Arc<clipboard::Clip>,
    /// Whether phones may use the keyboard and mouse; off until the person at the computer allows it.
    pub remote_input: std::sync::atomic::AtomicBool,
    /// Quick settings are read by running programs, so they are cached for a few seconds.
    toggles: std::sync::Mutex<Option<(std::time::Instant, Vec<switchboard_core::protocol::ToggleState>)>>,
}

const TOGGLES_FRESH: std::time::Duration = std::time::Duration::from_secs(5);

async fn blocking<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> Result<T> {
    tokio::task::spawn_blocking(f).await.map_err(|e| anyhow!("task failed: {e}"))
}

impl System {
    pub async fn snapshot(&self) -> HostState {
        let selected = self.player.lock().unwrap().clone();
        let (volume, outputs, apps, displays, now, toggles) = tokio::join!(
            async { blocking(volume::get).await.ok().and_then(Result::ok) },
            async { blocking(mixer::outputs).await.ok().and_then(Result::ok).unwrap_or_default() },
            async { blocking(mixer::apps).await.ok().and_then(Result::ok).unwrap_or_default() },
            self.displays.list(),
            media::now_playing(selected),
            self.toggles(),
        );
        *self.art_url.lock().unwrap() = now.art_url;
        HostState { volume, outputs, apps, displays, media: now.media, players: now.players, toggles, remote_input: self.remote_input.load(std::sync::atomic::Ordering::Relaxed), stats: self.stats.sample() }
    }

    async fn toggles(&self) -> Vec<switchboard_core::protocol::ToggleState> {
        if let Some((at, list)) = self.toggles.lock().unwrap().as_ref() {
            if at.elapsed() < TOGGLES_FRESH {
                return list.clone();
            }
        }
        let list = blocking(toggles::list).await.unwrap_or_default();
        *self.toggles.lock().unwrap() = Some((std::time::Instant::now(), list.clone()));
        list
    }

    /// Runs one phone command; the result's `data`, if the command returns any.
    pub async fn run(&self, cmd: &Command) -> Result<Option<String>> {
        match cmd {
            Command::MediaArt => {
                let url = self.art_url.lock().unwrap().clone().ok_or_else(|| anyhow!("no cover"))?;
                let bytes = blocking(move || media::fetch_art(&url)).await??;
                Ok(Some(switchboard_core::b64(&bytes)))
            }
            Command::ClipboardGet => {
                let c = self.clipboard.clone();
                Ok(Some(blocking(move || c.get()).await??))
            }
            _ => self.run_unit(cmd).await.map(|()| None),
        }
    }

    /// `Hello`, `GetState` and `Ping` carry no action.
    async fn run_unit(&self, cmd: &Command) -> Result<()> {
        let selected = || self.player.lock().unwrap().clone();
        match cmd {
            // Unpair and the file commands are handled by the session, which knows the device.
            Command::Hello | Command::GetState | Command::Ping | Command::Unpair => Ok(()),
            Command::FileSend { .. } | Command::FileChunk { .. } | Command::FileDone { .. } | Command::FileCancel { .. } | Command::FileRead { .. } => {
                Err(anyhow!("file commands run in the session"))
            }
            Command::SetVolume { level } => {
                let l = *level;
                blocking(move || volume::set_level(l)).await?
            }
            Command::SetMute { muted } => {
                let m = *muted;
                blocking(move || volume::set_mute(m)).await?
            }
            Command::SetOutput { output } => {
                let o = output.clone();
                blocking(move || mixer::set_output(&o)).await?
            }
            Command::SetAppVolume { app, level } => {
                let (a, l) = (app.clone(), *level);
                blocking(move || mixer::set_app_volume(&a, l)).await?
            }
            Command::SetAppMute { app, muted } => {
                let (a, m) = (app.clone(), *muted);
                blocking(move || mixer::set_app_mute(&a, m)).await?
            }
            Command::SetBrightness { display, level } => self.displays.set(display, displays::VCP_BRIGHTNESS, *level).await,
            Command::SetContrast { display, level } => self.displays.set(display, displays::VCP_CONTRAST, *level).await,
            Command::Media { action } => media::control(selected(), *action).await,
            Command::Seek { position } => media::seek(selected(), *position).await,
            Command::SetShuffle { on } => media::set_shuffle(selected(), *on).await,
            Command::SetRepeat { mode } => media::set_repeat(selected(), *mode).await,
            Command::SelectPlayer { player } => {
                *self.player.lock().unwrap() = Some(player.clone());
                Ok(())
            }
            Command::MediaArt | Command::ClipboardGet => Ok(()),
            Command::MouseMove { .. } | Command::MouseButton { .. } | Command::Scroll { .. } | Command::Key { .. } | Command::Chord { .. } | Command::TypeText { .. } => {
                anyhow::ensure!(self.remote_input.load(std::sync::atomic::Ordering::Relaxed), "Remote keyboard and mouse is turned off on the computer");
                let op = match cmd {
                    Command::MouseMove { dx, dy } => {
                        let (dx, dy) = input::clamp_move(*dx, *dy);
                        input::Op::Move(dx, dy)
                    }
                    Command::MouseButton { button, action } => input::Op::Button(*button, *action),
                    Command::Scroll { dx, dy } => input::Op::Scroll((*dx).clamp(-50, 50), (*dy).clamp(-50, 50)),
                    Command::Key { key, ctrl, alt, shift, meta } => input::Op::Key { key: *key, ctrl: *ctrl, alt: *alt, shift: *shift, meta: *meta },
                    Command::Chord { ch, ctrl, alt, shift, meta } => input::Op::Chord { ch: *ch, ctrl: *ctrl, alt: *alt, shift: *shift, meta: *meta },
                    Command::TypeText { text } => {
                        input::check_text(text)?;
                        input::Op::Text(text.clone())
                    }
                    _ => unreachable!(),
                };
                blocking(move || input::send(op)).await?
            }
            Command::SetToggle { toggle, on } => {
                let (t, o) = (*toggle, *on);
                let r = blocking(move || toggles::set(t, o)).await?;
                // Read the new state on the next snapshot, not from the cache.
                *self.toggles.lock().unwrap() = None;
                r
            }
            Command::ClipboardSet { text } => {
                let (c, t) = (self.clipboard.clone(), text.clone());
                blocking(move || c.set(&t)).await?
            }
            Command::Power { action } => {
                let a = *action;
                blocking(move || power::run(a)).await?
            }
        }
    }
}
