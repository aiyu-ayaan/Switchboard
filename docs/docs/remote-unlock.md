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
| **Credential Provider** | A COM DLL registered in `HKLM` that LogonUI loads and asks for credentials | What Switchboard does. The supported route, and the only one that works whatever you sign in with |
| **SYSTEM keystroke injection** | A privileged helper attaches to the Winlogon desktop and types the password | Tried and dropped. It only reached a lock screen already showing the password field, so a PIN or Hello sign-in got the password typed into the wrong box |

Switchboard takes the first. The limits that come with it are listed below
rather than buried.

---

## 🧠 How it works

Three parties, none of which trusts the next further than it has to.

```
  Phone                    Daemon (you)        switchboard_cp.dll (in LogonUI)
    |                          |                              |
    |-- unlock.challenge ----->|                              |
    |<--------- nonce ---------|                              |
    |                          |                              |
  [fingerprint prompt]         |                              |
  keystore signs the nonce     |                              |
    |                          |                              |
    |-- system.unlock (proof)->|                              |
    |                   verify signature                      |
    |                          |--------- SetEvent ---------->|
    |                          |                     offer one tile, default,
    |                          |                     auto-submit
    |                          |                     decrypt password -> LSA
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

It verifies the signature and sets **one event**. It never reads the password,
never touches the secure desktop, and gains no privilege it did not already
have.

### The credential provider does the privileged part

`switchboard_cp.dll` is loaded by LogonUI, which runs as `SYSTEM` on the secure
desktop. Until the event fires it shows **no tile at all**. When it fires it
offers one tile, declares it the default, and asks LogonUI to submit it with no
user interaction — so whichever tile you last signed in with is not the one
receiving credentials. It then decrypts the stored password and hands LSA a
`KERB_INTERACTIVE_UNLOCK_LOGON`. The plaintext exists for the length of that
one call and is wiped on the way out.

This is what makes your sign-in method irrelevant. Nothing is typed, so nothing
can be typed into the wrong field.

---

## ⚙️ Setting it up on Windows

Four steps, once. You need an **elevated** PowerShell or Command Prompt.

### 1. Build the credential provider

Needs the MSVC C++ toolchain, once:

```
winget install --id Microsoft.VisualStudio.2022.BuildTools --override "--quiet --add Microsoft.VisualStudio.Workload.VCTools --includeRecommended"
```

Then:

```
pnpm build:credprovider
```

That writes `bin/switchboard_cp.dll`, which has to sit next to `server.exe`.

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

### 3. Register the provider

```powershell
& "C:\Path\To\server.exe" unlock setup
```

`setup` does step 2 and this one together, because either alone is a
half-working install. It writes the CLSID under `HKLM\SOFTWARE\Classes\CLSID`
and lists it in `HKLM\...\Authentication\Credential Providers`. No reboot
needed — LogonUI loads providers afresh each time the session locks.

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
& "C:\Path\To\server.exe" unlock teardown
```

`teardown` deletes the stored password and unregisters the provider, which
withdraws the capability —
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

**Anything running as you can trigger an unlock.** The event has to be settable
by the daemon, which runs as you, so it is settable by anything else running as
you. Malware already in your session could unlock the screen. It inherits
your account's trust; it does not create a new way in from the network — a
remote attacker still needs a paired device *and* your fingerprint.

**It unlocks a locked session, not a cold boot.** The daemon must already be
running, which means you must already be signed in. After a reboot or a sign
out, sign in at the keyboard.

**If the account password changes, re-enrol.** Nothing notices on its own. The
lock screen shows the rejection and the phone is told the attempt failed.

**A broken provider is recoverable.** If the DLL ever misbehaves and the lock
screen will not come up properly, boot into Safe Mode and delete
`HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\{599BA444-2560-4520-AB9E-A68E52826FEE}`.
LogonUI falls back to the built-in providers.

**No fingerprint sensor, no feature.** The phone must report a real fingerprint
sensor of a class the keystore will bind a key to. Face and iris unlock do not
qualify, even where Android rates them strong.

---

## 🩺 If it does not work

| Symptom | Cause | Fix |
| :--- | :--- | :--- |
| No unlock button on the phone | The desktop never enrolled a password, or the phone is not ready | Run `unlock enroll`; open **Settings → Security** on the phone, which names whichever end is not ready |
| **Settings → Security** missing on the phone | The phone has no fingerprint reader at all | Nothing to do — face and iris do not qualify |
| "No fingerprint is registered on this phone" | The sensor is there but unused | Add a fingerprint in Android **Settings → Security**, then reopen the Switchboard screen |
| **Set up** is greyed out | The connected desktop does not advertise `unlock` | Run `unlock enroll` on that desktop and reconnect |
| "unlock provider not listening" | The DLL is not registered, or the desktop is not actually locked | Re-run `unlock setup` elevated; check `bin/switchboard_cp.dll` sits next to `server.exe` |
| Password rejected on the lock screen | The stored copy no longer matches the account | Run `unlock enroll` again |
| "unlock the desktop before enrolling" | Enrolment while locked | Unlock at the keyboard first, then enrol |
| Worked, then stopped | A new fingerprint was added to the phone, invalidating the key | **Remove key**, then **Set up** again |
| Password changed in Windows | The stored copy is stale | Run `unlock enroll` again |

---

## 🔗 Related

- [Security & Pairing Protocol](./security-pairing.md) — the session this rides on
- [API & Wire Protocol Reference](./api-protocol.md) — the three `system.unlock*` actions
- [Troubleshooting & FAQ](./troubleshooting.md)
