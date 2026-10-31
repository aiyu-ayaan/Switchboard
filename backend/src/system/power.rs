//! Lock, sleep and display-off.

use anyhow::Result;
use switchboard_core::protocol::PowerAction;

#[cfg(target_os = "linux")]
pub fn run(action: PowerAction) -> Result<()> {
    use anyhow::{bail, Context};
    let (cmd, args): (&str, &[&str]) = match action {
        PowerAction::Lock => ("loginctl", &["lock-sessions"]),
        PowerAction::Sleep => ("systemctl", &["suspend"]),
        PowerAction::DisplayOff => {
            if std::env::var("XDG_SESSION_TYPE").as_deref() == Ok("wayland") {
                bail!("turning the display off is not supported on Wayland yet");
            }
            ("xset", &["dpms", "force", "off"])
        }
    };
    let st = std::process::Command::new(cmd).args(args).status().with_context(|| format!("{cmd} is not available"))?;
    if !st.success() {
        bail!("{cmd} failed");
    }
    Ok(())
}

#[cfg(windows)]
pub fn run(action: PowerAction) -> Result<()> {
    use windows::Win32::Foundation::{LPARAM, WPARAM};
    use windows::Win32::System::Power::SetSuspendState;
    use windows::Win32::System::Shutdown::LockWorkStation;
    use windows::Win32::UI::WindowsAndMessaging::{HWND_BROADCAST, SC_MONITORPOWER, SendMessageW, WM_SYSCOMMAND};
    unsafe {
        match action {
            PowerAction::Lock => LockWorkStation()?,
            PowerAction::Sleep => {
                if !SetSuspendState(false, false, false).as_bool() {
                    anyhow::bail!("sleep was refused");
                }
            }
            PowerAction::DisplayOff => {
                SendMessageW(HWND_BROADCAST, WM_SYSCOMMAND, WPARAM(SC_MONITORPOWER as usize), LPARAM(2));
            }
        }
    }
    Ok(())
}

#[cfg(not(any(target_os = "linux", windows)))]
pub fn run(_: PowerAction) -> Result<()> {
    anyhow::bail!("unsupported platform")
}
