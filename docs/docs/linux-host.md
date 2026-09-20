# Linux Host Backend

This page documents what the Switchboard daemon can drive on a Linux desktop,
which system service each control talks to, and where the platform sets limits
that no amount of code will move.

Linux has no single system API the way Windows has WASAPI, WMI and SMTC. Every
control here reaches a different subsystem, so the honest way to describe the
Linux host is one section per source.

---

## 📦 Installing

Releases carry two Linux artifacts, both x86-64 and both containing the same
daemon:

| Asset | For | Install |
| ----- | --- | ------- |
| `Switchboard-<version>.deb` | Debian, Ubuntu, Linux Mint, Pop!_OS | `sudo apt install ./Switchboard-<version>.deb` |
| `Switchboard-<version>.AppImage` | Everything else | `chmod +x Switchboard-*.AppImage && ./Switchboard-*.AppImage` |

Prefer the `.deb` where it fits, and install it with `apt` rather than by
double-clicking, so its dependencies come with it. It puts the app in
`/opt/Switchboard`, a launcher in the applications menu, and `switchboard` on
your `PATH`.

The AppImage needs FUSE 2 on Ubuntu 24.04 and newer, which is no longer
installed by default: `sudo apt install libfuse2t64`.

### The AppImage, installed for you

An AppImage is one file and no install step, which is its appeal and its
nuisance: nothing gives it a launcher entry, an icon or a name you can type.
[`scripts/install.sh`](https://github.com/aiyu-ayaan/Switchboard/blob/master/scripts/install.sh)
does those three things and nothing more:

```bash
curl -fsSL https://raw.githubusercontent.com/aiyu-ayaan/Switchboard/master/scripts/install.sh | sh
```

| It writes | Which is |
| --------- | -------- |
| `~/.local/lib/switchboard/Switchboard.AppImage` | the app, plus a `version` file next to it |
| `~/.local/bin/switchboard` | a symlink, so `switchboard` starts it from a terminal |
| `~/.local/share/applications/switchboard.desktop` | the launcher entry |
| `~/.local/share/icons/hicolor/…/switchboard.png` | the icon set, read out of the AppImage itself |

Run as root — `curl … | sudo sh` — it installs into `/usr/local` instead, for
every user on the machine.

| Flag | Does |
| ---- | ---- |
| *(none)* | the newest stable release |
| `--pre` | the newest build of any channel, alpha and beta included |
| `--version 1.2.3` | that release, whether or not it is the newest |
| `--prefix DIR` | installs under `DIR` rather than `~/.local` |
| `--force` | reinstalls a version that is already here |
| `--uninstall` | removes all four paths above |

Re-running it is the update: it compares the release against the `version` file
and stops if there is nothing to do. It refuses while Switchboard is running,
because replacing the file under a mounted AppImage takes the running copy down
with it — quit it from the tray first.

Nothing it does touches `~/.config/Switchboard`, so installing, updating and
uninstalling all keep the host key and every pairing. `--uninstall` prints the
one command that does remove them, for when that is what you meant.

The desktop app's own updater is Windows-only for now — it looks for the NSIS
installer and a release carries no Linux equivalent it could run unattended — so
on Linux this script, or `apt`, is the update path.

Your pairings live in `~/.config/Switchboard/switchboard.db`, not beside the
app, so switching between the AppImage and the `.deb` — or updating either —
keeps every paired phone.

---

## 📋 At a glance

| Control | Source | Status |
| ------- | ------ | ------ |
| External display brightness & contrast | DDC/CI over `/dev/i2c-*` | ✅ Needs group membership |
| Internal panel brightness | Kernel backlight class, logind fallback | ✅ |
| Display power (standby) | DDC/CI VCP `0xD6`, backlight toggle | ✅ |
| Master volume, mute | PulseAudio / PipeWire via `pactl` | ✅ |
| Per-application mixer | PulseAudio sink inputs | ✅ |
| Output & input device routing | PulseAudio sinks and sources | ✅ |
| Microphone volume & mute | PulseAudio default source | ✅ |
| Media transport & now playing | MPRIS2 over the D-Bus session bus | ✅ |
| Cover art | MPRIS `mpris:artUrl` | ⚠️ `file://` only |
| CPU, RAM, GPU, disk, network telemetry | procfs and sysfs | ✅ |
| Temperatures | hwmon | ✅ Where a sensor exists |
| About System (OS, CPU, GPU, RAM, battery) | os-release, procfs, DRM, power_supply | ✅ |
| Air mouse: pointer, buttons, scroll, gestures | X11 XTEST | ✅ X11 / Xwayland |
| Remote typing | X11 XTEST with keycode borrowing | ✅ X11 / Xwayland |
| Clipboard push | — | ❌ Not implemented |
| Power actions, Stream Deck, installed apps | — | ❌ Not implemented |

Anything marked ❌ reports its capability as absent, so the phone hides that
screen rather than offering a control that errors when pressed.

---

## 🔆 Displays

Enumeration comes from `/sys/class/drm`, not from probing. Each connector
states whether something is plugged into it, carries that panel's EDID for the
name, and links to the I²C bus that reaches it, so no bus is ever written to
speculatively.

- **External monitors** speak DDC/CI over the kernel's `i2c-dev` character
  devices. The MCCS VCP codes are the same ones the Windows backend uses:
  `0x10` brightness, `0x12` contrast, `0xD6` power.
- **Internal panels** have no such bus and go through the kernel backlight
  class, brightness only — the same single slider they get on Windows.

Brightness is reported as 0–100 rather than the panel's raw range. External
panels keep their own reported range, because those are frequently not 0–100
and the phone's slider should reflect what the hardware actually accepts.

### If external monitors do not appear

`/dev/i2c-*` is root-only on most distributions. Add yourself to the `i2c`
group and log back in:

```bash
sudo usermod -aG i2c "$USER"
```

The daemon logs the group to join once per run rather than failing the whole
enumeration, and the built-in panel is listed either way.

### Writing internal brightness

The sysfs file is tried first, for hosts whose udev rules allow it. logind's
`SetBrightness` is the fallback — the same call GNOME and KDE make for their
own sliders — and logind refuses unless your session is the active one.

---

## 🔊 Audio

Desktop Linux has one audio protocol that everything speaks: PulseAudio's.
PipeWire, the default on current Fedora, Ubuntu and their derivatives, ships
`pipewire-pulse` for exactly this reason, and bare PulseAudio is the rest of
the field. Talking that protocol reaches both.

It is spoken through `pactl`, from `pulseaudio-utils`, which is a dependency of
both servers — a host that can play sound has it. `libpulse` is not used
because the daemon is built with `CGO_ENABLED=0` so it can be dropped onto a
machine with no toolchain.

A single `pactl --format=json list` returns sinks, sources and sink inputs
together in a few milliseconds, so one `host.state` broadcast costs two process
spawns rather than ten. The snapshot is held for 500 ms and dropped
immediately by any write, so a slider drag reads back what it just wrote.

### Two behaviours worth knowing

- **Switching output moves what is already playing.** Setting the default sink
  only routes streams that start later, so tapping "Headphones" would otherwise
  change nothing audible. Existing streams are moved with it. The same applies
  to capture.
- **Monitor sources are hidden.** Every sink has a companion monitor source for
  recording what is being played. They are sources in every respect except
  being microphones, so they are kept out of the input list.

Endpoint IDs are sink and source *names*, not indices: indices are reassigned
every time the server sees a device, so a remembered one would address a
different speaker after a suspend. Mixer rows have no such option — a stream is
only addressable by index — which is why a mixer write returns the whole list
rather than one row.

---

## ⏯️ Media transport

Windows has one system media session with an OS-level arbiter deciding which
application owns it. Linux has MPRIS2: every player exports the same D-Bus
interface under its own bus name, and nothing arbitrates between them.

So the daemon chooses. A player that is actually `Playing` wins; failing that a
`Paused` one, which is what "resume what I was listening to" means; a `Stopped`
player only when nothing else is open. An idle browser tab should not outrank
paused music.

The session bus is spoken directly rather than through `playerctl`, which most
distributions do not install by default. The capability is claimed whenever a
session bus is reachable, without requiring a player to be running: a desktop
with nothing open is the normal resting state, and hiding the controls until a
player appears would make them flicker in and out.

### Cover art

Only `file://` URLs are read. Players that cache their art to disk — mpv, VLC,
Rhythmbox, and the browsers for locally played media — are covered. Spotify and
some others publish an `https://` URL to their own CDN, and those come back
without art.

That is deliberate. Following the URL would have the daemon make an outbound
request to an address supplied by whatever happens to be on the session bus, on
behalf of a phone, which is more than a thumbnail is worth handing a media
player.

---

## 📊 Resource telemetry

Everything comes from procfs and sysfs. The live loop ticks every 2 s, so the
cheap counters — CPU, memory, network, disk — are read every tick, and the
expensive sources keep their own intervals behind that: the GPU every 10 s, the
process table every 6 s, drive capacity every 60 s.

Four decisions that change what the numbers mean:

- **Memory used is total minus `MemAvailable`**, not total minus `MemFree`.
  Linux spends every otherwise idle page on cache, so `MemFree` on a healthy
  desktop sits near zero and the naive formula would report a machine with
  20 GB spare as being at 95%.
- **Network counts only interfaces backed by real hardware.** Summing
  everything that is not loopback double counts badly on a host running
  containers or VMs, where one packet is seen on the veth or tap, again on the
  bridge and again on the NIC. A host with no hardware interface falls back to
  counting everything.
- **Disk counts whole disks only.** Partitions are listed beside their parent
  in `/proc/diskstats`, and `dm-` and loop devices sit on top of a real disk;
  either would count twice.
- **Free space is `f_bavail`**, so the root-only reserve — 5% of a default
  ext4 — is not promised to an ordinary write.

### GPU support

| Vendor | Source | Reports |
| ------ | ------ | ------- |
| NVIDIA | `nvidia-smi` | Utilisation, temperature, VRAM used/total |
| AMD | `amdgpu` sysfs (`gpu_busy_percent`) | Utilisation, temperature, VRAM used/total |
| Intel | — | Nothing |

`nvidia-smi` is a process spawn and is dropped permanently on first failure: a
machine without the card will not grow one. Intel's i915 exposes utilisation
only through the perf subsystem, which needs a privileged open and a sampling
thread, so an integrated GPU reports nothing rather than a guess.

### Temperatures

The package sensor is located once per run — `coretemp` on Intel, `k10temp` or
`zenpower` on AMD, then the board's own `acpitz` zone — and read directly after
that. A reading outside 1–150 °C is dropped: a sensor that has never been read
returns a number no silicon survives, and 0 °C on a gauge is worse than nothing.

---

## 💻 About System

| Field | Source |
| ----- | ------ |
| OS edition | `PRETTY_NAME` from `/etc/os-release` |
| Build version | `/proc/sys/kernel/osrelease` (the kernel release) |
| Uptime | `/proc/uptime` |
| CPU model, cores, threads | `/proc/cpuinfo` |
| Base clock | cpufreq `base_frequency`, else the model name |
| GPUs | `/sys/class/drm` resolved through `lspci` |
| RAM total | `/proc/meminfo` |
| Drives | `/proc/mounts` + `statfs` |
| Battery | `/sys/class/power_supply` |

The distribution is the OS name and the kernel release is the build, which
mirrors the edition-over-build Windows shows. Reporting "Linux" would be true
and useless on a screen whose purpose is telling one machine from another.

Physical cores are counted as distinct `(physical id, core id)` pairs, not by
counting `processor` lines — those are hyperthreads, and the phone shows both
numbers on the same row.

Base clock avoids the `cpu MHz` line in `/proc/cpuinfo` entirely: that is the
instantaneous frequency, around 800 MHz on an idle laptop, so the "base clock"
would change on every refresh.

**Memory type and slot count are not reported.** They live in DMI, which is
`0400` on every distribution, and the daemon does not run as root. The phone
hides rows it has no value for.

---

## 🖱️ Air mouse and typing

X11's answer to `SendInput` is the XTEST extension. Events enter the server's
ordinary dispatch path, so they reach any client rather than only ones that
accept synthetic posts.

The daemon speaks the X protocol directly — no `libX11` (cgo) and no
`xdotool` (not installed by default). Keycodes are read from the server at
connect rather than assumed, so a Dvorak or AZERTY layout works: "Tab is
keycode 23" is right on a US PC layout and wrong the moment anyone switches.

### Gestures

The bindings differ from the Windows table because the desktops do.

| Gesture | Keys | Notes |
| ------- | ---- | ----- |
| Task view | `Super` | GNOME Activities, KDE overview |
| Show desktop | `Super` + `D` | |
| Workspace left / right | `Ctrl` + `Alt` + `←` / `→` | Universal across GNOME, KDE, Cinnamon, Xfce |
| Back / forward | `Alt` + `←` / `→` | In-application navigation |

A desktop that has rebound one of these simply does nothing, which is the same
outcome as a Windows host with the shortcut disabled by policy. As on Windows,
nothing outside this table can be injected, so a malformed frame cannot turn
the air mouse into a general keyboard.

### Typing characters the host layout lacks

Most characters are somewhere on the user's layout and only need their keycode,
plus Shift where the keysym sits in a shifted slot. An emoji, or an accented
letter on a US layout, goes through an unused keycode borrowed for one
keystroke and handed straight back.

This is what `xdotool` does and carries the same caveat: clients cache the
keyboard mapping and refresh it when the server announces a change, so a
character typed immediately after a remap can arrive at an application that has
not caught up. There is a short settle pause for this reason, and the fast path
is preferred wherever the layout can do the job.

### ⚠️ Wayland

XTEST reaches X clients. In a Wayland session that means **only
Xwayland-hosted applications respond** — the compositor's own surfaces and
native Wayland windows do not.

Reaching those requires `/dev/uinput`, which is root-only on every
distribution. That is a packaging decision — a udev rule granting the user's
session write access — rather than something the daemon can arrange for itself.
Until Switchboard ships one, an X11 session is the supported configuration for
the air mouse.

### Clipboard

Not implemented, and not merely unwritten. An X selection lives in the *owning
client*, which has to stay running and answer `SelectionRequest` events for as
long as it holds it. Supporting it means the daemon owning a selection and
serving an X event loop, which is a different shape of work from the stateless
injection above.

---

## 🧰 Packages

| Package | Provides | Needed for |
| ------- | -------- | ---------- |
| `pulseaudio-utils` | `pactl` | All audio control |
| `pciutils` | `lspci` | Readable GPU names in About System |
| `nvidia-utils` (or the driver) | `nvidia-smi` | NVIDIA telemetry and VRAM |

`pulseaudio-utils` is pulled in by both PulseAudio and `pipewire-pulse`, so it
is present on any desktop that plays sound. Without `pciutils`, GPUs are shown
by their raw `vendor:device` PCI IDs.

---

## 🩺 Troubleshooting

**Older AppImages abort with "The SUID sandbox helper binary was found, but is not
configured correctly".**
Chromium needs either unprivileged user namespaces or a root-owned setuid
helper, and an AppImage cannot supply the second. Ubuntu 24.04 and everything
built on it — Mint 22, Pop!_OS 24.04 — restrict the first
(`kernel.apparmor_restrict_unprivileged_userns=1`). From the release after
1.1.5 the AppImage carries a launcher that adds `--no-sandbox` only when it is
running from an AppImage on a kernel that restricts user namespaces, so it
starts by double-click, from a terminal or from the menu entry. On 1.1.5 itself,
install through `scripts/install.sh` (its `switchboard` command does the same) or
run `./Switchboard-1.1.5.AppImage --no-sandbox`. The `.deb` never had the
problem: its post-install step makes the helper setuid.

**The first launch of an AppImage is slow.** The window can take 15 seconds or
more to fill in, because the daemon is read through a compressed FUSE mount for
the first time. It is not hung, and later launches are quicker.

**"No audio endpoint is available on this host."**
The daemon could not reach an audio server. Check `pactl info` works as the
same user the daemon runs as. A daemon started as a system unit has no session
and will not see one.

**Media buttons report "no player is running."**
Nothing on the session bus exports MPRIS. Confirm with:

```bash
busctl --user list | grep mpris
```

**The trackpad does nothing.**
Check `echo $XDG_SESSION_TYPE`. On `wayland`, only Xwayland windows respond —
see the Wayland note above. On `x11`, confirm `DISPLAY` is set in the daemon's
own environment; a daemon started outside the session has none.

**Resources show zeroes on the very first reading.**
CPU, network and disk are all delta counters: the first sample establishes a
baseline and can only report zero. The second tick, two seconds later, carries
real numbers.
