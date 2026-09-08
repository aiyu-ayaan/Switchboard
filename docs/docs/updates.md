---
title: Staying Up To Date
sidebar_label: Updates
---

# 🔄 Staying Up To Date

Switchboard is not in the Microsoft Store or on Google Play. Both apps are
published as files attached to a [GitHub
Release](https://github.com/aiyu-ayaan/Switchboard/releases), which means
nothing tells your computer or your phone that a newer build exists unless the
app itself goes and looks.

Both apps do. Neither installs anything without asking.

---

## 🖥️ On the desktop

**Settings → Updates.**

Switchboard checks when it starts and then every six hours, and there is a
**Check now** button for when you do not want to wait. If it finds a newer
build it downloads the installer straight away, quietly, in the background —
so that when you decide to update, the only thing left is a restart.

When it is ready you get **Restart and install**. Pressing it closes
Switchboard, runs the installer silently into the same folder you chose the
first time, and starts the app again when it is done. Your settings, your
paired phones and the host identity behind them all survive: the installer
writes over the program files and leaves your data alone.

Nothing is ever installed on its own. The download happens without being asked;
the install never does.

:::note Running from a checkout
A development build is never offered an update — there is no installed copy for
a release to replace. Settings will say so.
:::

---

## 📱 On Android

**Settings → App updates.**

The app checks each time you open it. If there is something newer, a sheet
comes up with what changed and two answers:

- **Download**, then **Install** — which hands the APK to Android's own package
  installer. That confirmation screen is the system's, it shows you what is
  about to replace what, and it is not skippable.
- **Not now** — a snooze, one day by default, and 1, 3 or 7 days on the update
  screen.

There is no "never". The version you are refusing is superseded next week; if
you want the app to stop looking, the switch on the update screen is the honest
way to say so.

### "Install unknown apps"

Android will not let an app start an install unless you have allowed that app
specifically. It is a per-app setting rather than a prompt, so Switchboard
cannot ask you for it in a dialog — it opens the right settings page instead.
Grant it once and the install goes through from then on.

:::warning Sideloading over a different build
If the copy you have installed was signed with a different key from the release
— for instance you built it yourself, or installed an unsigned CI build —
Android will refuse the update and say so. Uninstalling first is the only way
past it, and uninstalling takes your paired desktops with it.
:::

---

## 🚦 Release channels

Both apps offer the same three, and each one is cumulative:

| Channel | What you are offered |
| --- | --- |
| **Stable** | Finished releases only. |
| **Beta** | Release candidates, plus every stable release. |
| **Alpha** | Everything, the moment it is built. |

Cumulative matters: on beta you still get the stable release that supersedes
the beta build you are running, rather than being stranded between them.

The channel starts as the one your build came from — install an alpha and you
keep getting alphas — and you can change it on either client. It is a setting on
that device, not on your account, so your desktop and your phone can sit on
different channels.

Newer is judged by version number and never by release date. A stable release
cut after an alpha is not an upgrade for someone running that alpha, and
Switchboard will not offer it as one.

---

## 🧯 When it does not work

- **"GitHub answered 403"** — the check is unauthenticated and GitHub allows
  sixty an hour per address. Wait, or press Check again later. Switchboard
  reports it rather than retrying in a loop.
- **Nothing is offered even though a release exists** — check which channel you
  are on. A stable install is not offered alpha or beta builds.
- **A release with no matching file** — releases whose Windows or Android job
  failed still exist. That client is offered nothing rather than being handed a
  file it cannot use.
