//! The one-time secret a phone needs to pair: a QR token or a typed code.

use std::time::{Duration, Instant};

use switchboard_core::crypto::{code_psk, random_bytes};

pub const TTL: Duration = Duration::from_secs(300);
pub const MAX_FAILURES: u8 = 5;
/// No 0/O, 1/I/L: the code is read off a screen and typed on a phone.
const ALPHABET: &[u8] = b"ABCDEFGHJKMNPQRSTUVWXYZ23456789";

pub struct Pairing {
    pub token: [u8; 32],
    pub code: String,
    pub expires: Instant,
    pub failures: u8,
}

impl Pairing {
    pub fn new() -> Self {
        let raw = random_bytes::<8>();
        let chars: String = raw.iter().map(|b| ALPHABET[*b as usize % ALPHABET.len()] as char).collect();
        Self {
            token: random_bytes(),
            code: format!("{}-{}", &chars[..4], &chars[4..]),
            expires: Instant::now() + TTL,
            failures: 0,
        }
    }

    pub fn live(&self) -> bool {
        Instant::now() < self.expires && self.failures < MAX_FAILURES
    }

    pub fn code_psk(&self) -> [u8; 32] {
        code_psk(&self.code)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn code_shape() {
        let p = Pairing::new();
        assert_eq!(p.code.len(), 9);
        assert_eq!(&p.code[4..5], "-");
        assert!(p.code.chars().filter(|c| *c != '-').all(|c| ALPHABET.contains(&(c as u8))));
        assert!(p.live());
    }

    #[test]
    fn too_many_failures_kills_it() {
        let mut p = Pairing::new();
        p.failures = MAX_FAILURES;
        assert!(!p.live());
    }
}
