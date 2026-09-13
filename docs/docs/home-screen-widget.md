# Home-Screen Widgets

Two widgets, both driven by whatever the app has paired: a **controls** widget
that puts every desktop on the home screen, and a **performance** widget that
shows what the connected one is doing.

---

## 🎛️ Switchboard Controls

### What is dynamic and what is configured

| | Source | Changes when |
| :--- | :--- | :--- |
| The list of desktops | `HostStore` — the same records the app uses | You pair or forget a machine |
| The buttons each desktop carries | The widget's own Glance state | You edit them, per widget |

The split matters: a widget that made you re-add each desktop by hand would
drift out of step with the app the first time a pairing changed. The button row
is the only thing worth choosing, so it is the only thing the configuration
screen asks about.

Place two widgets and they can carry different button rows — one for media on
the home screen, one with `Lock` and `Sleep` on a secondary page.

### Available controls

| Control | Wire action | Notes |
| :--- | :--- | :--- |
| Previous / Play-Pause / Next | `media.playback.command` | Maps to the desktop's media keys |
| Volume down / up | `system.volume.set` | Steps by 5, clamped to 0–100 |
| Mute | `system.volume.set` | Toggles the current mute state |
| Lock | `system.lock` | Locks the workstation |
| Screen off | `system.power` (`display_off`) | Blanks the desktop's displays |
| Sleep | `system.power` (`sleep`) | Off by default — interrupts what you are doing |
| Shut down | `system.power` (`shutdown`) | Off by default — interrupts what you are doing |

A fresh widget carries Previous, Play-Pause, Next, Volume down, Volume up and
Lock. The two disruptive entries are opt-in: a mis-tap on a home screen is
cheap, and suspending a machine you were working on is not.

### Layout

The button row **wraps rather than shrinking**. Material's minimum touch target
is 48dp, and six of those do not fit a four-cell placement, so the widget
declares `SizeMode.Exact`, reads its real width, and splits the buttons into
balanced rows — 3 + 3, never 4 + 2.

Above ~185dp, the connected desktop's card also shows **what it is playing and
how loud**. Both come from the session broadcast the app is already receiving;
nothing asks the desktop for anything extra.

On a narrow widget the status pill shortens to `Idle`, because "Tap to control"
crowds out the machine name beside it — and the name is the part being read.

### Configuring

- **On placement** — the launcher runs the configuration step. Cancelling
  leaves no widget behind.
- **Later** — the gear in the widget's header reopens the same screen.

Tapping a desktop's name (rather than a button) opens the app.

---

## 📊 Switchboard Performance

CPU, memory, GPU and network for the desktop this phone is talking to, with
temperatures where the host reports them. Throughput is shown as two rates
rather than a meter — a progress bar needs a ceiling, and a network has no
honest one.

### Resizing

The stat grid has a fixed number of rows and no way to scroll, so it gives
elements up rather than letting them be sliced:

| Height | What you get |
| :--- | :--- |
| ~210dp and up | Everything: title bar, four cards with temperatures and used/total, age stamp |
| ~190–210dp | Smaller figures, no secondary lines |
| ~150–190dp | …and no age stamp |
| ~140–150dp | …and CPU + memory only |
| below ~140dp | …and no title bar |

Half the readings shown properly beats four with two of them cut in half, and a
reading that silently disappears off the bottom edge is the worst of the three.

### Where the numbers come from

Telemetry only flows over an open session, and a widget is mostly read while
the app is closed. So the last reading is persisted by `WidgetTelemetry` and
**every card is stamped with its age**: `Live · just now` while the session is
up, `4 min ago` when it is not. A stale number presented as current is worse
than no number.

Writes are rate-limited to **15 seconds**. Readings arrive every couple of
seconds, and each write is a preferences commit plus a redraw of every placed
widget — the session must not pay for the widget.

The refresh icon re-reads the snapshot. It deliberately does **not** dial the
desktop: connecting to refresh a number would make a glance at the home screen
cost a handshake and a session hand-over.

---

## 🔌 What a tap actually does

Switchboard holds **one** session at a time. A tap on a desktop that is not the
active one moves the session to it — exactly what the in-app host switcher
does — then sends the command:

1. The click gives the app a short foreground window (Android grants one for
   widget interaction).
2. If that desktop is already the live session, the command goes out
   immediately.
3. Otherwise the widget connects, waits up to 7 seconds for the handshake, and
   sends. A desktop that is off or off-network simply does nothing.

With **Stay connected** enabled in the app the session is already up, so every
tap is instant. Without it, the first tap after a while pays for a handshake.

:::note Why a widget never opens a socket on its own
Constructing the connection holder dials the last host. A launcher redraw —
rotation, resize, reboot — must not do that, so rendering reads the paired list
straight from storage and asks `SwitchboardConnection.peek()` for a session that
already exists. That is the difference between a row reading **Live** and one
reading **Tap to control**.
:::

---

## 🧱 Implementation

| Piece | File |
| :--- | :--- |
| Control catalogue and execution | `mobile/.../widget/WidgetActions.kt` |
| Controls widget, state key, callbacks | `mobile/.../widget/SwitchboardWidget.kt` |
| Performance widget | `mobile/.../widget/PerformanceWidget.kt` |
| Cached readings and formatting | `mobile/.../widget/WidgetTelemetry.kt` |
| Configuration screen | `mobile/.../widget/WidgetConfigActivity.kt` |
| Size thresholds | `mobile/.../widget/WidgetDensity.kt` |
| Provider metadata | `mobile/app/src/main/res/xml/switchboard_*widget_info.xml` |
| Picker previews | `mobile/app/src/main/res/layout/widget_preview_*.xml` |

Built on **Glance** with its Material 3 components (`Scaffold`, `TitleBar`,
`CircleIconButton`, `LinearProgressIndicator`), not hand-written `RemoteViews`:
the list is data-driven in both its length and its contents, which in
`RemoteViews` means a `RemoteViewsService` collection adapter and a layout per
cell.

Both providers declare an `android:previewLayout` so the launcher's picker
shows the real design. Those previews use **static sample data** — one built
from the live host list would put the user's machine names, and whatever they
are playing, into the picker. They are plain XML inflated as `RemoteViews`, so
they use only the allowed view types (no `Space`, no bare `View`) and take
their colours from the framework's dynamic palette.

`updatePeriodMillis` is `0` on both. There is nothing to poll — a widget
redraws when a button is pressed, when the refresh icon is tapped, and when the
app reports that the paired list, the live session or the readings changed.
