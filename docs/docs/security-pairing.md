# Switchboard Security & Pairing Protocol

This document outlines the cryptographic specifications, trust model, and pairing protocol that secure communication between the Switchboard desktop daemon and mobile client.

---

## 🎯 Trust Model & Principles

Switchboard is designed around a **Zero-Password, Local-First Trust Model**:
1. **No External Dependencies**: Cryptographic handshakes, key derivations, and message passing occur exclusively within the local network (LAN / Wi-Fi). No third-party relay or cloud identity provider is ever contacted.
2. **Asymmetric Identity**: Both host and mobile client generate long-term cryptographic identities based on Curve25519 (X25519).
3. **Out-of-Band Verification**: Pairing credentials (ephemeral public keys and verification codes) are delivered visually via a high-density QR code displayed on the physical monitor, ensuring physical proximity.
4. **Perfect Forward Secrecy (PFS)**: Every active connection derives unique ephemeral session keys via ECDH. Compromise of a long-term storage token cannot retroactively decrypt recorded past traffic.
5. **Mutual Revocability**: Either host or client can unilaterally invalidate trust at any time, instantly purging local session keys and refusing subsequent connections.

---

## 🔑 Cryptographic Primitives

| Purpose | Algorithm / Primitive | Standard |
| :--- | :--- | :--- |
| **Key Agreement (ECDH)** | X25519 (Curve25519 scalar multiplication) | RFC 7748 |
| **Key Derivation (KDF)** | HKDF-SHA256 (HMAC-based Key Derivation) | RFC 5869 |
| **Authenticated Encryption** | ChaCha20-Poly1305 / AES-256-GCM (AEAD) | RFC 8439 / NIST SP 800-38D |
| **Integrity Checksums** | SHA-256 | FIPS 180-4 |
| **Pairing Codes** | Alphanumeric Crockford Base32 (10 chars) | Custom unambiguous alphabet |

---

## 📱 Pairing Flow & Key Exchange

### 1. Host Identity & Ephemeral Pairing Token
When the desktop daemon initializes:
1. It loads or generates a stable host identity key pair: $(sk_{host}, pk_{host})$.
2. It generates an ephemeral pairing code `code` (e.g., `0XTC6D1B0F`) with a rolling 5-minute expiration window.
3. It bundles the host connection coordinates and public key into a JSON QR payload:

```json
{
  "v": 1,
  "daemonId": "d19795f7-fa89-4779-abb1-320207c92dcf",
  "hostName": "ROOT",
  "host": "192.168.1.105",
  "port": 9427,
  "hostKey": "ML9il-2-Tgvzofi5--15FjxqCPJktG1r4J80TrsjcHs",
  "code": "0XTC6D1B0F"
}
```

### 2. Client Key Generation & Handshake Request
1. The Android client opens its camera scanner and reads the QR code (or receives the host IP and 10-character code via manual entry).
2. The client generates its own ephemeral key pair: $(sk_{client\_eph}, pk_{client\_eph})$.
3. The client opens a WebSocket connection to `ws://{host}:{port}/ws`.
4. The client initiates the cryptographic handshake by sending a `pair_request` envelope:

```json
{
  "type": "pair_request",
  "payload": {
    "code": "0XTC6D1B0F",
    "clientPublicKey": "<base64-encoded X25519 client public key>",
    "deviceName": "Google Pixel 8 Pro",
    "deviceId": "<stable-uuid>"
  }
}
```

### 3. Key Agreement & Shared Secret Derivation
1. The host validates that `code` matches its currently active pairing code and has not expired.
2. The host computes the raw shared ECDH secret:
   $$Z = \text{X25519}(sk_{host}, pk_{client\_eph})$$
3. The client independently computes the identical secret:
   $$Z = \text{X25519}(sk_{client\_eph}, pk_{host})$$
4. Both sides pass the shared secret through HKDF-SHA256 with contextual salt and application info (`"Switchboard-v1-Session"`):
   $$K_{session} = \text{HKDF-Expand}(\text{HKDF-Extract}(\text{salt}, Z), \text{"Switchboard-v1-Session"}, 32)$$
5. The host persists the client’s identity in SQLite, immediately rotates the pairing code (so the displayed QR code cannot be reused by another party), and returns an authenticated confirmation:

```json
{
  "type": "pair_response",
  "payload": {
    "status": "authenticated",
    "daemonId": "d19795f7-fa89-4779-abb1-320207c92dcf",
    "token": "<secure-session-token>"
  }
}
```

---

## 🔒 Encrypted Session Communication

Once paired, all WebSocket messages and REST streaming endpoints are encrypted using AEAD:

### Encrypted Message Frame Structure
```
┌─────────────────────────────────────────────────────────────┐
│ Nonce (12 bytes: 96-bit monotonically incrementing counter) │
├─────────────────────────────────────────────────────────────┤
│ Ciphertext (ChaCha20 encrypted JSON payload)                │
├─────────────────────────────────────────────────────────────┤
│ Poly1305 Authentication Tag (16 bytes)                      │
└─────────────────────────────────────────────────────────────┘
```

### Replay & Tamper Protection
- **Monotonic Nonces**: Each transmission increments a 64-bit sequence counter. Out-of-order packets or duplicate counter values are rejected immediately.
- **AEAD Integrity**: Any alteration of ciphertext or message headers triggers an AEAD tag mismatch, terminating the session immediately.

---

## 🛡️ Identity Storage & Revocation

### Host Storage
- Paired client public keys, device UUIDs, and friendly names are persisted in local SQLite (`host.db`).
- Private keys never touch disk unencrypted.
- The desktop interface provides an immediate **Revoke** button (trash icon). Revoking a client immediately removes its public key from SQLite and actively severs any ongoing WebSocket connections.

### Android Storage
- Client keys and paired host credentials are saved in **Android Keystore** backed by hardware-backed TEE / StrongBox (via `EncryptedSharedPreferences`).
- Tapping **Forget Host** inside the Android client purges the stored keys from the Keystore, requiring physical re-pairing to re-establish access.

---

## 👆 Fingerprint Unlock Keys

Remote unlock rides on the session above but does not trust it for the one
thing that matters: whether a finger was actually presented. A paired phone can
already send any command, so an unlock frame proves nothing about the user
holding it.

The claim is therefore made with a second key, separate from the session.

- **Curve:** ECDSA **P-256**, chosen because every Android keystore holds it in
  hardware and Go verifies it from the standard library. The host pins the
  curve and rejects anything else.
- **Generation:** in the phone's hardware keystore with
  `setUserAuthenticationRequired`, so the private half is unusable until a
  class 3 biometric prompt succeeds. Signing runs inside the prompt's
  `CryptoObject`, making a signature and an authentication one event.
- **Invalidation:** `setInvalidatedByBiometricEnrollment` destroys the key when
  a new fingerprint is enrolled on the phone.
- **Host storage:** the public half only, in the `unlock_keys` table, keyed by
  device. Revoking a device deletes it, so re-pairing does not restore access.
- **Enrolment:** refused while the desktop is locked. Pairing happens with the
  owner at the machine; a phone taken afterwards must not be able to grant
  itself the lock screen.
- **Signed message:**
  `"switchboard-unlock-v1" ‖ 0x00 ‖ daemonId ‖ 0x00 ‖ challenge`. The label is
  domain separation; the daemon ID binds the proof to one host. The 32-byte
  challenge is spent when checked, pass or fail.

The host-side cost — a stored Windows password and a `SYSTEM` helper — is
described in full in [Remote Unlock](./remote-unlock.md), including who can
read that password and what the feature does not defend against.

---

## 🛑 Threat Analysis & Mitigations

| Threat | Risk Level | Mitigation in Switchboard |
| :--- | :--- | :--- |
| **Passive Wi-Fi Sniffing** | High | All payload bytes are sealed with AEAD encryption; eavesdroppers see only opaque ciphertext. |
| **Man-In-The-Middle (MITM)** | High | The QR code conveys the host's actual public key out-of-band via physical screen capture. |
| **Replay Attacks** | Medium | Strict monotonically incrementing 96-bit nonces reject replayed frames. |
| **Shoulder Surfing** | Low | Pairing codes expire every 5 minutes and rotate instantly once used. |
| **Malicious Desktop Page** | Medium | Desktop renderer has no direct network access; context isolation prevents rogue scripts from talking to LAN. |
| **Stolen unlocked phone** | Medium | Unlock needs the owner's fingerprint, not just the app: the signing key is released by the keystore, not by application code. |
| **Tampered Android build** | Medium | A patched APK can skip its own biometric prompt but still cannot use a key the keystore gates on hardware authentication. |
| **Unlock proof replayed elsewhere** | Low | The signature covers the daemon ID and a single-use nonce, so it opens one desktop once. |
| **Code running as the console user** | Accepted | Such code can trigger an unlock via the local pipe. It inherits that account's trust rather than crossing a new boundary; see [Remote Unlock](./remote-unlock.md). |
