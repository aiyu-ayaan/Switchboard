//! Protocol and crypto shared by the Switchboard desktop daemon and, over JNI,
//! the Android app.

pub mod crypto;
pub mod protocol;

#[cfg(target_os = "android")]
mod jni_bridge;

use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;

/// Keys and tokens travel as unpadded base64url everywhere (JSON, QR, mDNS TXT).
pub fn b64(bytes: &[u8]) -> String {
    URL_SAFE_NO_PAD.encode(bytes)
}

pub fn unb64(s: &str) -> Option<Vec<u8>> {
    URL_SAFE_NO_PAD.decode(s.trim()).ok()
}
