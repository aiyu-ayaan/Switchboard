#!/bin/sh
# Install, update or remove the Switchboard desktop app on Linux (x86_64), for this user, no root.
#
#   curl -fsSL https://raw.githubusercontent.com/aiyu-ayaan/Switchboard/master/scripts/install.sh | sh
#   ... | sh -s -- --pre              the newest build, alpha and beta included
#   ... | sh -s -- --version 1.1.7    a specific release
#   ... | sh -s -- --uninstall        remove it (pairings stay in ~/.config/Switchboard)
#
# The AppImage goes to ~/.local/share/switchboard, a place this user owns. It also gets a menu
# entry with the app's own icon and a `switchboard` command. Re-running it updates.
#
# It never touches ~/.config/Switchboard, where the host key and every pairing live, so
# installing, updating and uninstalling all keep your paired phones.
#
# POSIX sh: it is piped into whatever `sh` is, which on Debian and Ubuntu is dash.
set -eu

REPO="${SWITCHBOARD_REPO:-aiyu-ayaan/Switchboard}"
API="https://api.github.com/repos/${REPO}"
DATA_HOME="${XDG_DATA_HOME:-$HOME/.local/share}"
APP_DIR="$DATA_HOME/switchboard"
APP="$APP_DIR/Switchboard.AppImage"
STAMP="$APP_DIR/version"
BIN="$HOME/.local/bin/switchboard"
# Named like the entry inside the AppImage; StartupWMClass below is what ties the window to it.
DESKTOP_FILE="$DATA_HOME/applications/switchboard.desktop"
ICON_PATH="usr/share/icons/hicolor/512x512/apps/switchboard.png"
ICON_FILE="$DATA_HOME/icons/hicolor/512x512/apps/switchboard.png"
# Written by the app itself when "Start with system" is on; it names the AppImage's path.
AUTOSTART="${XDG_CONFIG_HOME:-$HOME/.config}/autostart/Switchboard.desktop"
# Where installers before 1.1.8 put the app, behind a wrapper script. Moved out of on install.
OLD_DIR="$HOME/.local/lib/switchboard"

VERSION=""
PRE=0
FORCE=0
FROM_FILE=""
UNINSTALL=0

die() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }
warn() { printf '%s\n' "$*" >&2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="${2:?--version needs a value, e.g. --version 1.1.7}"; VERSION="${VERSION#v}"; shift 2 ;;
    --version=*) VERSION="${1#*=}"; VERSION="${VERSION#v}"; shift ;;
    --pre | --prerelease) PRE=1; shift ;;
    --force) FORCE=1; shift ;;
    --from-file) FROM_FILE="${2:?--from-file needs a path}"; shift 2 ;;
    --uninstall | --remove) UNINSTALL=1; shift ;;
    -h | --help)
      # Spelled out: piped to a shell there is no file to read a header from.
      cat <<EOF
Install Switchboard on Linux from the released AppImage, for this user.

  install.sh [--version <x.y.z>] [--pre] [--force] [--from-file <AppImage>]
  install.sh --uninstall

  --version <x.y.z>   install that release instead of the newest stable one
  --pre               take the newest build, alpha and beta included
  --force             reinstall even if that version is already here
  --from-file PATH    install a local AppImage instead of downloading one
  --uninstall         remove the app, keeping your pairings

Run it again to update. ~/.config/Switchboard (your host key and pairings) is never touched.
EOF
      exit 0 ;;
    *) die "unknown option '$1' (try --help)" ;;
  esac
done

[ "$(id -u)" -ne 0 ] || die "run this as the user who will use Switchboard, not as root (no sudo)."
[ "$(uname -s)" = Linux ] || die "this installs the Linux AppImage; $(uname -s) is not Linux."

refresh_menus() {
  command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q "$DATA_HOME/applications" 2>/dev/null || true
  command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q -t "$DATA_HOME/icons/hicolor" 2>/dev/null || true
}

# The layout older installers wrote: the AppImage, a sandbox wrapper and a version stamp in
# ~/.local/lib/switchboard, with `switchboard` linked to the wrapper. The icon and the menu entry
# were already where this script writes them, so they are simply overwritten.
remove_old_layout() {
  [ -d "$OLD_DIR" ] || return 0
  if [ -L "$BIN" ]; then
    case "$(readlink "$BIN")" in "$OLD_DIR"/*) rm -f "$BIN" ;; esac
  fi
  rm -rf "$OLD_DIR"
  rmdir "$HOME/.local/lib" 2>/dev/null || true
}

# --- Uninstall ------------------------------------------------------------------------------

if [ "$UNINSTALL" -eq 1 ]; then
  [ -e "$APP" ] || [ -e "$DESKTOP_FILE" ] || [ -d "$OLD_DIR" ] || die "Switchboard is not installed for this user."
  rm -f "$APP" "$APP.part" "$STAMP" "$DESKTOP_FILE" "$ICON_FILE"
  # Only our own link: a `switchboard` on PATH that came from the .deb is not ours to delete.
  if [ -L "$BIN" ] && [ "$(readlink "$BIN")" = "$APP" ]; then rm -f "$BIN"; fi
  # A login entry that would start a file that is gone. Left alone if it points anywhere else.
  if [ -f "$AUTOSTART" ] && grep -qF -e "$APP" -e "$OLD_DIR" "$AUTOSTART"; then rm -f "$AUTOSTART"; fi
  remove_old_layout
  rmdir "$APP_DIR" 2>/dev/null || true
  refresh_menus
  echo "Switchboard is removed. If it is still running, quit it from the tray or with: pkill -x switchboard"
  echo "Your pairings are kept in ~/.config/Switchboard. To forget every paired phone as well:"
  echo "  rm -rf ~/.config/Switchboard"
  exit 0
fi

# --- Download -------------------------------------------------------------------------------

case "$(uname -m)" in
  x86_64 | amd64) ;;
  *) die "releases carry x86_64 builds only, and this is $(uname -m). Build from source: https://github.com/${REPO}/blob/master/CONTRIBUTING.md" ;;
esac

mkdir -p "$APP_DIR"
trap 'rm -f "$APP.part"' EXIT INT TERM

if [ -n "$FROM_FILE" ]; then
  cp "$FROM_FILE" "$APP.part" || die "cannot read $FROM_FILE"
  label="$(basename "$FROM_FILE")"
  # Switchboard-1.2.3.AppImage -> 1.2.3; anything else is installed without a version.
  case "$label" in Switchboard-*.AppImage) label="${label#Switchboard-}"; label="${label%.AppImage}" ;; esac
else
  if command -v curl >/dev/null 2>&1; then
    fetch() { curl -fsSL --retry 3 --proto '=https' --tlsv1.2 "$1"; }
    # A progress bar only where someone is watching: in a log it is a wall of carriage returns.
    if [ -t 2 ]; then
      fetch_to() { curl -fL --retry 3 --progress-bar --proto '=https' --tlsv1.2 -o "$2" "$1"; }
    else
      fetch_to() { curl -fsSL --retry 3 --proto '=https' --tlsv1.2 -o "$2" "$1"; }
    fi
  elif command -v wget >/dev/null 2>&1; then
    fetch() { wget -qO- "$1"; }
    fetch_to() { wget -q --show-progress -O "$2" "$1"; }
  else
    die "curl or wget is needed to download Switchboard."
  fi

  if [ -n "$VERSION" ]; then
    api_path="/releases/tags/v${VERSION}"; wanted="v${VERSION}"
  elif [ "$PRE" -eq 1 ]; then
    # Newest first, so the first AppImage in the list is the newest build of any channel.
    api_path="/releases?per_page=20"; wanted="the newest build"
  else
    api_path="/releases/latest"; wanted="the latest stable release"
  fi

  echo "Looking up Switchboard releases..."
  releases="$(fetch "${API}${api_path}")" \
    || die "GitHub returned nothing for ${wanted}. See https://github.com/${REPO}/releases (only pre-releases so far? try --pre)"
  url="$(printf '%s\n' "$releases" | grep -oE '"https://[^"]*/Switchboard-[0-9][^"/]*\.AppImage"' | head -n 1 | tr -d '"')"
  [ -n "$url" ] || die "that release has no AppImage attached: https://github.com/${REPO}/releases"
  label="${url##*/Switchboard-}"
  label="${label%.AppImage}"

  if [ "$FORCE" -eq 0 ] && [ -x "$APP" ] && [ -f "$STAMP" ] && [ "$(cat "$STAMP")" = "$label" ]; then
    remove_old_layout
    echo "Switchboard $label is already installed. Pass --force to reinstall it."
    exit 0
  fi

  echo "Downloading Switchboard $label..."
  fetch_to "$url" "$APP.part" || die "the download failed. Nothing was changed."
fi

# An error page saved under the right name must not become the app.
[ "$(head -c 4 "$APP.part" | od -An -c | tr -d ' \n')" = '177ELF' ] || die "that is not an AppImage. Nothing was changed."
chmod 755 "$APP.part"
# A rename within one directory: a running copy keeps running from the old file until restarted.
mv -f "$APP.part" "$APP"
trap - EXIT INT TERM

# --- Icon, menu entry, command --------------------------------------------------------------

# The icon comes out of the AppImage itself, so it is always this build's. --appimage-extract
# needs no FUSE, so this works even where the AppImage cannot run yet.
tmp="$(mktemp -d)"
if (cd "$tmp" && "$APP" --appimage-extract "$ICON_PATH" >/dev/null 2>&1) && [ -f "$tmp/squashfs-root/$ICON_PATH" ]; then
  mkdir -p "$(dirname "$ICON_FILE")"
  cp "$tmp/squashfs-root/$ICON_PATH" "$ICON_FILE"
  icon="$ICON_FILE"
else
  warn "warning: could not read the icon out of the AppImage; the menu entry will use a generic one."
  icon=switchboard
fi
rm -rf "$tmp"

# Since 1.1.6 the AppImage drops Chromium's sandbox by itself where the kernel restricts user
# namespaces (Ubuntu 24.04 and derivatives). Older builds abort there without --no-sandbox.
sandbox_flag=""
case "$label" in 0.* | 1.0.* | 1.1.[0-5] | 1.1.[0-5]-*) sandbox_flag=" --no-sandbox" ;; esac

# Same fields as the entry electron-builder writes into the AppImage. The icon is an absolute
# path so it shows without waiting for the icon theme cache, and whatever icon theme is active
# cannot substitute its own.
mkdir -p "$(dirname "$DESKTOP_FILE")"
cat >"$DESKTOP_FILE" <<ENTRY
[Desktop Entry]
Type=Application
Name=Switchboard
GenericName=Remote desktop control
Comment=Control your desktop from your phone
Exec="$APP"$sandbox_flag %U
TryExec=$APP
Icon=$icon
Terminal=false
Categories=Utility;
Keywords=remote;control;display;brightness;volume;media;
StartupWMClass=Switchboard
ENTRY
chmod 644 "$DESKTOP_FILE"

remove_old_layout
mkdir -p "$(dirname "$BIN")"
if [ ! -e "$BIN" ] || [ -L "$BIN" ]; then ln -sfn "$APP" "$BIN"; fi

# "Start with system" names the AppImage's old path until the app next starts and rewrites it.
if [ -f "$AUTOSTART" ] && grep -qF "$OLD_DIR/Switchboard.AppImage" "$AUTOSTART"; then
  sed -i "s|$OLD_DIR/Switchboard.AppImage|$APP|g" "$AUTOSTART"
fi

# Last, so a run that died halfway leaves no stamp claiming this version is installed.
printf '%s\n' "$label" >"$STAMP"
refresh_menus

# --- What is left for the user --------------------------------------------------------------

echo ""
echo "Switchboard $label is installed: find it in your applications menu, or run 'switchboard'."
if pgrep -x switchboard >/dev/null 2>&1 || pgrep -x switchboard.bin >/dev/null 2>&1; then
  echo "A copy is already running; quit it from the tray and start it again to use this one."
fi
case ":${PATH}:" in
  *":$HOME/.local/bin:"*) ;;
  *) warn "Note: ~/.local/bin is not on your PATH, so the 'switchboard' command will not be found until it is." ;;
esac

# The AppImage runtime mounts itself with FUSE 2. Ubuntu 24.04 and newer ship without it, and the
# AppImage then prints a dlopen error and exits.
if command -v ldconfig >/dev/null 2>&1 && ! ldconfig -p 2>/dev/null | grep -q 'libfuse\.so\.2'; then
  warn ""
  warn "Note: FUSE 2 is missing, and the AppImage needs it to start. Install it:"
  warn "  Debian/Ubuntu 24.04+  sudo apt install libfuse2t64"
  warn "  older Debian/Ubuntu   sudo apt install libfuse2"
  warn "  Fedora                sudo dnf install fuse-libs"
  warn "  Arch                  sudo pacman -S fuse2"
fi

echo ""
echo "Worth knowing:"
echo "  - External monitor brightness needs you in the i2c group:"
echo "      sudo usermod -aG i2c \"\$USER\"    (then log out and back in)"
echo "  - In a Wayland session the remote touchpad reaches only Xwayland windows."
if command -v apt-get >/dev/null 2>&1; then
  echo "  - On Debian, Ubuntu and Mint the .deb on the same release also brings pactl and lspci,"
  echo "    which audio control and the GPU name on the About System screen use."
fi
echo ""
echo "Open Paired Devices and scan the QR code with the Android app."
echo "Docs: https://aiyu-ayaan.github.io/Switchboard/linux-host"
