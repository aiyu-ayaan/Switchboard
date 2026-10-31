//! Drives the real server with a phone-side client built from the core
//! crate, the same calls the Android app makes over JNI.

use std::sync::Arc;

use futures_util::{SinkExt, StreamExt};
use switchboard_core::crypto::{HandshakeKeys, KeyPair, Role, Session, code_psk, to_array, transcript};
use switchboard_core::protocol::*;
use switchboard_core::{b64, unb64};
use tokio::net::TcpStream;
use tokio_tungstenite::tungstenite::Message;
use tokio_tungstenite::{MaybeTlsStream, WebSocketStream};
use x25519_dalek::PublicKey;

use crate::app::App;
use crate::store::Store;

type Ws = WebSocketStream<MaybeTlsStream<TcpStream>>;

struct Phone {
    id: String,
    keys: KeyPair,
}

async fn start() -> (Arc<App>, u16) {
    let (tx, _rx) = tokio::sync::mpsc::unbounded_channel();
    std::mem::forget(_rx);
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let app = App::new(Store::memory().unwrap(), port, tx).unwrap();
    tokio::spawn(crate::server::serve(app.clone(), listener));
    tokio::spawn(crate::server::poll_state(app.clone()));
    (app, port)
}

/// Returns the open session, or the server's reject reason.
async fn connect(port: u16, phone: &Phone, mode: HelloMode, psk: [u8; 32], host_pk: &PublicKey) -> Result<(Ws, Session), String> {
    let (mut ws, _) = tokio_tungstenite::connect_async(format!("ws://127.0.0.1:{port}/ws")).await.unwrap();
    let eph = KeyPair::generate();
    let ch = serde_json::to_string(&ClientHello {
        v: PROTOCOL_VERSION,
        mode,
        device_id: phone.id.clone(),
        device_name: "Test phone".into(),
        static_pk: b64(phone.keys.public.as_bytes()),
        eph_pk: b64(eph.public.as_bytes()),
    })
    .unwrap();
    ws.send(Message::Text(ch.clone())).await.unwrap();
    let Some(Ok(Message::Text(sh))) = ws.next().await else { panic!("no server hello") };
    let host_eph = match serde_json::from_str::<ServerHello>(&sh).unwrap() {
        ServerHello::Accept { eph_pk, .. } => PublicKey::from(to_array(&unb64(&eph_pk).unwrap()).unwrap()),
        ServerHello::Reject { reason } => return Err(format!("{reason:?}")),
    };
    let t = transcript(ch.as_bytes(), sh.as_bytes());
    let mut s = Session::derive(
        Role::Client,
        HandshakeKeys { local_static: &phone.keys.secret, local_eph: &eph.secret, remote_static: host_pk, remote_eph: &host_eph, psk: &psk, transcript: &t },
    );
    let hello = s.seal(&serde_json::to_vec(&Request { id: 1, cmd: Command::Hello }).unwrap()).unwrap();
    ws.send(Message::Binary(hello)).await.unwrap();
    Ok((ws, s))
}

async fn next_event(ws: &mut Ws, s: &mut Session) -> Option<Event> {
    loop {
        match tokio::time::timeout(std::time::Duration::from_secs(5), ws.next()).await.ok()?? {
            Ok(Message::Binary(b)) => return serde_json::from_slice(&s.open(&b).ok()?).ok(),
            Ok(Message::Close(_)) | Err(_) => return None,
            _ => continue,
        }
    }
}

fn phone(id: &str) -> Phone {
    Phone { id: id.into(), keys: KeyPair::generate() }
}

#[tokio::test]
async fn pair_with_code_then_resume() {
    let (app, port) = start().await;
    app.start_pairing();
    let code = app.pairing.lock().unwrap().as_ref().unwrap().code.clone();
    let p = phone("phone-1");

    let (mut ws, mut s) = connect(port, &p, HelloMode::PairCode, code_psk(&code.to_lowercase()), &app.keys.public).await.unwrap();
    assert!(matches!(next_event(&mut ws, &mut s).await, Some(Event::Welcome { .. })));
    assert!(matches!(next_event(&mut ws, &mut s).await, Some(Event::Result { id: 1, ok: true, .. })));
    assert!(matches!(next_event(&mut ws, &mut s).await, Some(Event::State(_))));
    assert!(app.pairing.lock().unwrap().is_none(), "pairing is single-use");
    assert!(app.store.device("phone-1").unwrap().is_some());

    // A command round trip inside the encrypted channel.
    ws.send(Message::Binary(s.seal(&serde_json::to_vec(&Request { id: 7, cmd: Command::Ping }).unwrap()).unwrap())).await.unwrap();
    loop {
        match next_event(&mut ws, &mut s).await {
            Some(Event::Result { id: 7, ok, .. }) => break assert!(ok),
            Some(_) => continue,
            None => panic!("no ping result"),
        }
    }
    drop(ws);

    // Reconnect with no secret: pinned keys are enough.
    let (mut ws, mut s) = connect(port, &p, HelloMode::Resume, [0; 32], &app.keys.public).await.unwrap();
    assert!(matches!(next_event(&mut ws, &mut s).await, Some(Event::Welcome { .. })));
}

#[tokio::test]
async fn pair_with_qr_token() {
    let (app, port) = start().await;
    app.start_pairing();
    let token = app.pairing.lock().unwrap().as_ref().unwrap().token;
    let (mut ws, mut s) = connect(port, &phone("qr"), HelloMode::PairQr, token, &app.keys.public).await.unwrap();
    assert!(matches!(next_event(&mut ws, &mut s).await, Some(Event::Welcome { .. })));
}

#[tokio::test]
async fn wrong_code_is_counted_and_refused() {
    let (app, port) = start().await;
    app.start_pairing();
    let (mut ws, mut s) = connect(port, &phone("x"), HelloMode::PairCode, code_psk("AAAA-AAAA"), &app.keys.public).await.unwrap();
    assert!(next_event(&mut ws, &mut s).await.is_none());
    assert_eq!(app.pairing.lock().unwrap().as_ref().unwrap().failures, 1);
    assert!(app.store.device("x").unwrap().is_none());
}

#[tokio::test]
async fn impostor_host_key_fails() {
    let (app, port) = start().await;
    app.start_pairing();
    let code = app.pairing.lock().unwrap().as_ref().unwrap().code.clone();
    let fake = KeyPair::generate();
    let (mut ws, mut s) = connect(port, &phone("y"), HelloMode::PairCode, code_psk(&code), &fake.public).await.unwrap();
    assert!(next_event(&mut ws, &mut s).await.is_none());
}

#[tokio::test]
async fn pairing_closed_and_unknown_resume_rejected() {
    let (app, port) = start().await;
    let r = connect(port, &phone("z"), HelloMode::PairQr, [0; 32], &app.keys.public).await;
    assert_eq!(r.err().as_deref(), Some("NotPairing"));
    let r = connect(port, &phone("z"), HelloMode::Resume, [0; 32], &app.keys.public).await;
    assert_eq!(r.err().as_deref(), Some("Unknown"));
}

#[tokio::test]
async fn forget_revokes_live_session() {
    let (app, port) = start().await;
    app.start_pairing();
    let token = app.pairing.lock().unwrap().as_ref().unwrap().token;
    let p = phone("gone");
    let (mut ws, mut s) = connect(port, &p, HelloMode::PairQr, token, &app.keys.public).await.unwrap();
    assert!(matches!(next_event(&mut ws, &mut s).await, Some(Event::Welcome { .. })));
    // Let the server register the connection before forgetting.
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;
    assert!(app.forget("gone").unwrap());
    loop {
        match next_event(&mut ws, &mut s).await {
            Some(Event::Revoked) => break,
            Some(_) => continue,
            None => panic!("no revoke"),
        }
    }
    let r = connect(port, &p, HelloMode::Resume, [0; 32], &app.keys.public).await;
    assert_eq!(r.err().as_deref(), Some("Unknown"));
}

#[tokio::test]
async fn phone_unpair_removes_device() {
    let (app, port) = start().await;
    app.start_pairing();
    let token = app.pairing.lock().unwrap().as_ref().unwrap().token;
    let (mut ws, mut s) = connect(port, &phone("bye"), HelloMode::PairQr, token, &app.keys.public).await.unwrap();
    assert!(matches!(next_event(&mut ws, &mut s).await, Some(Event::Welcome { .. })));
    ws.send(Message::Binary(s.seal(&serde_json::to_vec(&Request { id: 9, cmd: Command::Unpair }).unwrap()).unwrap())).await.unwrap();
    loop {
        match next_event(&mut ws, &mut s).await {
            Some(Event::Revoked) => break,
            Some(_) => continue,
            None => panic!("no revoke"),
        }
    }
    assert!(app.store.device("bye").unwrap().is_none());
}
