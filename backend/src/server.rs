//! The phone-facing WebSocket: handshake, then encrypted request/event frames.

use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use axum::Router;
use axum::extract::ws::{Message, WebSocket, WebSocketUpgrade};
use axum::extract::{ConnectInfo, State};
use axum::response::IntoResponse;
use axum::routing::get;
use serde_json::json;
use switchboard_core::crypto::{HandshakeKeys, KeyPair, Role, Session, to_array, transcript};
use switchboard_core::protocol::{ClientHello, Command, Event, HelloMode, PROTOCOL_VERSION, RejectReason, Request, ServerHello};
use switchboard_core::{b64, unb64};
use tokio::sync::mpsc;
use tokio::time::timeout;
use x25519_dalek::PublicKey;

use crate::app::{App, Outbound};
use crate::files;

const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);
/// The phone pings every 15 s; twice that with nothing means it is gone.
const IDLE_TIMEOUT: Duration = Duration::from_secs(40);

pub fn router(app: Arc<App>) -> Router {
    Router::new().route("/ws", get(ws)).route("/info", get(info)).with_state(app)
}

pub async fn serve(app: Arc<App>, listener: tokio::net::TcpListener) -> Result<()> {
    axum::serve(listener, router(app).into_make_service_with_connect_info::<SocketAddr>()).await?;
    Ok(())
}

/// Unauthenticated probe: lets a phone confirm an address is this desktop
/// and learn its key for typed-code pairing. A forged key cannot pair: the
/// code is mixed into the session keys, so the first frame would not open.
async fn info(State(app): State<Arc<App>>) -> impl IntoResponse {
    axum::Json(json!({ "id": app.host_id, "name": app.name(), "pk": app.pk_b64(), "v": PROTOCOL_VERSION }))
}

async fn ws(State(app): State<Arc<App>>, ConnectInfo(peer): ConnectInfo<SocketAddr>, up: WebSocketUpgrade) -> impl IntoResponse {
    up.max_message_size(1 << 20).on_upgrade(move |sock| async move {
        if let Err(e) = connection(app, sock).await {
            tracing::info!("{peer}: {e:#}");
        }
    })
}

async fn recv_text(sock: &mut WebSocket) -> Result<String> {
    match timeout(HANDSHAKE_TIMEOUT, sock.recv()).await {
        Ok(Some(Ok(Message::Text(t)))) => Ok(t),
        Ok(_) => bail!("expected a hello"),
        Err(_) => bail!("handshake timed out"),
    }
}

async fn recv_binary(sock: &mut WebSocket) -> Result<Vec<u8>> {
    match timeout(HANDSHAKE_TIMEOUT, sock.recv()).await {
        Ok(Some(Ok(Message::Binary(b)))) => Ok(b),
        Ok(_) => bail!("expected an encrypted frame"),
        Err(_) => bail!("handshake timed out"),
    }
}

async fn reject(sock: &mut WebSocket, reason: RejectReason) -> Result<()> {
    let _ = sock.send(Message::Text(serde_json::to_string(&ServerHello::Reject { reason })?)).await;
    let _ = sock.send(Message::Close(None)).await;
    bail!("rejected: {reason:?}")
}

async fn send(sock: &mut WebSocket, sess: &mut Session, ev: &Event) -> Result<()> {
    let frame = sess.seal(&serde_json::to_vec(ev)?)?;
    sock.send(Message::Binary(frame)).await.context("send")
}

fn key(s: &str) -> Option<PublicKey> {
    unb64(s).and_then(|b| to_array(&b).ok()).map(PublicKey::from)
}

async fn connection(app: Arc<App>, mut sock: WebSocket) -> Result<()> {
    // 1. ClientHello, plaintext.
    let ch_text = recv_text(&mut sock).await?;
    let Ok(hello) = serde_json::from_str::<ClientHello>(&ch_text) else { return reject(&mut sock, RejectReason::Malformed).await };
    if hello.v != PROTOCOL_VERSION {
        return reject(&mut sock, RejectReason::Version).await;
    }
    let (Some(phone_static), Some(phone_eph)) = (key(&hello.static_pk), key(&hello.eph_pk)) else {
        return reject(&mut sock, RejectReason::Malformed).await;
    };
    let name: String = hello.device_name.chars().take(64).collect();
    let pairing = hello.mode != HelloMode::Resume;

    let psk = match hello.mode {
        HelloMode::Resume => match app.store.device(&hello.device_id)? {
            Some(d) if d.static_pk == *phone_static.as_bytes() => [0u8; 32],
            _ => return reject(&mut sock, RejectReason::Unknown).await,
        },
        mode => {
            let psk = app.pairing.lock().unwrap().as_ref().filter(|p| p.live()).map(|p| match mode {
                HelloMode::PairQr => p.token,
                _ => p.code_psk(),
            });
            match psk {
                Some(psk) => psk,
                None => return reject(&mut sock, RejectReason::NotPairing).await,
            }
        }
    };

    // 2. ServerHello with our ephemeral key, then derive.
    let eph = KeyPair::generate();
    let sh_text = serde_json::to_string(&ServerHello::Accept {
        v: PROTOCOL_VERSION,
        host_id: app.host_id.clone(),
        host_name: app.name(),
        eph_pk: b64(eph.public.as_bytes()),
    })?;
    sock.send(Message::Text(sh_text.clone())).await?;
    let t = transcript(ch_text.as_bytes(), sh_text.as_bytes());
    let mut sess = Session::derive(
        Role::Host,
        HandshakeKeys {
            local_static: &app.keys.secret,
            local_eph: &eph.secret,
            remote_static: &phone_static,
            remote_eph: &phone_eph,
            psk: &psk,
            transcript: &t,
        },
    );
    drop(eph);

    // 3. The phone's first frame must decrypt to Hello: proves the secret.
    let first = recv_binary(&mut sock).await?;
    let hello_req = sess.open(&first).ok().and_then(|pt| serde_json::from_slice::<Request>(&pt).ok());
    let Some(Request { id: hello_id, cmd: Command::Hello }) = hello_req else {
        if pairing {
            if let Some(p) = app.pairing.lock().unwrap().as_mut() {
                p.failures += 1;
            }
            app.emit("pair_failed", json!({ "name": name }));
            app.emit_status();
        }
        let _ = sock.send(Message::Close(None)).await;
        return Err(anyhow!("wrong pairing secret or key from {name}"));
    };

    if pairing {
        app.store.add_device(&hello.device_id, &name, phone_static.as_bytes())?;
        app.cancel_pairing();
        app.emit("paired", json!({ "id": hello.device_id, "name": name }));
        tracing::info!("paired {name}");
    } else {
        app.store.touch(&hello.device_id, &name)?;
    }

    let (tx, mut rx) = mpsc::unbounded_channel();
    let conn_id = app.register(&hello.device_id, tx);
    app.emit_status();
    app.poke.notify_one();

    let result = session(&app, &mut sock, &mut sess, hello_id, &hello.device_id, &mut rx).await;

    app.unregister(conn_id);
    let _ = app.store.touch(&hello.device_id, &name);
    app.emit_status();
    result
}

async fn session(
    app: &Arc<App>,
    sock: &mut WebSocket,
    sess: &mut Session,
    hello_id: u64,
    device_id: &str,
    rx: &mut mpsc::UnboundedReceiver<Outbound>,
) -> Result<()> {
    send(sock, sess, &Event::Welcome { host_id: app.host_id.clone(), host_name: app.name(), os: app.os.clone(), macs: crate::discovery::lan_macs() }).await?;
    send(sock, sess, &Event::Result { id: hello_id, ok: true, error: None, data: None }).await?;
    let mut state = app.state.subscribe();
    let first = (**state.borrow_and_update()).clone();
    send(sock, sess, &Event::State(Box::new(first))).await?;

    // Uploads are strictly ordered, so file commands run right here in the
    // loop; they vanish with the connection.
    let mut uploads = files::Uploads::new(app.inbox.clone(), device_id.to_owned(), app.parked.clone());
    // Offers this phone has not taken yet (it may have dropped and come back).
    for (xfer, name, size) in app.pending_offers(device_id) {
        send(sock, sess, &Event::FileOffer { xfer, name, size }).await?;
    }

    // Commands run concurrently so a slow DDC write never delays a volume
    // change; their results come back through this channel.
    let (done_tx, mut done_rx) = mpsc::unbounded_channel::<Event>();

    loop {
        tokio::select! {
            msg = timeout(IDLE_TIMEOUT, sock.recv()) => {
                let frame = match msg {
                    Ok(Some(Ok(Message::Binary(b)))) => b,
                    Ok(Some(Ok(Message::Ping(_) | Message::Pong(_)))) => continue,
                    Ok(Some(Ok(Message::Close(_)) | Err(_)) | None) => return Ok(()),
                    Ok(Some(Ok(Message::Text(_)))) => bail!("plaintext after handshake"),
                    Err(_) => bail!("idle timeout"),
                };
                let pt = sess.open(&frame).map_err(|_| anyhow!("frame failed authentication"))?;
                let req: Request = match serde_json::from_slice(&pt) {
                    Ok(r) => r,
                    Err(e) => { tracing::debug!("bad request: {e}"); continue }
                };
                if req.cmd == Command::Unpair {
                    app.forget(device_id)?;
                    app.emit_status();
                    send(sock, sess, &Event::Result { id: req.id, ok: true, error: None, data: None }).await?;
                    // forget() queued a Revoked for this connection; the
                    // next loop turn sends it and closes.
                    continue;
                }
                if matches!(req.cmd, Command::FileSend { .. } | Command::FileChunk { .. } | Command::FileDone { .. } | Command::FileCancel { .. } | Command::FileRead { .. }) {
                    let r = files::handle(app, device_id, &mut uploads, &req.cmd).await;
                    let ev = Event::Result { id: req.id, ok: r.is_ok(), error: r.as_ref().err().map(|e| format!("{e:#}")), data: r.ok().flatten() };
                    send(sock, sess, &ev).await?;
                    continue;
                }
                let app = app.clone();
                let done = done_tx.clone();
                tokio::spawn(async move {
                    let r = app.system.run(&req.cmd).await;
                    let _ = done.send(Event::Result { id: req.id, ok: r.is_ok(), error: r.as_ref().err().map(|e| format!("{e:#}")), data: r.ok().flatten() });
                    if !matches!(req.cmd, Command::Ping) {
                        app.poke.notify_one();
                    }
                });
            }
            Some(ev) = done_rx.recv() => send(sock, sess, &ev).await?,
            Ok(()) = state.changed() => {
                let s = (**state.borrow_and_update()).clone();
                send(sock, sess, &Event::State(Box::new(s))).await?;
            }
            Some(out) = rx.recv() => match out {
                Outbound::Revoked => {
                    send(sock, sess, &Event::Revoked).await?;
                    let _ = sock.send(Message::Close(None)).await;
                    return Ok(());
                }
                Outbound::Offer { xfer, name, size } => send(sock, sess, &Event::FileOffer { xfer, name, size }).await?,
            },
        }
    }
}

/// Polls the machine while at least one phone is connected and publishes
/// changes. Idle desktops do no work.
pub async fn poll_state(app: Arc<App>) {
    loop {
        if app.conns.lock().unwrap().is_empty() {
            app.poke.notified().await;
            continue;
        }
        let snap = app.system.snapshot().await;
        app.state.send_if_modified(|cur| {
            if **cur == snap {
                false
            } else {
                *cur = Arc::new(snap);
                true
            }
        });
        tokio::select! {
            _ = tokio::time::sleep(Duration::from_millis(1500)) => {}
            _ = app.poke.notified() => {
                // Let a burst of commands settle into one snapshot.
                tokio::time::sleep(Duration::from_millis(120)).await;
            }
        }
    }
}
