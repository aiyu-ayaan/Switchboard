# Home-Screen Widget

Every paired desktop, on the Android home screen, with the controls you press
most often. No list to maintain: the widget renders whatever the app has
paired, so a machine appears the moment it is paired and disappears when it is
forgotten.

---

## 🧠 What is dynamic and what is configured

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

---

## 🎛️ Available controls

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

---

## ⚙️ Configuring

- **On placement** — the launcher runs the configuration step. Cancelling
  leaves no widget behind.
- **Later** — the gear in the widget's header reopens the same screen.

Tapping a desktop's name (rather than a button) opens the app.

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

:::note Why the widget never opens a socket on its own
Constructing the connection holder dials the last host. A launcher redraw —
rotation, resize, reboot — must not do that, so rendering reads the paired list
straight from storage and asks `SwitchboardConnection.peek()` for a session that
already exists. That is the difference between a row reading **Connected** and
one reading **Tap to control**.
:::

---

## 🧱 Implementation

| Piece | File |
| :--- | :--- |
| Control catalogue and execution | `mobile/.../widget/WidgetActions.kt` |
| Rendering, state key, callbacks | `mobile/.../widget/SwitchboardWidget.kt` |
| Configuration screen | `mobile/.../widget/WidgetConfigActivity.kt` |
| Provider metadata | `mobile/app/src/main/res/xml/switchboard_widget_info.xml` |

Built on **Glance**, not hand-written `RemoteViews`: the list is data-driven in
both its length and its contents, which in `RemoteViews` means a
`RemoteViewsService` collection adapter and a layout per cell.

`updatePeriodMillis` is `0`. There is nothing to poll — the widget redraws when
a button is pressed, when the refresh icon is tapped, and when the app reports
that the paired list or the live session changed.
