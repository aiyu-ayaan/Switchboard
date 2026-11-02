//! Quick settings a phone can flip: dark mode, do not disturb, Wi-Fi, Bluetooth.
//! Only the ones this computer can really read are offered.

use anyhow::Result;
use switchboard_core::protocol::{Toggle, ToggleState};

#[cfg(target_os = "linux")]
mod imp {
    use super::*;
    use anyhow::{Context, bail};
    use std::process::Command;

    fn out(cmd: &str, args: &[&str]) -> Option<String> {
        let o = Command::new(cmd).args(args).output().ok()?;
        o.status.success().then(|| String::from_utf8_lossy(&o.stdout).trim().to_owned())
    }

    fn run(cmd: &str, args: &[&str]) -> Result<()> {
        let o = Command::new(cmd).args(args).output().with_context(|| format!("{cmd} is not installed"))?;
        if !o.status.success() {
            bail!("{cmd} failed: {}", String::from_utf8_lossy(&o.stderr).trim());
        }
        Ok(())
    }

    /// `'prefer-dark'` from `gsettings get`.
    pub(super) fn parse_dark(s: &str) -> bool {
        s.contains("prefer-dark")
    }

    /// Banners off means "do not disturb" is on.
    pub(super) fn parse_banners(s: &str) -> bool {
        s.trim() == "false"
    }

    pub(super) fn parse_radio(s: &str) -> Option<bool> {
        match s.trim() {
            "enabled" => Some(true),
            "disabled" => Some(false),
            _ => None,
        }
    }

    /// First line of `rfkill -n -o SOFT list bluetooth`.
    pub(super) fn parse_rfkill(s: &str) -> Option<bool> {
        match s.lines().next()?.trim() {
            "unblocked" => Some(true),
            "blocked" => Some(false),
            _ => None,
        }
    }

    pub fn get(t: Toggle) -> Option<bool> {
        match t {
            Toggle::DarkMode => out("gsettings", &["get", "org.gnome.desktop.interface", "color-scheme"]).map(|s| parse_dark(&s)),
            Toggle::DoNotDisturb => out("gsettings", &["get", "org.gnome.desktop.notifications", "show-banners"]).map(|s| parse_banners(&s)),
            Toggle::Wifi => out("nmcli", &["radio", "wifi"]).and_then(|s| parse_radio(&s)),
            Toggle::Bluetooth => out("rfkill", &["-n", "-o", "SOFT", "list", "bluetooth"]).and_then(|s| parse_rfkill(&s)),
        }
    }

    pub fn set(t: Toggle, on: bool) -> Result<()> {
        match t {
            Toggle::DarkMode => run("gsettings", &["set", "org.gnome.desktop.interface", "color-scheme", if on { "prefer-dark" } else { "default" }]),
            Toggle::DoNotDisturb => run("gsettings", &["set", "org.gnome.desktop.notifications", "show-banners", if on { "false" } else { "true" }]),
            Toggle::Wifi => run("nmcli", &["radio", "wifi", if on { "on" } else { "off" }]),
            Toggle::Bluetooth => run("rfkill", &[if on { "unblock" } else { "block" }, "bluetooth"]),
        }
    }
}

#[cfg(windows)]
mod imp {
    use super::*;
    use anyhow::bail;
    use windows::Win32::Foundation::{LPARAM, WPARAM};
    use windows::Win32::UI::WindowsAndMessaging::{HWND_BROADCAST, SMTO_ABORTIFHUNG, SendMessageTimeoutW, WM_SETTINGCHANGE};
    use windows::core::w;

    const KEY: &str = r"HKCU\Software\Microsoft\Windows\CurrentVersion\Themes\Personalize";

    fn reg(args: &[&str]) -> Option<String> {
        let o = std::process::Command::new("reg").args(args).output().ok()?;
        o.status.success().then(|| String::from_utf8_lossy(&o.stdout).into_owned())
    }

    /// Light theme is value 1; the line ends `REG_DWORD    0x0`.
    pub(super) fn parse_light(s: &str) -> Option<bool> {
        let v = s.lines().find(|l| l.contains("AppsUseLightTheme"))?.split_whitespace().last()?;
        Some(v == "0x1")
    }

    // Windows has no supported switch for do not disturb, Wi-Fi or Bluetooth that
    // works without administrator rights, so only dark mode is offered.
    pub fn get(t: Toggle) -> Option<bool> {
        match t {
            Toggle::DarkMode => reg(&["query", KEY, "/v", "AppsUseLightTheme"]).and_then(|s| parse_light(&s)).map(|light| !light),
            _ => None,
        }
    }

    pub fn set(t: Toggle, on: bool) -> Result<()> {
        if t != Toggle::DarkMode {
            bail!("not supported on Windows");
        }
        let v = if on { "0" } else { "1" };
        for name in ["AppsUseLightTheme", "SystemUsesLightTheme"] {
            let st = std::process::Command::new("reg").args(["add", KEY, "/v", name, "/t", "REG_DWORD", "/d", v, "/f"]).status()?;
            anyhow::ensure!(st.success(), "could not change the theme");
        }
        // Tell running programs the theme changed.
        unsafe {
            SendMessageTimeoutW(HWND_BROADCAST, WM_SETTINGCHANGE, WPARAM(0), LPARAM(w!("ImmersiveColorSet").as_ptr() as isize), SMTO_ABORTIFHUNG, 2000, None);
        }
        Ok(())
    }
}

#[cfg(not(any(target_os = "linux", windows)))]
mod imp {
    use super::*;
    pub fn get(_: Toggle) -> Option<bool> { None }
    pub fn set(_: Toggle, _: bool) -> Result<()> { anyhow::bail!("unsupported platform") }
}

const ALL: [Toggle; 4] = [Toggle::DarkMode, Toggle::DoNotDisturb, Toggle::Wifi, Toggle::Bluetooth];

/// The quick settings this computer supports, with their current state.
pub fn list() -> Vec<ToggleState> {
    ALL.iter().filter_map(|t| imp::get(*t).map(|on| ToggleState { toggle: *t, on })).collect()
}

pub fn set(t: Toggle, on: bool) -> Result<()> {
    anyhow::ensure!(imp::get(t).is_some(), "this computer cannot change that");
    imp::set(t, on)
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use super::imp::*;

    #[test]
    fn gsettings_and_radio_output() {
        assert!(parse_dark("'prefer-dark'"));
        assert!(!parse_dark("'default'"));
        assert!(parse_banners("false\n"));
        assert!(!parse_banners("true"));
        assert_eq!(parse_radio("enabled\n"), Some(true));
        assert_eq!(parse_radio("disabled"), Some(false));
        assert_eq!(parse_radio("weird"), None);
        assert_eq!(parse_rfkill("blocked\n"), Some(false));
        assert_eq!(parse_rfkill("unblocked"), Some(true));
        assert_eq!(parse_rfkill(""), None);
    }
}
