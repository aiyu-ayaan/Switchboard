//! JNI surface for `com.aiyu.switchboard.core.NativeCore`. Kotlin owns the
//! WebSocket and JSON; Rust owns every key and the frame cipher, so a session
//! key never exists as a Java byte array.

use std::sync::Mutex;

use jni::JNIEnv;
use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jlong};
use x25519_dalek::PublicKey;

use crate::crypto::{self, HandshakeKeys, KeyPair, Role, Session};

fn bytes(env: &JNIEnv, a: &JByteArray) -> Vec<u8> {
    env.convert_byte_array(a).unwrap_or_default()
}

fn out(env: &JNIEnv, b: &[u8]) -> jbyteArray {
    env.byte_array_from_slice(b).map(|a| a.into_raw()).unwrap_or(std::ptr::null_mut())
}

/// 64 bytes: secret (32) then public (32).
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_aiyu_switchboard_core_NativeCore_keypair(env: JNIEnv, _: JClass) -> jbyteArray {
    let kp = KeyPair::generate();
    let mut v = kp.secret.to_bytes().to_vec();
    v.extend_from_slice(kp.public.as_bytes());
    out(&env, &v)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_aiyu_switchboard_core_NativeCore_transcript(
    env: JNIEnv,
    _: JClass,
    client_hello: JByteArray,
    server_hello: JByteArray,
) -> jbyteArray {
    let t = crypto::transcript(&bytes(&env, &client_hello), &bytes(&env, &server_hello));
    out(&env, &t)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_aiyu_switchboard_core_NativeCore_codePsk(mut env: JNIEnv, _: JClass, code: JString) -> jbyteArray {
    let code: String = env.get_string(&code).map(Into::into).unwrap_or_default();
    out(&env, &crypto::code_psk(&code))
}

/// Returns a session handle, or 0 when a key has the wrong length.
#[unsafe(no_mangle)]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_aiyu_switchboard_core_NativeCore_sessionNew(
    env: JNIEnv,
    _: JClass,
    static_sk: JByteArray,
    eph_sk: JByteArray,
    remote_static_pk: JByteArray,
    remote_eph_pk: JByteArray,
    psk: JByteArray,
    transcript: JByteArray,
) -> jlong {
    let build = || -> Result<Session, crypto::CryptoError> {
        let s = KeyPair::from_secret(&bytes(&env, &static_sk))?;
        let e = KeyPair::from_secret(&bytes(&env, &eph_sk))?;
        let rs = PublicKey::from(crypto::to_array(&bytes(&env, &remote_static_pk))?);
        let re = PublicKey::from(crypto::to_array(&bytes(&env, &remote_eph_pk))?);
        let psk = crypto::to_array(&bytes(&env, &psk))?;
        let t = crypto::to_array(&bytes(&env, &transcript))?;
        Ok(Session::derive(
            Role::Client,
            HandshakeKeys {
                local_static: &s.secret,
                local_eph: &e.secret,
                remote_static: &rs,
                remote_eph: &re,
                psk: &psk,
                transcript: &t,
            },
        ))
    };
    match build() {
        Ok(s) => Box::into_raw(Box::new(Mutex::new(s))) as jlong,
        Err(_) => 0,
    }
}

fn with<R>(h: jlong, f: impl FnOnce(&mut Session) -> R) -> Option<R> {
    if h == 0 {
        return None;
    }
    // SAFETY: `h` came from `sessionNew` and Kotlin frees it exactly once.
    let m = unsafe { &*(h as *const Mutex<Session>) };
    m.lock().ok().map(|mut s| f(&mut s))
}

/// Returns null when the handle is invalid.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_aiyu_switchboard_core_NativeCore_seal(env: JNIEnv, _: JClass, h: jlong, pt: JByteArray) -> jbyteArray {
    let pt = bytes(&env, &pt);
    match with(h, |s| s.seal(&pt)) {
        Some(Ok(ct)) => out(&env, &ct),
        _ => std::ptr::null_mut(),
    }
}

/// Returns null when the frame fails authentication.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_aiyu_switchboard_core_NativeCore_open(env: JNIEnv, _: JClass, h: jlong, ct: JByteArray) -> jbyteArray {
    let ct = bytes(&env, &ct);
    match with(h, |s| s.open(&ct)) {
        Some(Ok(pt)) => out(&env, &pt),
        _ => std::ptr::null_mut(),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_aiyu_switchboard_core_NativeCore_sessionFree(_: JNIEnv, _: JClass, h: jlong) {
    if h != 0 {
        // SAFETY: see `with`.
        drop(unsafe { Box::from_raw(h as *mut Mutex<Session>) });
    }
}

/// Public key for a secret, so Kotlin never has to do curve maths.
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_aiyu_switchboard_core_NativeCore_publicKey(env: JNIEnv, _: JClass, sk: JByteArray) -> jbyteArray {
    match KeyPair::from_secret(&bytes(&env, &sk)) {
        Ok(kp) => out(&env, kp.public.as_bytes()),
        Err(_) => std::ptr::null_mut(),
    }
}

