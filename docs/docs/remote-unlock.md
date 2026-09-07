# Remote Unlock (Fingerprint)

Switchboard can lock the desktop from the phone, and — once you set it up — it
can open it again with your fingerprint. Guarded by the `unlock` capability, so
a desktop that has not been set up, and a phone with no fingerprint sensor,
simply never show the control.

> **Read the trade-off before you enable this.** Remote unlock stores your
> Windows password on the desktop. That is not an implementation shortcut; it
> is what unlocking Windows costs. The section on
> [what this actually costs](#-what-this-actually-costs) is not optional
> reading.

---

## 🔒 Why unlocking is harder than locking

Locking is one Win32 call. Unlocking has no call at all.

Once `LockWorkStation` runs, the console session belongs to Winlogon's **secure
desktop**. Nothing running as you — the Switchboard daemon included — can draw
on it, read it, or send it a keystroke. That is a deliberate Windows security
boundary, and there is no API that crosses it.

That leaves exactly two mechanisms:

| Mechanism | What it is | Why not / why |
| :--- | :--- | :--- |
| **Credential Provider** | A COM DLL registered in `HKLM` that LogonUI loads and asks for credentials | The supported route. A separate C++ project with an MSVC toolchain, CLSID registration and an admin installer — several times the size of everything else here |
| **SYSTEM keystroke injection** | A privileged helper attaches to the Winlogon desktop and types the password | What Switchboard does. Small, no new toolchain, and honest about its limits |

Switchboard takes the second. The limits that come with it are listed below
rather than buried.

---

## 🧠 How it works

Three parties, none of which trusts the next further than it has to.

```
  Phone                        Daemon (you)                 Helper (SYSTEM)
    |                              |                              |
    |-- unlock.challenge --------->|                              |
    |<------------- nonce ---------|                              |
    |                              |                              |
  [fingerprint prompt]             |                              |
  keystore signs the nonce         |                              |
    |                              |                              |
    |-- system.unlock (proof) ---->|                              |
    |                       verify signature                      |
    |                              |------ one byte on a pipe --->|
    |                              |                    spawn on Winlogon desktop
    |                              |                    decrypt password, type, Enter
```

### The phone proves a fingerprint, it does not claim one

A request arriving on the session already proves it came from the paired phone.
What it cannot prove is that a finger was presented — an app that skipped its
own prompt would send exactly the same frame.

So the claim is made with a key instead of a boolean. At setup the phone
generates an **ECDSA P-256** key pair inside its hardware keystore with
`setUserAuthenticationRequired`, which means the private half is unusable until
a biometric prompt succeeds. The prompt carries the signing operation itself as
its `CryptoObject`, so a signature and a real authentication are the same
event, not two an app could pull apart.

The desktop keeps only the public half. A patched APK can skip its own prompt
all it likes; it still cannot produce the signature.

Three further properties fall out of that design:

- **Bound to one desktop.** The signed message carries the daemon ID, so a
  proof captured on one machine will not open another.
- **Single use.** The nonce is spent the moment it is checked, pass or fail, so
  a failed attempt cannot be ground against a live challenge.
- **Bound to your current fingerprints.** The key is created with
  `setInvalidatedByBiometricEnrollment`, so adding a new finger to the phone
  destroys it. Someone who could enrol their own thumb on a borrowed phone does
  not inherit your desktop with it.

### The daemon stays unprivileged

It verifies the signature and writes **one byte** to a named pipe. It never
reads the password, never touches the secure desktop, and gains no privilege it
did not already have.

### The helper does the privileged part

`server.exe unlock service` runs as `SYSTEM` from a scheduled task. On that
byte it re-stamps a copy of its own token into the console session and starts
`server.exe unlock type` directly on `WinSta0\Winlogon`, which decrypts the
stored password, types it, and presses Enter. That child exits immediately, so
the plaintext lives for a fraction of a second.

---

## ⚙️ Setting it up on Windows

Four steps, once. You need an **elevated** PowerShell or Command Prompt.

### 1. Make the lock screen ask for your password

The helper types a password, so the lock screen has to be showing the password
field. If your PC signs in with a PIN or Windows Hello, the field will be
waiting for that instead and the typed password will be rejected.

Sign out and sign back in **with your password** once. Windows remembers the
last credential you used and offers it first from then on. To check, lock the
machine and confirm the field says *Password* — if it does not, click
**Sign-in options** and pick the password key.

### 2. Store the password

```powershell
& "C:\Path\To\server.exe" unlock enroll
```

It prompts without echoing. The password is encrypted with **DPAPI at machine
scope** and written to:

```
%ProgramData%\Switchboard\unlock.bin
```

Machine scope is required — the helper must decrypt it with nobody logged in —
which means anything that can *read* the file can decrypt it. So enrolment also
writes a protected ACL admitting only `SYSTEM` and `Administrators`, and
**refuses to leave the file behind** if that ACL cannot be applied.

### 3. Register the helper to run at boot

```powershell
schtasks /create /tn "Switchboard-Unlock" /ru SYSTEM /rl highest /sc onstart ^
         /tr "\"C:\Path\To\server.exe\" unlock service"
schtasks /run /tn "Switchboard-Unlock"
```

The second line starts it now so you do not have to reboot. Confirm it is
alive:

```powershell
schtasks /query /tn "Switchboard-Unlock"
```

### 4. Enrol the phone

With the desktop **unlocked** and the phone connected, open
**Settings → Security → Fingerprint Unlock** on the phone and tap **Set up**.

The card restates the trade-off before you tap it: the phone is where the
feature is switched on, so that is the last screen before you accept the cost.

Enrolment is refused while the desktop is locked. Pairing happens with you at
the machine; a phone taken afterwards must not be able to grant itself the lock
screen while you are away from it.

Now lock the desktop. The lock control on the phone's home screen — on the
host card and in the utility grid — turns into an unlock button within a
second. It lives on the home screen only: the detail panes carry no lock
control.

### Turning it off

```powershell
& "C:\Path\To\server.exe" unlock disable
schtasks /delete /tn "Switchboard-Unlock" /f
```

`unlock disable` deletes the stored password, which withdraws the capability —
the button disappears from every paired phone. On the phone, **Remove key**
deletes its key. Forgetting a device on the desktop drops its unlock key too,
so re-pairing never silently restores access you withdrew.

---

## ⚠️ What this actually costs

Stated plainly, because you are the one accepting them.

**Your Windows password is on disk.** DPAPI and the ACL mean a standard user
account cannot read it, but an administrator or anything running as `SYSTEM`
can. If that is not acceptable on your machine, do not enable this feature —
everything else in Switchboard works without it.

**Anything running as you can trigger an unlock.** The pipe has to be reachable
by the daemon, which runs as you, so it is reachable by anything else running
as you. Malware already in your session could unlock the screen. It inherits
your account's trust; it does not create a new way in from the network — a
remote attacker still needs a paired device *and* your fingerprint.

**It unlocks a locked session, not a cold boot.** The daemon must already be
running, which means you must already be signed in. After a reboot or a sign
out, sign in at the keyboard.

**PIN-first sign-in breaks it.** See step 1. If the lock screen is asking for a
PIN, the password gets typed into the wrong field and rejected.

**No fingerprint sensor, no feature.** The phone must report a real fingerprint
sensor of a class the keystore will bind a key to. Face and iris unlock do not
qualify, even where Android rates them strong.

---

## 🩺 If it does not work

| Symptom | Cause | Fix |
| :--- | :--- | :--- |
| No unlock button on the phone | The desktop never enrolled a password, or the phone has no usable sensor | Run `unlock enroll`; check **Settings → Security** appears on the phone at all |
| **Set up** is greyed out | The connected desktop does not advertise `unlock` | Run `unlock enroll` on that desktop and reconnect |
| "unlock helper unreachable" | The scheduled task is not running | `schtasks /run /tn "Switchboard-Unlock"` |
| Screen wakes, password rejected | The lock screen is asking for a PIN | Step 1 — sign in with your password once |
| "unlock the desktop before enrolling" | Enrolment while locked | Unlock at the keyboard first, then enrol |
| Worked, then stopped | A new fingerprint was added to the phone, invalidating the key | **Remove key**, then **Set up** again |
| Password changed in Windows | The stored copy is stale | Run `unlock enroll` again |

---

## 🔗 Related

- [Security & Pairing Protocol](./security-pairing.md) — the session this rides on
- [API & Wire Protocol Reference](./api-protocol.md) — the three `system.unlock*` actions
- [Troubleshooting & FAQ](./troubleshooting.md)
