//! Switchboard desktop daemon.
//!
//! `switchboard --stdio` is how the desktop app runs it: control on
//! stdin/stdout, exits when stdin closes. Plain `switchboard` runs headless
//! and prints a pairing code when no phone is paired yet.
//!
//! Environment: `SWITCHBOARD_PROFILE=dev` (port 9428 and a separate data
//! directory, so a dev build never touches the installed app's pairings),
//! `SWITCHBOARD_PORT`, `SWITCHBOARD_DATA_DIR`.

mod app;
mod control;
mod discovery;
mod files;
mod pairing;
mod server;
mod store;
mod system;
#[cfg(test)]
mod e2e_tests;

use std::path::PathBuf;
use std::time::Duration;

use anyhow::{Context, Result};
use switchboard_core::protocol::DEFAULT_PORT;
use tokio::io::AsyncWriteExt;
use tokio::sync::mpsc;
use tracing_subscriber::EnvFilter;

fn data_dir(dev: bool) -> PathBuf {
    if let Some(d) = std::env::var_os("SWITCHBOARD_DATA_DIR") {
        return d.into();
    }
    let base = dirs::data_dir().unwrap_or_else(|| PathBuf::from("."));
    base.join(if dev { "switchboard-dev" } else { "switchboard" })
}

#[tokio::main]
async fn main() -> Result<()> {
    // Logs go to stderr; stdout belongs to the control channel.
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")))
        .with_writer(std::io::stderr)
        .init();

    let stdio = std::env::args().any(|a| a == "--stdio");
    let dev = std::env::var("SWITCHBOARD_PROFILE").as_deref() == Ok("dev");
    let port: u16 = match std::env::var("SWITCHBOARD_PORT") {
        Ok(p) => p.parse().context("SWITCHBOARD_PORT must be a port number")?,
        Err(_) if dev => DEFAULT_PORT + 1,
        Err(_) => DEFAULT_PORT,
    };
    let dir = data_dir(dev);
    let store = store::Store::open(&dir.join("switchboard.db"))?;

    let (ui_tx, mut ui_rx) = mpsc::unbounded_channel::<String>();
    let app = app::App::new(store, port, ui_tx)?;

    tokio::spawn(async move {
        let mut out = tokio::io::stdout();
        while let Some(line) = ui_rx.recv().await {
            if stdio {
                let _ = out.write_all(line.as_bytes()).await;
                let _ = out.write_all(b"\n").await;
                let _ = out.flush().await;
            }
        }
    });

    let listener = match tokio::net::TcpListener::bind(("0.0.0.0", port)).await {
        Ok(l) => l,
        Err(e) => {
            app.emit("fatal", serde_json::json!(format!("Port {port} is in use. Is Switchboard already running? ({e})")));
            tokio::time::sleep(Duration::from_millis(200)).await;
            std::process::exit(2);
        }
    };
    tracing::info!("listening on :{port}, data in {}", dir.display());
    app.advertise();
    tokio::spawn(server::poll_state(app.clone()));
    app.emit("ready", app.status());

    if stdio {
        tokio::select! {
            r = server::serve(app.clone(), listener) => r?,
            _ = control::run(app.clone()) => tracing::info!("control channel closed; exiting"),
        }
    } else {
        if app.store.devices()?.is_empty() {
            app.start_pairing();
            if let Some(p) = app.pairing.lock().unwrap().as_ref() {
                eprintln!("\nPair a phone with code {}  (or open {})\n", p.code, app.pair_uri(p));
            }
        }
        server::serve(app, listener).await?;
    }
    Ok(())
}
