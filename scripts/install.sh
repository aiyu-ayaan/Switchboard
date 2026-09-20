#!/bin/sh
# Install Switchboard on Linux from the released AppImage.
#
#   curl -fsSL https://raw.githubusercontent.com/aiyu-ayaan/Switchboard/master/scripts/install.sh | sh
#
# A downloaded AppImage on its own is a file in ~/Downloads that has to be
# chmod +x'd, remembered and launched by path: no launcher entry, no icon, no
# `switchboard` on PATH, and nothing to run again to update it. This does those
# four things and nothing else -- it is the .desktop file and the symlink the
# AppImage format deliberately leaves to whoever installs it.
#
# What it does NOT do, on purpose:
#
#   - It never touches ~/.config/Switchboard, where the host identity key and
#     every pairing live. Installing, updating and uninstalling all keep your
#     paired phones; --uninstall says how to remove them if that is what you
#     want.
#   - It adds no apt/dnf repository and no cron job. Re-running it is the
#     update, which is why it is short enough to read before piping it to a
#     shell.
#
# Debian, Ubuntu and Mint have a .deb on the same release, and it is the better
# choice there: apt pulls in pactl and lspci, which audio control and the GPU
# name on the About System screen need. This script says so and carries on --
# the AppImage works on those distributions too.
#
# Usage:
#   install.sh [--version <x.y.z>] [--pre] [--prefix <dir>] [--force]
#   install.sh --uninstall
#
# Environment:
#   SWITCHBOARD_PREFIX   same as --prefix
#   SWITCHBOARD_REPO     owner/name to install from (for forks)
set -eu

REPO="${SWITCHBOARD_REPO:-aiyu-ayaan/Switchboard}"
API="https://api.github.com/repos/${REPO}"

# ~/.local for a normal user, /usr/local under sudo -- `curl | sudo sh` is a
# common reflex and installing root's copy into /root/.local would put the
# launcher entry somewhere no desktop session reads.
if [ -n "${SWITCHBOARD_PREFIX:-}" ]; then
	prefix="$SWITCHBOARD_PREFIX"
elif [ "$(id -u)" = 0 ]; then
	prefix="/usr/local"
else
	prefix="${HOME}/.local"
fi

want_version=""
want_pre=no
force=no
uninstall=no

while [ $# -gt 0 ]; do
	case "$1" in
		--version) want_version="${2:?--version needs a version, e.g. --version 1.1.4}"; shift 2 ;;
		--version=*) want_version="${1#*=}"; shift ;;
		--pre|--prerelease) want_pre=yes; shift ;;
		--prefix) prefix="${2:?--prefix needs a directory}"; shift 2 ;;
		--prefix=*) prefix="${1#*=}"; shift ;;
		--force) force=yes; shift ;;
		--uninstall|--remove) uninstall=yes; shift ;;
		-h|--help)
			# Spelled out rather than sed'd out of this file's own header:
			# piped to a shell there is no file to read it from.
			cat <<-EOF
			Install Switchboard on Linux from the released AppImage.

			  install.sh [--version <x.y.z>] [--pre] [--prefix <dir>] [--force]
			  install.sh --uninstall

			  --version <x.y.z>  install that release instead of the newest stable one
			  --pre              accept an alpha or beta, whichever is newest
			  --prefix <dir>     install under <dir> instead of ${prefix}
			  --force            reinstall even if that version is already here
			  --uninstall        remove the app, keeping your pairings

			It installs the AppImage, a \`switchboard\` command and a launcher entry,
			and leaves ~/.config/Switchboard -- your host key and every pairing --
			alone. Run it again to update.
			EOF
			exit 0
			;;
		*) echo "install.sh: unknown option '$1' (try --help)" >&2; exit 2 ;;
	esac
done

appdir="${prefix}/lib/switchboard"
appimage="${appdir}/Switchboard.AppImage"
stamp="${appdir}/version"
bin="${prefix}/bin/switchboard"
entry="${prefix}/share/applications/switchboard.desktop"
icons="${prefix}/share/icons"

say() { printf '%s\n' "$*"; }
warn() { printf '%s\n' "$*" >&2; }
die() { printf 'install.sh: %s\n' "$*" >&2; exit 1; }

# A tray icon is the only part of the app that keeps running with the window
# closed, so "is it installed" and "is it running" are different questions and
# both matter: replacing the file under a mounted AppImage takes the running
# copy down with it.
running() {
	pgrep -f "${appimage}" >/dev/null 2>&1 && return 0
	# The daemon and the Electron process are both `switchboard`, and either one
	# holding the AppImage is enough of a reason to stop.
	pgrep -x switchboard >/dev/null 2>&1
}

refresh_caches() {
	if command -v update-desktop-database >/dev/null 2>&1; then
		update-desktop-database "${prefix}/share/applications" >/dev/null 2>&1 || true
	fi
	if command -v gtk-update-icon-cache >/dev/null 2>&1; then
		gtk-update-icon-cache -q -t -f "${icons}/hicolor" >/dev/null 2>&1 || true
	fi
}

# --- Uninstall --------------------------------------------------------------

if [ "$uninstall" = yes ]; then
	[ -e "$appimage" ] || [ -e "$bin" ] || [ -e "$entry" ] \
		|| die "nothing installed under ${prefix}."

	if running && [ "$force" != yes ]; then
		die "Switchboard is running. Quit it from the tray first, or pass --force."
	fi

	rm -rf "$appdir"
	# Only our own symlink: a `switchboard` on PATH that came from the .deb is
	# not ours to delete.
	if [ -L "$bin" ]; then rm -f "$bin"; fi
	rm -f "$entry"
	find "${icons}/hicolor" -name 'switchboard.png' -type f -delete 2>/dev/null || true
	refresh_caches

	say "Removed Switchboard from ${prefix}."
	say ""
	say "Your pairings are still in ~/.config/Switchboard. To forget every paired"
	say "phone as well:  rm -rf ~/.config/Switchboard"
	exit 0
fi

# --- What this machine can run ----------------------------------------------

[ "$(uname -s)" = Linux ] || die "this installs the Linux AppImage; $(uname -s) is not it."

case "$(uname -m)" in
	x86_64|amd64) : ;;
	*) die "releases carry x86-64 builds only, and this is $(uname -m). Build from source: https://github.com/${REPO}/blob/master/CONTRIBUTING.md" ;;
esac

if command -v curl >/dev/null 2>&1; then
	get() { curl -fsSL --proto '=https' --tlsv1.2 "$1"; }
	# A progress bar only where someone is watching one: piped into a log,
	# curl's meter is a few hundred lines of carriage returns.
	if [ -t 2 ]; then
		fetch() { curl -fL --progress-bar --proto '=https' --tlsv1.2 "$2" -o "$1"; }
	else
		fetch() { curl -fsSL --proto '=https' --tlsv1.2 "$2" -o "$1"; }
	fi
elif command -v wget >/dev/null 2>&1; then
	get() { wget -qO- "$1"; }
	fetch() { wget -O "$1" "$2"; }
else
	die "neither curl nor wget is installed."
fi

if running && [ "$force" != yes ]; then
	die "Switchboard is running. Quit it from the tray first (replacing the file under a running AppImage kills it), or pass --force."
fi

# --- Which release ----------------------------------------------------------

if [ -n "$want_version" ]; then
	tag="v${want_version#v}"
	api_path="/releases/tags/${tag}"
	label="$tag"
elif [ "$want_pre" = yes ]; then
	# The list comes back newest first, so the first AppImage in it is the
	# newest one published, pre-release or not.
	api_path="/releases?per_page=20"
	label="the newest build"
else
	api_path="/releases/latest"
	label="the latest stable release"
fi

say "Looking up ${label} of ${REPO}..."
release="$(get "${API}${api_path}" || true)"

if [ -z "$release" ]; then
	if [ "$want_pre" = no ] && [ -z "$want_version" ]; then
		die "GitHub returned nothing for the latest release. If only pre-releases have been published so far, try --pre."
	fi
	die "GitHub returned nothing for ${label}. Check the version exists: https://github.com/${REPO}/releases"
fi

# No jq on a stock install, so the payload is split on commas and the first
# AppImage URL in it is taken. That is the right one for every shape above: a
# single release has one, and a list is ordered newest first.
#
# Two greps rather than a grep and a sed: the first finds the right field, the
# second cuts the URL out of it. A sed that fails to match would print the line
# it was given instead, and a JSON fragment that reaches `curl` as a URL is a
# confusing way to find out the payload changed shape.
url="$(printf '%s' "$release" \
	| tr ',' '\n' \
	| grep -o '"browser_download_url"[[:space:]]*:[[:space:]]*"[^"]*\.AppImage"' \
	| grep -o 'https://[^"]*\.AppImage' \
	| head -1)"

[ -n "$url" ] || die "that release has no AppImage attached. Its Linux packaging job may have failed: https://github.com/${REPO}/releases"

# Switchboard-1.2.3.AppImage -> 1.2.3, which is what the stamp compares and
# what gets printed. Derived from the file name rather than the payload so it
# always matches the file that actually landed.
file="${url##*/}"
version="${file#Switchboard-}"
version="${version%.AppImage}"

if [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$version" ] && [ "$force" != yes ]; then
	say "Switchboard ${version} is already installed at ${appimage}."
	say "Pass --force to reinstall it."
	exit 0
fi

# --- Download ---------------------------------------------------------------

tmp="$(mktemp -d "${TMPDIR:-/tmp}/switchboard-install.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

say "Downloading ${file}..."
fetch "${tmp}/app" "$url" || die "the download failed."

# A 404 page or a transfer that stopped halfway must never be chmod +x'd and
# handed a launcher entry. The ELF header is the check that matters; the 'AI'
# and format version at bytes 8..10 are the AppImage marker on top of it, and a
# runtime that stops writing them one day should not stop installs, so that
# half is a warning.
magic="$(od -An -tx1 -N11 "${tmp}/app" 2>/dev/null | tr -d ' \n')"
case "$magic" in
	7f454c46*414902) : ;;
	7f454c46*) warn "Note: the download is an executable but carries no AppImage marker. Continuing." ;;
	*) die "what downloaded is not an AppImage (starts ${magic:-empty}). Try again, or download it by hand from https://github.com/${REPO}/releases" ;;
esac

chmod +x "${tmp}/app"

# The icon set electron-builder already put in the AppImage, rather than one
# fetched separately: `--appimage-extract <pattern>` needs no FUSE, so this
# works even where the AppImage itself cannot yet run. Older runtimes take no
# pattern and extract everything, which is why failure here is survivable --
# the entry falls back to the theme name and the app still starts.
(cd "$tmp" && ./app --appimage-extract 'usr/share/icons/*' >/dev/null 2>&1) || true
if [ ! -d "${tmp}/squashfs-root/usr/share/icons" ]; then
	(cd "$tmp" && ./app --appimage-extract 'switchboard.png' >/dev/null 2>&1) || true
fi

# --- Install ----------------------------------------------------------------

mkdir -p "$appdir" "${prefix}/bin" "${prefix}/share/applications"

# Into place as one rename, so an interrupted run never leaves a half-written
# AppImage that the launcher entry already points at.
mv "${tmp}/app" "${appimage}.new"
mv -f "${appimage}.new" "$appimage"

ln -sfn "$appimage" "$bin"

icon_name=switchboard
if [ -d "${tmp}/squashfs-root/usr/share/icons" ]; then
	mkdir -p "$icons"
	# File by file rather than copying the tree over $icons, so each size lands
	# beside whatever else installed one and no other app's hicolor entries are
	# disturbed.
	(cd "${tmp}/squashfs-root/usr/share/icons" && find . -name 'switchboard.png' -type f) \
		| while read -r found; do
			mkdir -p "${icons}/$(dirname "${found#./}")"
			cp -f "${tmp}/squashfs-root/usr/share/icons/${found#./}" "${icons}/${found#./}"
		done
elif [ -f "${tmp}/squashfs-root/switchboard.png" ]; then
	# The single icon at the AppImage root, which is the 512px source the rest
	# of the set is generated from.
	mkdir -p "${icons}/hicolor/512x512/apps"
	cp -f "${tmp}/squashfs-root/switchboard.png" "${icons}/hicolor/512x512/apps/switchboard.png"
fi

# Exec is quoted because $prefix may contain spaces. StartupWMClass is
# Switchboard, the productName the window actually carries -- without it the
# running window is a second, iconless entry in the taskbar.
cat > "$entry" <<EOF
[Desktop Entry]
Type=Application
Name=Switchboard
GenericName=Remote desktop control
Comment=Control your desktop from your phone
Exec="${appimage}" %U
TryExec=${appimage}
Icon=${icon_name}
Terminal=false
Categories=Utility;
Keywords=remote;control;display;brightness;volume;media;
StartupWMClass=Switchboard
EOF
chmod 644 "$entry"

# Last, once the app, the symlink, the icons and the entry are all in place: a
# run that died halfway should not leave behind a stamp claiming this version is
# installed, because the next run would believe it.
printf '%s\n' "$version" > "$stamp"

refresh_caches

# --- What is left for the user ----------------------------------------------

say ""
say "Switchboard ${version} is installed."
say "  app      ${appimage}"
say "  command  ${bin}"
say "  launcher ${entry}"
say ""

case ":${PATH}:" in
	*":${prefix}/bin:"*) : ;;
	*) warn "Note: ${prefix}/bin is not on your PATH, so \`switchboard\` will not be found until it is." ;;
esac

# FUSE 2 is what mounts the AppImage. Ubuntu 24.04 and newer ship neither it
# nor a hint about it: the AppImage just prints a dlopen error and exits.
if command -v ldconfig >/dev/null 2>&1 && ! ldconfig -p 2>/dev/null | grep -q 'libfuse\.so\.2'; then
	warn "Note: FUSE 2 is missing, and the AppImage needs it to start. Install it:"
	warn "  Debian/Ubuntu 24.04+  sudo apt install libfuse2t64"
	warn "  older Debian/Ubuntu   sudo apt install libfuse2"
	warn "  Fedora                sudo dnf install fuse-libs"
	warn "  Arch                  sudo pacman -S fuse2"
	warn "Or run it without FUSE:  APPIMAGE_EXTRACT_AND_RUN=1 switchboard"
	warn ""
fi

if command -v apt-get >/dev/null 2>&1; then
	say "On Debian, Ubuntu and Mint the .deb on the same release is the better fit --"
	say "it brings pactl and lspci with it, which audio control and the GPU name on"
	say "the About System screen use. The AppImage works either way."
	say ""
fi

say "Two things worth doing now:"
say "  - External monitor brightness needs you in the i2c group:"
say "      sudo usermod -aG i2c \"\$USER\"    (then log out and back in)"
say "  - In a Wayland session the remote touchpad reaches only Xwayland windows."
say "    Log in to X11 for the full air mouse; everything else is unaffected."
say ""
say "Start it with \`switchboard\` or from your applications menu, then open Paired"
say "Devices and scan the QR code with the Android app."
say "Docs: https://aiyu-ayaan.github.io/Switchboard/linux-host"
say "Update later by running this script again; --uninstall removes it."
