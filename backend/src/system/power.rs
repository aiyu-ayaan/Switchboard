//! Lock, sleep and display-off.

use anyhow::Result;
use switchboard_core::protocol::PowerAction;

#[cfg(target_os = "linux")]
mod linux {
    use anyhow::{Result, bail};
    use std::process::{Command, Stdio};

    type Cmd = (&'static str, Vec<&'static str>);

    /// Wayland has no `xset`; each desktop has its own way to blank the panels.
    /// Candidates for the running desktop come first, the rest are fallbacks.
    pub(super) fn wayland_off(desktop: &str) -> Vec<Cmd> {
        let gnome: Cmd = (
            "gdbus",
            vec![
                "call", "--session", "--dest", "org.gnome.Mutter.DisplayConfig", "--object-path", "/org/gnome/Mutter/DisplayConfig",
                "--method", "org.freedesktop.DBus.Properties.Set", "org.gnome.Mutter.DisplayConfig", "PowerSaveMode", "<int32 3>",
            ],
        );
        let kde: Cmd = ("kscreen-doctor", vec!["--dpms", "off"]);
        let sway: Cmd = ("swaymsg", vec!["output * power off"]);
        let hypr: Cmd = ("hyprctl", vec!["dispatch", "dpms", "off"]);
        let d = desktop.to_ascii_lowercase();
        let mut all = vec![
            ("gnome", gnome),
            ("kde", kde),
            ("sway", sway),
            ("hyprland", hypr),
        ];
        // Stable sort: the matching desktop moves to the front.
        all.sort_by_key(|(name, _)| !d.contains(name));
        all.into_iter().map(|(_, c)| c).collect()
    }

    fn run(cmd: &str, args: &[&str]) -> bool {
        Command::new(cmd).args(args).stdout(Stdio::null()).stderr(Stdio::null()).status().is_ok_and(|s| s.success())
    }

    pub fn display_off() -> Result<()> {
        if std::env::var("XDG_SESSION_TYPE").as_deref() != Ok("wayland") {
            return if run("xset", &["dpms", "force", "off"]) { Ok(()) } else { bail!("xset failed") };
        }
        let desktop = std::env::var("XDG_CURRENT_DESKTOP").unwrap_or_default();
        if wayland_off(&desktop).iter().any(|(c, a)| run(c, a)) {
            return Ok(());
        }
        bail!("this Wayland desktop has no known way to turn the display off")
    }

    /// Ends this login session; the session id is set by logind for graphical logins.
    pub fn logout() -> Result<()> {
        match std::env::var("XDG_SESSION_ID") {
            Ok(id) if !id.is_empty() && id.bytes().all(|b| b.is_ascii_alphanumeric()) => run_simple("loginctl", &["terminate-session", &id]),
            _ => run_simple("loginctl", &["terminate-user", &std::env::var("USER").unwrap_or_default()]),
        }
    }

    pub fn run_simple(cmd: &str, args: &[&str]) -> Result<()> {
        if !run(cmd, args) {
            bail!("{cmd} failed");
        }
        Ok(())
    }

    #[cfg(test)]
    mod tests {
        use super::*;

        #[test]
        fn running_desktop_is_tried_first() {
            assert_eq!(wayland_off("KDE").first().unwrap().0, "kscreen-doctor");
            assert_eq!(wayland_off("ubuntu:GNOME").first().unwrap().0, "gdbus");
            assert_eq!(wayland_off("Hyprland").first().unwrap().0, "hyprctl");
            assert_eq!(wayland_off("").len(), 4, "unknown desktops try all");
        }
    }
}

#[cfg(target_os = "linux")]
pub fn run(action: PowerAction) -> Result<()> {
    match action {
        PowerAction::Lock => linux::run_simple("loginctl", &["lock-sessions"]),
        PowerAction::Sleep => linux::run_simple("systemctl", &["suspend"]),
        PowerAction::DisplayOff => linux::display_off(),
        PowerAction::Shutdown => linux::run_simple("systemctl", &["poweroff"]),
        PowerAction::Restart => linux::run_simple("systemctl", &["reboot"]),
        PowerAction::Logout => linux::logout(),
    }
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
            PowerAction::Shutdown | PowerAction::Restart | PowerAction::Logout => {
                let flag = match action {
                    PowerAction::Shutdown => "/s",
                    PowerAction::Restart => "/r",
                    _ => "/l",
                };
                let st = std::process::Command::new("shutdown").args([flag, "/t", "0"]).status()?;
                anyhow::ensure!(st.success(), "shutdown failed");
            }
        }
    }
    Ok(())
}

#[cfg(not(any(target_os = "linux", windows)))]
pub fn run(_: PowerAction) -> Result<()> {
    anyhow::bail!("unsupported platform")
}
