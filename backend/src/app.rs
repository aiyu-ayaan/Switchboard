//! Shared daemon state and the status the desktop UI renders.

use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::Serialize;
use serde_json::json;
use switchboard_core::b64;
use switchboard_core::crypto::KeyPair;
use switchboard_core::protocol::{HostState, PROTOCOL_VERSION};
use tokio::sync::{Notify, mpsc, watch};

use crate::discovery::{Advert, lan_addresses};
use crate::pairing::Pairing;
use crate::store::Store;
use crate::system::System;

pub enum Outbound {
    /// The desktop forgot this phone.
    Revoked,
}

pub struct Conn {
    pub device_id: String,
    pub tx: mpsc::UnboundedSender<Outbound>,
}

pub struct App {
    pub store: Store,
    pub host_id: String,
    pub keys: KeyPair,
    pub host_name: RwLock<String>,
    pub port: u16,
    pub os: String,
    pub pairing: Mutex<Option<Pairing>>,
    pub system: System,
    pub conns: Mutex<HashMap<u64, Conn>>,
    next_conn: AtomicU64,
    pub state: watch::Sender<Arc<HostState>>,
    /// Ask the poller for a fresh snapshot now (after a command, a connect).
    pub poke: Notify,
    /// Lines for the desktop UI (stdout in `--stdio` mode).
    pub ui: mpsc::UnboundedSender<String>,
    advert: Mutex<Option<Advert>>,
}

#[derive(Serialize)]
struct DeviceView {
    id: String,
    name: String,
    paired_at: i64,
    last_seen: i64,
    online: bool,
}

impl App {
    pub fn new(store: Store, port: u16, ui: mpsc::UnboundedSender<String>) -> anyhow::Result<Arc<Self>> {
        let (host_id, keys) = store.identity()?;
        let host_name = match store.host_name()? {
            Some(n) => n,
            None => sysinfo::System::host_name().unwrap_or_else(|| "My computer".into()),
        };
        let os = sysinfo::System::long_os_version().unwrap_or_else(|| std::env::consts::OS.into());
        Ok(Arc::new(Self {
            store,
            host_id,
            keys,
            host_name: RwLock::new(host_name),
            port,
            os,
            pairing: Mutex::new(None),
            system: System::default(),
            conns: Mutex::new(HashMap::new()),
            next_conn: AtomicU64::new(1),
            state: watch::channel(Arc::new(HostState::default())).0,
            poke: Notify::new(),
            ui,
            advert: Mutex::new(None),
        }))
    }

    pub fn name(&self) -> String {
        self.host_name.read().unwrap().clone()
    }

    pub fn pk_b64(&self) -> String {
        b64(self.keys.public.as_bytes())
    }

    pub fn advertise(&self) {
        let mut a = self.advert.lock().unwrap();
        if a.is_none() {
            *a = Advert::new();
        }
        if let Some(a) = a.as_mut() {
            a.publish(&self.host_id, &self.name(), &self.pk_b64(), self.port);
        }
    }

    pub fn rename(&self, name: &str) -> anyhow::Result<()> {
        let name = name.trim();
        anyhow::ensure!(!name.is_empty() && name.len() <= 64, "name must be 1-64 characters");
        self.store.set_host_name(name)?;
        *self.host_name.write().unwrap() = name.to_owned();
        self.advertise();
        Ok(())
    }

    pub fn register(&self, device_id: &str, tx: mpsc::UnboundedSender<Outbound>) -> u64 {
        let id = self.next_conn.fetch_add(1, Ordering::Relaxed);
        self.conns.lock().unwrap().insert(id, Conn { device_id: device_id.to_owned(), tx });
        id
    }

    pub fn unregister(&self, id: u64) {
        self.conns.lock().unwrap().remove(&id);
    }

    pub fn forget(&self, device_id: &str) -> anyhow::Result<bool> {
        let removed = self.store.remove_device(device_id)?;
        for c in self.conns.lock().unwrap().values().filter(|c| c.device_id == device_id) {
            let _ = c.tx.send(Outbound::Revoked);
        }
        Ok(removed)
    }

    pub fn start_pairing(&self) {
        *self.pairing.lock().unwrap() = Some(Pairing::new());
    }

    pub fn cancel_pairing(&self) {
        *self.pairing.lock().unwrap() = None;
    }

    pub fn pair_uri(&self, p: &Pairing) -> String {
        let addrs: Vec<String> = lan_addresses().iter().map(|a| a.to_string()).collect();
        format!(
            "switchboard://pair?v={PROTOCOL_VERSION}&id={}&n={}&k={}&t={}&a={}&p={}",
            self.host_id,
            pct(&self.name()),
            self.pk_b64(),
            b64(&p.token),
            addrs.join(","),
            self.port
        )
    }

    pub fn status(&self) -> serde_json::Value {
        let online: Vec<String> = self.conns.lock().unwrap().values().map(|c| c.device_id.clone()).collect();
        let devices: Vec<DeviceView> = self
            .store
            .devices()
            .unwrap_or_default()
            .into_iter()
            .map(|d| DeviceView { online: online.contains(&d.id), id: d.id, name: d.name, paired_at: d.paired_at, last_seen: d.last_seen })
            .collect();
        let pairing = self.pairing.lock().unwrap().as_ref().filter(|p| p.live()).map(|p| {
            let left = p.expires.saturating_duration_since(std::time::Instant::now());
            let expires_at = SystemTime::now() + left;
            json!({
                "uri": self.pair_uri(p),
                "code": p.code,
                "expires_at": expires_at.duration_since(UNIX_EPOCH).unwrap_or(Duration::ZERO).as_millis() as u64,
            })
        });
        json!({
            "host_id": self.host_id,
            "name": self.name(),
            "os": self.os,
            "port": self.port,
            "addresses": lan_addresses(),
            "fingerprint": fingerprint(self.keys.public.as_bytes()),
            "pairing": pairing,
            "devices": devices,
        })
    }

    pub fn emit(&self, event: &str, data: serde_json::Value) {
        let _ = self.ui.send(json!({ "event": event, "data": data }).to_string());
    }

    pub fn emit_status(&self) {
        self.emit("status", self.status());
    }
}

/// Short, readable key fingerprint for the desktop and phone to compare.
pub fn fingerprint(pk: &[u8]) -> String {
    use std::fmt::Write;
    use sha2::Digest;
    let h = sha2::Sha256::digest(pk);
    let mut s = String::new();
    for (i, b) in h.iter().take(6).enumerate() {
        if i > 0 && i % 2 == 0 {
            s.push(' ');
        }
        let _ = write!(s, "{b:02X}");
    }
    s
}

/// Percent-encode a URI query value.
pub fn pct(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => (b as char).to_string(),
            _ => format!("%{b:02X}"),
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pct_encodes_spaces_and_unicode() {
        assert_eq!(pct("Ayaan's PC"), "Ayaan%27s%20PC");
        assert_eq!(pct("é"), "%C3%A9");
    }

    #[test]
    fn fingerprint_shape() {
        let f = fingerprint(&[1; 32]);
        assert_eq!(f.len(), 14, "{f}");
        assert_eq!(f.matches(' ').count(), 2);
    }
}
