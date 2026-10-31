//! The machine the phone controls.

pub mod displays;
pub mod media;
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
        let (volume, displays, media) = tokio::join!(
            async { blocking(volume::get).await.ok().and_then(Result::ok) },
            self.displays.list(),
            media::now_playing(),
        );
        HostState { volume, displays, media, stats: self.stats.sample() }
    }

    /// Runs one phone command. `Hello`, `GetState` and `Ping` carry no action.
    pub async fn run(&self, cmd: &Command) -> Result<()> {
        match cmd {
            Command::Hello | Command::GetState | Command::Ping => Ok(()),
            Command::SetVolume { level } => {
                let l = *level;
                blocking(move || volume::set_level(l)).await?
            }
            Command::SetMute { muted } => {
                let m = *muted;
                blocking(move || volume::set_mute(m)).await?
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
