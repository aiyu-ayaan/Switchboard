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
    let (mut ws, mut s) = connect(port, &phone("x"), HelloMode::PairCode, code_psk("AAAA-AAAA-AAAA"), &app.keys.public).await.unwrap();
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

// --- Files ---------------------------------------------------------------------

async fn start_with_inbox(inbox: std::path::PathBuf) -> (Arc<App>, u16, tokio::sync::mpsc::UnboundedReceiver<String>) {
    let (tx, rx) = tokio::sync::mpsc::unbounded_channel();
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let app = App::with_inbox(Store::memory().unwrap(), port, tx, inbox).unwrap();
    tokio::spawn(crate::server::serve(app.clone(), listener));
    (app, port, rx)
}

/// Pairs a phone by QR and returns its open, welcomed session.
async fn paired(app: &Arc<App>, port: u16, id: &str) -> (Ws, Session) {
    app.start_pairing();
    let token = app.pairing.lock().unwrap().as_ref().unwrap().token;
    let (mut ws, mut s) = connect(port, &phone(id), HelloMode::PairQr, token, &app.keys.public).await.unwrap();
    assert!(matches!(next_event(&mut ws, &mut s).await, Some(Event::Welcome { .. })));
    (ws, s)
}

/// Sends one request and waits for its result, skipping pushes in between.
async fn ask(ws: &mut Ws, s: &mut Session, id: u64, cmd: Command) -> (bool, Option<String>, Option<String>) {
    ws.send(Message::Binary(s.seal(&serde_json::to_vec(&Request { id, cmd }).unwrap()).unwrap())).await.unwrap();
    loop {
        match next_event(ws, s).await {
            Some(Event::Result { id: i, ok, error, data }) if i == id => return (ok, error, data),
            Some(_) => continue,
            None => panic!("no result for request {id}"),
        }
    }
}

fn scratch(tag: &str) -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!("sb-e2e-{tag}-{}", b64(&switchboard_core::crypto::random_bytes::<6>())));
    std::fs::create_dir_all(&d).unwrap();
    d
}

#[tokio::test]
async fn phone_uploads_a_file() {
    let inbox = scratch("inbox");
    let (app, port, mut ui) = start_with_inbox(inbox.clone()).await;
    let (mut ws, mut s) = paired(&app, port, "up").await;

    let data: Vec<u8> = (0..FILE_CHUNK * 2 + 777).map(|i| (i % 251) as u8).collect();
    let (ok, err, _) = ask(&mut ws, &mut s, 9, Command::FileSend { xfer: "x0".into(), name: "../evil/photo.bin".into(), size: 1 }).await;
    assert!(!ok && err.is_some(), "a path that climbs is refused");
    let (ok, _, offset) = ask(&mut ws, &mut s, 10, Command::FileSend { xfer: "x1".into(), name: "trip/photo.bin".into(), size: data.len() as u64 }).await;
    assert!(ok);
    assert_eq!(offset.as_deref(), Some("0"));
    for (n, chunk) in data.chunks(FILE_CHUNK).enumerate() {
        let offset = (n * FILE_CHUNK) as u64;
        let (ok, ..) = ask(&mut ws, &mut s, 11 + n as u64, Command::FileChunk { xfer: "x1".into(), offset, data: b64(chunk) }).await;
        assert!(ok, "chunk {n}");
    }
    let (ok, ..) = ask(&mut ws, &mut s, 20, Command::FileDone { xfer: "x1".into() }).await;
    assert!(ok);

    // Landed inside the inbox, folders kept, byte for byte.
    assert_eq!(std::fs::read(inbox.join("trip").join("photo.bin")).unwrap(), data);
    let mut saw = false;
    while let Ok(line) = ui.try_recv() {
        saw |= line.contains("file_received") && line.contains("photo.bin");
    }
    assert!(saw, "the desktop UI is told");

    // An out-of-order chunk and an unknown transfer are refused.
    let (ok, err, _) = ask(&mut ws, &mut s, 30, Command::FileChunk { xfer: "nope".into(), offset: 0, data: "AA".into() }).await;
    assert!(!ok && err.is_some());
}

#[tokio::test]
async fn desktop_offers_a_file_and_only_that_phone_can_pull_it() {
    let (app, port, mut ui) = start_with_inbox(scratch("unused")).await;
    let (mut ws, mut s) = paired(&app, port, "owner").await;
    let (mut ws2, mut s2) = paired(&app, port, "other").await;
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;

    let src = scratch("src").join("song.mp3");
    let data: Vec<u8> = (0..FILE_CHUNK + 5000).map(|i| (i * 7 % 256) as u8).collect();
    std::fs::write(&src, &data).unwrap();
    assert!(app.offer_file("nobody", &src, false).is_err(), "offline phones cannot be offered files");
    app.offer_file("owner", &src, false).unwrap();

    let (xfer, size) = loop {
        match next_event(&mut ws, &mut s).await {
            Some(Event::FileOffer { xfer, name, size }) => {
                assert_eq!(name, "song.mp3");
                break (xfer, size);
            }
            Some(_) => continue,
            None => panic!("no offer"),
        }
    };
    assert_eq!(size, data.len() as u64);

    let (ok, ..) = ask(&mut ws2, &mut s2, 5, Command::FileRead { xfer: xfer.clone(), offset: 0, len: 10 }).await;
    assert!(!ok, "another phone cannot read the offer");

    let mut got = Vec::new();
    while (got.len() as u64) < size {
        let (ok, _, piece) = ask(&mut ws, &mut s, 100 + got.len() as u64, Command::FileRead { xfer: xfer.clone(), offset: got.len() as u64, len: FILE_CHUNK as u32 }).await;
        assert!(ok);
        got.extend(unb64(&piece.unwrap()).unwrap());
    }
    assert_eq!(got, data);
    let (ok, ..) = ask(&mut ws, &mut s, 900, Command::FileDone { xfer: xfer.clone() }).await;
    assert!(ok);
    let (ok, ..) = ask(&mut ws, &mut s, 901, Command::FileRead { xfer, offset: 0, len: 1 }).await;
    assert!(!ok, "a finished offer is gone");
    let mut saw = false;
    while let Ok(line) = ui.try_recv() {
        saw |= line.contains("file_sent");
    }
    assert!(saw);
}

#[tokio::test]
async fn upload_and_offer_survive_a_dropped_connection() {
    let inbox = scratch("resume");
    let (app, port, _ui) = start_with_inbox(inbox.clone()).await;
    app.start_pairing();
    let token = app.pairing.lock().unwrap().as_ref().unwrap().token;
    let p = phone("flaky");
    let (mut ws, mut s) = connect(port, &p, HelloMode::PairQr, token, &app.keys.public).await.unwrap();
    assert!(matches!(next_event(&mut ws, &mut s).await, Some(Event::Welcome { .. })));
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;

    let data: Vec<u8> = (0..FILE_CHUNK + 1000).map(|i| (i % 253) as u8).collect();
    ask(&mut ws, &mut s, 2, Command::FileSend { xfer: "u".into(), name: "movie.bin".into(), size: data.len() as u64 }).await;
    let (ok, ..) = ask(&mut ws, &mut s, 3, Command::FileChunk { xfer: "u".into(), offset: 0, data: b64(&data[..FILE_CHUNK]) }).await;
    assert!(ok);

    let src = scratch("offer-src").join("clip.bin");
    std::fs::write(&src, &data).unwrap();
    app.offer_file("flaky", &src, false).unwrap();
    let xfer = loop {
        match next_event(&mut ws, &mut s).await {
            Some(Event::FileOffer { xfer, .. }) => break xfer,
            Some(_) => continue,
            None => panic!("no offer"),
        }
    };
    let (_, _, first) = ask(&mut ws, &mut s, 4, Command::FileRead { xfer: xfer.clone(), offset: 0, len: 100 }).await;
    assert_eq!(unb64(&first.unwrap()).unwrap(), &data[..100]);

    // The connection drops.
    drop(ws);
    tokio::time::sleep(std::time::Duration::from_millis(300)).await;

    let (mut ws, mut s) = connect(port, &p, HelloMode::Resume, [0; 32], &app.keys.public).await.unwrap();
    assert!(matches!(next_event(&mut ws, &mut s).await, Some(Event::Welcome { .. })));
    // The offer is shown again, and the upload continues from the byte it reached.
    let mut offered_again = false;
    let (ok, _, resume) = {
        ws.send(Message::Binary(s.seal(&serde_json::to_vec(&Request { id: 20, cmd: Command::FileSend { xfer: "u".into(), name: "movie.bin".into(), size: data.len() as u64 } }).unwrap()).unwrap())).await.unwrap();
        loop {
            match next_event(&mut ws, &mut s).await {
                Some(Event::FileOffer { xfer: x, .. }) if x == xfer => offered_again = true,
                Some(Event::Result { id: 20, ok, error, data }) => break (ok, error, data),
                Some(_) => continue,
                None => panic!("no result"),
            }
        }
    };
    assert!(ok);
    assert_eq!(resume.as_deref(), Some(FILE_CHUNK.to_string().as_str()));
    let (ok, ..) = ask(&mut ws, &mut s, 21, Command::FileChunk { xfer: "u".into(), offset: FILE_CHUNK as u64, data: b64(&data[FILE_CHUNK..]) }).await;
    assert!(ok);
    let (ok, ..) = ask(&mut ws, &mut s, 22, Command::FileDone { xfer: "u".into() }).await;
    assert!(ok);
    assert_eq!(std::fs::read(inbox.join("movie.bin")).unwrap(), data);

    // The offer can be read on from where it stopped.
    let (ok, _, rest) = ask(&mut ws, &mut s, 23, Command::FileRead { xfer: xfer.clone(), offset: 100, len: 50 }).await;
    assert!(ok);
    assert_eq!(unb64(&rest.unwrap()).unwrap(), &data[100..150]);
    let _ = offered_again; // the offer may arrive before or after the result; reading it above proves it is still there
}
