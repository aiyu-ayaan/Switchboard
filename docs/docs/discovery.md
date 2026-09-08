# Host Discovery (mDNS / DNS-SD)

Switchboard desktops advertise themselves on the local network so a phone can
find them without being told an IP address.

---

## 📡 The Record

The daemon registers a DNS-SD service on every multicast-capable interface at
startup and withdraws it on shutdown, sending the goodbye packets so clients
drop the host immediately rather than waiting out the TTL.

| Field | Value |
| :--- | :--- |
| Service type | `_switchboard._tcp` |
| Domain | `local.` |
| Instance name | The machine name (truncated to the 63-byte DNS-SD limit) |
| Port | The daemon's LAN listener, `9427` by default |

### TXT record

```
v=1
id=ef21995b-12c7-4695-b03a-00d8b57afb08
name=ROOT
```

| Key | Meaning |
| :--- | :--- |
| `v` | Record version. Bumped if the meaning changes, so an old client ignores a record it would misread. |
| `id` | The daemon ID. What a client matches against a stored host. |
| `name` | The host name, shown until the client has connected. |

---

## 🔐 Discovery Carries No Authority

**The host's public key is deliberately absent from the TXT record.**

A record says only "a daemon claiming this ID answers here". It does not say the
daemon is the one it claims to be, and nothing in the pairing handshake trusts
it:

- **Pairing** still requires the 10-character code shown on the desktop, and
  still pins the host key seen during the handshake. A hostile machine
  advertising a stolen daemon ID produces a **failed handshake**, not a
  connection.
- **Reconnecting** still authenticates against the key already stored for that
  daemon ID. Discovery can move where the phone dials; it cannot change who the
  phone will accept on the other end.

Publishing the host key would imply an authority the record does not have, and
the handshake establishes the real key anyway. See
[Security & Pairing](./security-pairing.md).

---

## 📱 On the Phone

The Android client browses through the framework's `NsdManager` — no extra
permission and no third-party dependency. Browsing runs only while a screen is
collecting it, so an app sitting in the background costs no multicast traffic.

Resolution is serialised through a single mutex regardless of API level:
`NsdManager.resolveService` is single-flight below API 34 and fails with
"listener already in use" if a second resolve starts while one is outstanding.

Discovery is used in two places:

1. **Pairing** — desktops that are visible but not yet paired appear under
   "Found on this network". Tapping one fills in the address, leaving only the
   code to type. Manual entry of an IP address remains available and unchanged.
2. **Following a moved host** — when a paired desktop is discovered at an
   address different from the stored one, the stored record is updated. Without
   this, a new DHCP lease would leave reconnect dialling an address the desktop
   has left. Only the address moves; the pinned host key is untouched, so the
   handshake still has to prove the machine at the new address is the same one.
3. **Rescuing a failed reconnect** — a resume that never reaches the handshake
   is usually dialling an address the desktop has left behind, which is what
   changing Wi-Fi network does to a stored record. The phone looks the daemon
   up by its id, adopts the address it answers on now and retries there, up to
   three moves per session. This runs whether or not *Stay connected* is on: a
   pairing is meant to survive a change of network, not only a dropped socket.

---

## 🔁 Following the Host Across Networks

A pairing is not tied to the network it was made on. Both ends have to move for
that to hold:

- **The desktop** re-registers its advertisement whenever its outbound LAN
  address changes. zeroconf reads interface addresses once, at registration, so
  without this a desktop that joined another network kept answering queries with
  the address it had on the old one — a record that resolved to nowhere.
- **The phone** re-resolves by daemon id when a reconnect fails, rather than
  giving up on the stored address.

Either fix alone still leaves one end pointing at a dead address.

The limit is mDNS itself: it does not cross a router. Two devices on different
subnets, or on a network with client isolation, cannot find each other this way,
and Switchboard has no relay — pair by address there, or put both on the same
network.

---

## 🧯 When It Does Not Work

Discovery is best-effort at every layer. A network that blocks multicast — many
guest and corporate wireless networks do, and so does client isolation — costs
discovery, not pairing:

- The daemon logs the registration failure and keeps serving.
- The phone's browse fails quietly and the "Found on this network" section stays
  empty.
- The manual address-and-code path is unaffected, as is the QR code.
