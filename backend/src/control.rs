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

use crate::app::App;

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
    Forget { id: String },
    Rename { name: String },
    /// What a phone would see right now, for the desktop's own dashboard.
    State,
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
        Cmd::Forget { id } => {
            let removed = app.forget(&id)?;
            app.emit_status();
            json!({ "removed": removed })
        }
        Cmd::Rename { name } => {
            app.rename(&name)?;
            app.emit_status();
            Value::Null
        }
        Cmd::State => serde_json::to_value(app.system.snapshot().await)?,
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
        let slow = matches!(input.cmd, Cmd::State);
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
