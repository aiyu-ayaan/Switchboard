//! The desktop UI drives the daemon over stdin/stdout, one JSON object per
//! line. Nothing listens on a port for this, so no other local process can
//! start a pairing or forget a phone.
//!
//! In:  `{"id": 1, "cmd": "pair_start"}`
//! Out: `{"id": 1, "ok": true, "data": ...}` and `{"event": "status", "data": ...}`

use std::sync::Arc;

use serde::Deserialize;
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, BufReader};

use switchboard_core::protocol::Command;

use crate::app::App;

// Arguments share the line with the request `id`, so no command may take
// an argument called `id`.
#[derive(Deserialize)]
struct Input {
    id: u64,
    #[serde(flatten)]
    cmd: Cmd,
}

#[derive(Deserialize)]
#[serde(tag = "cmd", rename_all = "snake_case")]
enum Cmd {
    Status,
    PairStart,
    PairCancel,
    Forget { device: String },
    Rename { name: String },
    /// What a phone would see right now, for the desktop's own dashboard.
    State,
    /// Offer files and folders (picked in the desktop's own dialog) to a
    /// connected phone. A folder is zipped first.
    SendFiles { device: String, paths: Vec<String> },
    /// Recent transfers, newest first.
    History,
    /// The folder phones' uploads are saved to.
    Inbox,
    /// Run a control (brightness, volume, media) from the desktop itself.
    Run { command: Command },
}

async fn handle(app: &Arc<App>, cmd: Cmd) -> anyhow::Result<Value> {
    Ok(match cmd {
        Cmd::Status => app.status(),
        Cmd::PairStart => {
            app.start_pairing();
            app.emit_status();
            app.status()
        }
        Cmd::PairCancel => {
            app.cancel_pairing();
            app.emit_status();
            Value::Null
        }
        Cmd::Forget { device } => {
            let removed = app.forget(&device)?;
            app.emit_status();
            json!({ "removed": removed })
        }
        Cmd::Rename { name } => {
            app.rename(&name)?;
            app.emit_status();
            Value::Null
        }
        Cmd::SendFiles { device, paths } => {
            let app2 = app.clone();
            let sent = tokio::task::spawn_blocking(move || -> anyhow::Result<usize> {
                for p in &paths {
                    let p = std::path::Path::new(p);
                    if p.is_dir() {
                        let (zip, _) = crate::files::zip_dir(p)?;
                        app2.offer_file(&device, &zip, true)?;
                    } else {
                        app2.offer_file(&device, p, false)?;
                    }
                }
                Ok(paths.len())
            })
            .await??;
            json!({ "sent": sent })
        }
        Cmd::History => json!(app.store.transfers(50)?),
        Cmd::Inbox => {
            std::fs::create_dir_all(&app.inbox)?;
            json!(app.inbox)
        }
        Cmd::State => serde_json::to_value(app.system.snapshot().await)?,
        Cmd::Run { command } => {
            let data = app.system.run(&command).await?;
            // Connected phones see the change on their next state push.
            app.poke.notify_one();
            json!(data)
        }
    })
}

/// Returns when stdin closes: the UI went away, so the daemon does too.
pub async fn run(app: Arc<App>) {
    let mut lines = BufReader::new(tokio::io::stdin()).lines();
    while let Ok(Some(line)) = lines.next_line().await {
        let input: Input = match serde_json::from_str(&line) {
            Ok(i) => i,
            Err(e) => {
                app.emit("error", json!(format!("bad control line: {e}")));
                continue;
            }
        };
        let slow = matches!(input.cmd, Cmd::State | Cmd::Run { .. } | Cmd::SendFiles { .. });
        let app2 = app.clone();
        let reply = async move {
            let reply = match handle(&app2, input.cmd).await {
                Ok(data) => json!({ "id": input.id, "ok": true, "data": data }),
                Err(e) => json!({ "id": input.id, "ok": false, "error": format!("{e:#}") }),
            };
            let _ = app2.ui.send(reply.to_string());
        };
        // Only the slow hardware read runs aside; everything else keeps the
        // order it was sent in, so a start and a cancel cannot swap places.
        if slow {
            tokio::spawn(reply);
        } else {
            reply.await;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn forget_line_keeps_request_id() {
        let i: Input = serde_json::from_str(r#"{"id":7,"cmd":"forget","device":"abc"}"#).unwrap();
        assert_eq!(i.id, 7);
        assert!(matches!(i.cmd, Cmd::Forget { device } if device == "abc"));
    }

    #[test]
    fn run_line_parses() {
        let i: Input = serde_json::from_str(r#"{"id":1,"cmd":"run","command":{"type":"set_volume","level":5}}"#).unwrap();
        assert!(matches!(i.cmd, Cmd::Run { .. }));
    }
}
