//! Handshake and transport encryption.
//!
//! Both sides hold a long-term X25519 identity (static key). Every connection
//! adds a fresh ephemeral key on each side, and the session keys come from
//! three Diffie-Hellman results:
//!
//! ```text
//! dh1 = DH(client_eph,    host_static)   proves the host owns its identity
//! dh2 = DH(client_eph,    host_eph)      forward secrecy
//! dh3 = DH(client_static, host_static)   proves the phone owns its identity
//! keys = HKDF-SHA256(salt = psk, ikm = dh1 || dh2 || dh3, info = transcript)
//! ```
//!
//! `psk` is the one-time pairing secret during pairing (QR token or manual
//! code) and all zeros on a reconnect, where the pinned static keys alone
//! authenticate both ends. A side that derived different keys fails to
//! decrypt the first frame, so a wrong code or an impostor ends the
//! connection right there.
//!
//! Frames are ChaCha20-Poly1305 with an implicit per-direction counter as the
//! nonce. The WebSocket is ordered, so a replayed, dropped or reordered frame
//! fails authentication instead of being accepted.

use chacha20poly1305::aead::{Aead, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce};
use hkdf::Hkdf;
use rand_core::{OsRng, RngCore};
use sha2::{Digest, Sha256};
use x25519_dalek::{PublicKey, StaticSecret};
use zeroize::Zeroize;

pub const KEY_LEN: usize = 32;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum CryptoError {
    #[error("key must be {KEY_LEN} bytes")]
    BadKey,
    #[error("frame failed authentication")]
    BadFrame,
    #[error("nonce counter exhausted")]
    Exhausted,
}

/// An X25519 key pair. The secret is wiped on drop by `x25519-dalek`.
pub struct KeyPair {
    pub secret: StaticSecret,
    pub public: PublicKey,
}

impl KeyPair {
    pub fn generate() -> Self {
        let secret = StaticSecret::random_from_rng(OsRng);
        let public = PublicKey::from(&secret);
        Self { secret, public }
    }

    pub fn from_secret(bytes: &[u8]) -> Result<Self, CryptoError> {
        let secret = StaticSecret::from(to_array(bytes)?);
        let public = PublicKey::from(&secret);
        Ok(Self { secret, public })
    }
}

pub fn random_bytes<const N: usize>() -> [u8; N] {
    let mut out = [0u8; N];
    OsRng.fill_bytes(&mut out);
    out
}

pub fn to_array(bytes: &[u8]) -> Result<[u8; KEY_LEN], CryptoError> {
    bytes.try_into().map_err(|_| CryptoError::BadKey)
}

/// Hash of both hello messages exactly as they crossed the wire, so the keys
/// are bound to every field either side announced.
pub fn transcript(client_hello: &[u8], server_hello: &[u8]) -> [u8; 32] {
    let mut h = Sha256::new();
    h.update(b"switchboard/v1");
    h.update((client_hello.len() as u64).to_le_bytes());
    h.update(client_hello);
    h.update((server_hello.len() as u64).to_le_bytes());
    h.update(server_hello);
    h.finalize().into()
}

/// The pairing secret for a manually typed code. Case, spaces and dashes are
/// ignored so `ab3d-9kq2` and `AB3D9KQ2` are the same code.
pub fn code_psk(code: &str) -> [u8; 32] {
    let norm: String = code
        .chars()
        .filter(|c| c.is_ascii_alphanumeric())
        .map(|c| c.to_ascii_uppercase())
        .collect();
    let mut h = Sha256::new();
    h.update(b"switchboard/code");
    h.update(norm.as_bytes());
    h.finalize().into()
}

pub enum Role {
    Client,
    Host,
}

pub struct HandshakeKeys<'a> {
    pub local_static: &'a StaticSecret,
    pub local_eph: &'a StaticSecret,
    pub remote_static: &'a PublicKey,
    pub remote_eph: &'a PublicKey,
    pub psk: &'a [u8; 32],
    pub transcript: &'a [u8; 32],
}

/// One encrypted connection: a key and a counter for each direction.
pub struct Session {
    send: ChaCha20Poly1305,
    recv: ChaCha20Poly1305,
    send_ctr: u64,
    recv_ctr: u64,
}

impl Session {
    pub fn derive(role: Role, k: HandshakeKeys) -> Self {
        // Each DH is computed so both roles arrive at the same three values.
        let (dh1, dh2, dh3) = match role {
            Role::Client => (
                k.local_eph.diffie_hellman(k.remote_static),
                k.local_eph.diffie_hellman(k.remote_eph),
                k.local_static.diffie_hellman(k.remote_static),
            ),
            Role::Host => (
                k.local_static.diffie_hellman(k.remote_eph),
                k.local_eph.diffie_hellman(k.remote_eph),
                k.local_static.diffie_hellman(k.remote_static),
            ),
        };
        let mut ikm = [0u8; 96];
        ikm[..32].copy_from_slice(dh1.as_bytes());
        ikm[32..64].copy_from_slice(dh2.as_bytes());
        ikm[64..].copy_from_slice(dh3.as_bytes());

        let hk = Hkdf::<Sha256>::new(Some(k.psk), &ikm);
        let mut okm = [0u8; 64];
        hk.expand(k.transcript, &mut okm).expect("64 bytes is a valid HKDF length");
        ikm.zeroize();

        let c2h = ChaCha20Poly1305::new(Key::from_slice(&okm[..32]));
        let h2c = ChaCha20Poly1305::new(Key::from_slice(&okm[32..]));
        okm.zeroize();

        let (send, recv) = match role {
            Role::Client => (c2h, h2c),
            Role::Host => (h2c, c2h),
        };
        Self { send, recv, send_ctr: 0, recv_ctr: 0 }
    }

    fn nonce(ctr: u64) -> Nonce {
        let mut n = [0u8; 12];
        n[4..].copy_from_slice(&ctr.to_le_bytes());
        *Nonce::from_slice(&n)
    }

    pub fn seal(&mut self, plaintext: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let ctr = self.send_ctr;
        self.send_ctr = ctr.checked_add(1).ok_or(CryptoError::Exhausted)?;
        self.send
            .encrypt(&Self::nonce(ctr), plaintext)
            .map_err(|_| CryptoError::BadFrame)
    }

    pub fn open(&mut self, frame: &[u8]) -> Result<Vec<u8>, CryptoError> {
        let pt = self
            .recv
            .decrypt(&Self::nonce(self.recv_ctr), frame)
            .map_err(|_| CryptoError::BadFrame)?;
        // Only an authentic frame advances the counter.
        self.recv_ctr = self.recv_ctr.checked_add(1).ok_or(CryptoError::Exhausted)?;
        Ok(pt)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn pair(psk_client: [u8; 32], psk_host: [u8; 32], impostor: bool) -> (Session, Session) {
        let host_s = KeyPair::generate();
        let host_e = KeyPair::generate();
        let phone_s = KeyPair::generate();
        let phone_e = KeyPair::generate();
        let fake = KeyPair::generate();
        let t = transcript(b"client", b"server");
        // An impostor host answers with its own static key while the phone
        // expects the pinned one.
        let host_secret = if impostor { &fake.secret } else { &host_s.secret };
        let client = Session::derive(
            Role::Client,
            HandshakeKeys {
                local_static: &phone_s.secret,
                local_eph: &phone_e.secret,
                remote_static: &host_s.public,
                remote_eph: &host_e.public,
                psk: &psk_client,
                transcript: &t,
            },
        );
        let host = Session::derive(
            Role::Host,
            HandshakeKeys {
                local_static: host_secret,
                local_eph: &host_e.secret,
                remote_static: &phone_s.public,
                remote_eph: &phone_e.public,
                psk: &psk_host,
                transcript: &t,
            },
        );
        (client, host)
    }

    #[test]
    fn round_trip_both_directions() {
        let (mut c, mut h) = pair([7; 32], [7; 32], false);
        for i in 0..5u8 {
            let f = c.seal(&[i; 10]).unwrap();
            assert_eq!(h.open(&f).unwrap(), vec![i; 10]);
            let g = h.seal(b"pong").unwrap();
            assert_eq!(c.open(&g).unwrap(), b"pong");
        }
    }

    #[test]
    fn wrong_code_fails_first_frame() {
        let (mut c, mut h) = pair(code_psk("AB3D-9KQ2-7XMH"), code_psk("ab3d9kq27xmj"), false);
        assert_eq!(h.open(&c.seal(b"x").unwrap()), Err(CryptoError::BadFrame));
    }

    #[test]
    fn code_normalisation() {
        assert_eq!(code_psk("ab3d-9kq2"), code_psk(" AB3D 9KQ2 "));
    }

    #[test]
    fn impostor_host_fails() {
        let (mut c, mut h) = pair([0; 32], [0; 32], true);
        assert_eq!(h.open(&c.seal(b"x").unwrap()), Err(CryptoError::BadFrame));
    }

    #[test]
    fn replay_and_reorder_rejected() {
        let (mut c, mut h) = pair([1; 32], [1; 32], false);
        let a = c.seal(b"a").unwrap();
        let b = c.seal(b"b").unwrap();
        assert!(h.open(&b).is_err(), "out of order");
        assert_eq!(h.open(&a).unwrap(), b"a");
        assert!(h.open(&a).is_err(), "replay");
        assert_eq!(h.open(&b).unwrap(), b"b");
    }

    #[test]
    fn transcript_binds_hellos() {
        assert_ne!(transcript(b"ab", b"c"), transcript(b"a", b"bc"));
    }
}
