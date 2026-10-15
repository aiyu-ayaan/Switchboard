# Screen Sharing (Both Ways)

Switchboard shares screens in both directions over the same encrypted session
everything else uses — no second app, no account, no cloud relay.

- **PC Screen** (desktop → phone): see the desktop on the phone and use it by
  touch. Tap to click, drag to drag, type with the phone's keyboard.
- **Phone screen** (phone → desktop): see the phone on the desktop and use it
  with the mouse and keyboard. Its apps are listed on the desktop, and each
  can open in a window of its own.

The two are independent and can run at the same time.

---

## PC Screen: the desktop on the phone

Open **PC Screen** on the phone's home screen. It appears when the desktop can
capture its screen (the `screen` capability).

| On the phone | On the desktop |
| ------------ | -------------- |
| Tap | Left click where you tapped |
| Drag | Left-button drag |
| Long press, or two-finger tap | Right click |
| Two-finger drag | Scroll |
| Pinch | Zoom the picture on the phone; once zoomed, two fingers pan it |
| Keyboard button | Type into the focused window, plus a row of Esc, Tab, arrows, Home, End, Del, Backspace, Enter |

The toolbar switches between **touch** mode (above) and **trackpad** mode,
which moves the pointer relatively for precise work, picks a **quality**
preset (sharp text, balanced, smooth motion), locks **landscape**, and, on a
desktop with more than one monitor, picks the **display** — one monitor at a
time, or all of them as one picture. Sharing starts on the primary monitor:
several monitors side by side shrink to an unreadable strip on a phone.

While any phone is watching, the desktop shows a banner naming it, with
**Stop sharing**. The phone is told why its view ended.

### How it works

The daemon captures the chosen monitor, box-filters it down to roughly the
phone's own resolution, and sends it as JPEG. Unchanged frames are never
resent, and a moved pointer alone goes as a few bytes of metadata, so an idle
desktop costs nothing. The phone draws the pointer itself, since screen
captures do not include it.

| Platform | Capture | Pointer |
| -------- | ------- | ------- |
| Windows | GDI `BitBlt` of the virtual screen, per-monitor DPI aware | `SendInput` absolute, virtual-desk coordinates |
| Linux (X11) | Core `GetImage` on a dedicated connection; monitors from RandR 1.5 | XTEST absolute motion |

On Linux under Wayland, only Xwayland windows can be captured, the same limit
the air mouse has.

---

## Phone screen: the phone on the desktop

Open **Phone screen** in the desktop app, pick the phone, and press **Show
phone screen**. Android then asks on the phone — *Share your screen with
Switchboard?* — and nothing can skip that prompt. Choose **Share entire
screen**. If the phone is on another screen, a notification brings the prompt
back.

The phone can also start sharing itself from **Share Phone** on its home
screen.

### Using the phone from the desktop

| Desktop | Phone |
| ------- | ----- |
| Click | Tap |
| Hold | Long press |
| Drag | Swipe along the same path at the same speed (a flick flings) |
| Wheel | Scroll |
| Right click | Back |
| Middle click | Home |
| Typing, Ctrl+V | Text into the focused field |
| Backspace, Delete, Enter, arrows, Home, End, Esc | The same edit, or Back for Esc |

The buttons under the picture are Back, Home, Recents and Notifications.

Control needs **Switchboard desktop control** switched on in the phone's
*Settings → Accessibility*. Without it the mirror is view-only and the desktop
says so. On Android 13 and later, apps installed outside a store have this
switch greyed out until you open *App info → ⋮ → Allow restricted settings*;
the Share Phone screen links straight there.

### Apps in their own windows

The **Apps** panel lists everything on the phone's home screen. Clicking an
app opens it on the phone and in a phone-sized window of its own. Open several
and arrange them like any other windows.

An Android app cannot run other apps on hidden screens of its own; only the
system and ADB can. So every window shows the phone's one screen, and
**focusing a window brings its app to the front on the phone**. While another
app is in front, a window dims and says so, and clicking it brings its own app
back. **Pop out** opens the whole phone in a window. While the phone is out in
a window, the main view stops drawing it.

### How it works

The phone captures through `MediaProjection` straight into the hardware H.264
encoder, so no frame touches its CPU. Rotation is followed by resizing the one
virtual display. When the network falls behind, the phone drops frames and
sends a fresh keyframe instead of building a backlog.

The daemon keeps a few seconds of history rather than only the latest frame,
because a skipped H.264 delta corrupts the picture. Each desktop window reads
it in order from the newest keyframe, so a window opened mid-stream shows a
picture at once.

A still phone screen produces no frames, only a repeat every quarter second.
The desktop then shows **idle** rather than a misleadingly low frame rate.

---

## Wire actions

| Action | Direction | Payload | Blob |
| ------ | --------- | ------- | ---- |
| `screen.start` | phone → desktop | `ScreenSettings` (`maxWidth`, `quality`, `fps`, `display`) | — |
| `screen.stop` | phone → desktop | — | — |
| `screen.frame` | desktop → phone | `ScreenFrame` (size, desktop size, cursor) | JPEG, or none for a cursor-only move |
| `screen.state` | desktop → phone | `ScreenState` (displays, chosen display, error) | — |
| `input.point` | phone → desktop | `{x, y}` as fractions of the shared picture | — |
| `input.key` | phone → desktop | `{key}` from a fixed table | — |
| `mirror.start` / `mirror.stop` | desktop → phone | `MirrorSettings` / — | — |
| `mirror.keyframe` | desktop → phone | — | — |
| `mirror.frame` | phone → desktop | `MirrorFrame` (size, key, timestamp) | H.264 access unit |
| `mirror.state` | phone → desktop | `MirrorState` (streaming, consent, control, foreground app) | — |
| `mirror.input` | desktop → phone | `MirrorInput` (`tap`, `longPress`, `swipe`, `scroll`, `text`, `key`, `nav`) | — |
| `mirror.apps` | both | request / `{apps: [{package, label, icon}]}` | — |
| `mirror.launch` | desktop → phone | `{package}` | — |

Taps, keys and absolute moves reuse the air mouse's button and scroll frames;
`input.point` only adds "put the pointer here". Like the shell gestures,
`input.key` is a fixed table, so the remote screen never becomes a general
keyboard channel.
