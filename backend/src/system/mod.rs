//! The machine the phone controls.

pub mod displays;
pub mod media;
pub mod mixer;
pub mod power;
pub mod stats;
pub mod volume;

use anyhow::{Result, anyhow};
use switchboard_core::protocol::{Command, HostState};

#[derive(Default)]
pub struct System {
    pub displays: displays::Displays,
    stats: stats::Sampler,
}

async fn blocking<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> Result<T> {
    tokio::task::spawn_blocking(f).await.map_err(|e| anyhow!("task failed: {e}"))
}

impl System {
    pub async fn snapshot(&self) -> HostState {
        let (volume, outputs, apps, displays, media) = tokio::join!(
            async { blocking(volume::get).await.ok().and_then(Result::ok) },
            async { blocking(mixer::outputs).await.ok().and_then(Result::ok).unwrap_or_default() },
            async { blocking(mixer::apps).await.ok().and_then(Result::ok).unwrap_or_default() },
            self.displays.list(),
            media::now_playing(),
        );
        HostState { volume, outputs, apps, displays, media, stats: self.stats.sample() }
    }

    /// Runs one phone command. `Hello`, `GetState` and `Ping` carry no action.
    pub async fn run(&self, cmd: &Command) -> Result<()> {
        match cmd {
            // Unpair is handled by the session, which knows the device.
            Command::Hello | Command::GetState | Command::Ping | Command::Unpair => Ok(()),
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
            Command::Media { action } => media::control(*action).await,
            Command::Power { action } => {
                let a = *action;
                blocking(move || power::run(a)).await?
            }
        }
    }
}
