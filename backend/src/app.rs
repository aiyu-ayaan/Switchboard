//! Shared daemon state and the status the desktop UI renders.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::{Arc, Mutex, RwLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use serde::Serialize;
use serde_json::json;
use switchboard_core::b64;
use anyhow::Context;
use switchboard_core::crypto::{KeyPair, random_bytes};
use switchboard_core::protocol::{HostState, PROTOCOL_VERSION};
use tokio::sync::{Notify, mpsc, watch};

use crate::discovery::{Advert, lan_addresses};
use crate::files::{self, Offer};
use crate::pairing::Pairing;
use crate::store::Store;
use crate::system::System;

pub enum Outbound {
    /// The desktop forgot this phone.
    Revoked,
    /// The desktop is offering this phone a file.
    Offer { xfer: String, name: String, size: u64 },
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
    /// Where phones' uploads are saved.
    pub inbox: PathBuf,
    /// Files the desktop has offered, by transfer id.
    offers: Mutex<HashMap<String, Offer>>,
    /// Half-finished uploads waiting for their phone to reconnect.
    pub parked: files::ParkedMap,
    /// Take files phones send without asking.
    pub auto_accept: AtomicBool,
    /// Uploads a phone has announced that the person at this computer has not answered yet.
    asks: Mutex<HashMap<(String, String), files::Ask>>,
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
        Self::with_inbox(store, port, ui, files::inbox())
    }

    pub fn with_inbox(store: Store, port: u16, ui: mpsc::UnboundedSender<String>, inbox: PathBuf) -> anyhow::Result<Arc<Self>> {
        let (host_id, keys) = store.identity()?;
        let host_name = match store.host_name()? {
            Some(n) => n,
            None => sysinfo::System::host_name().unwrap_or_else(|| "My computer".into()),
        };
        files::sweep(&inbox);
        let auto_accept = store.auto_accept_files()?;
        let system = System::default();
        system.remote_input.store(store.remote_input()?, std::sync::atomic::Ordering::Relaxed);
        let os = sysinfo::System::long_os_version().unwrap_or_else(|| std::env::consts::OS.into());
        Ok(Arc::new(Self {
            store,
            host_id,
            keys,
            host_name: RwLock::new(host_name),
            port,
            os,
            pairing: Mutex::new(None),
            system,
            conns: Mutex::new(HashMap::new()),
            inbox,
            offers: Mutex::new(HashMap::new()),
            parked: Default::default(),
            auto_accept: AtomicBool::new(auto_accept),
            asks: Mutex::new(HashMap::new()),
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

    pub fn set_remote_input(&self, on: bool) -> anyhow::Result<()> {
        self.store.set_remote_input(on)?;
        self.system.remote_input.store(on, std::sync::atomic::Ordering::Relaxed);
        Ok(())
    }

    pub fn set_auto_accept(&self, on: bool) -> anyhow::Result<()> {
        self.store.set_auto_accept_files(on)?;
        self.auto_accept.store(on, Ordering::Relaxed);
        Ok(())
    }

    /// Lets an announced upload start, asks the person the first time, and says
    /// "waiting" until they answer. The phone keeps asking until then.
    pub fn gate_upload(&self, device_id: &str, xfer: &str, name: &str, size: u64) -> anyhow::Result<()> {
        if self.auto_accept.load(Ordering::Relaxed) {
            return Ok(());
        }
        let key = (device_id.to_owned(), xfer.to_owned());
        let mut asks = self.asks.lock().unwrap();
        asks.retain(|_, a| a.at.elapsed() < files::ASK_FOR);
        let decision = match asks.get(&key) {
            None => {
                asks.insert(key, files::Ask { name: name.to_owned(), size, at: Instant::now(), accepted: None });
                drop(asks);
                self.emit_status();
                anyhow::bail!("{}", files::WAITING);
            }
            Some(a) => a.accepted,
        };
        match decision {
            None => anyhow::bail!("{}", files::WAITING),
            Some(accepted) => {
                asks.remove(&key);
                drop(asks);
                self.emit_status();
                anyhow::ensure!(accepted, "declined on the computer");
                Ok(())
            }
        }
    }

    /// Answers one waiting upload, or all of them when `xfer` is `None`. Returns how many.
    pub fn answer_uploads(&self, xfer: Option<&str>, accept: bool) -> usize {
        let mut n = 0;
        for ((_, x), a) in self.asks.lock().unwrap().iter_mut() {
            if a.accepted.is_none() && xfer.is_none_or(|w| w == x) {
                a.accepted = Some(accept);
                n += 1;
            }
        }
        self.emit_status();
        n
    }

    /// The phone gave up before an answer.
    pub fn withdraw_upload(&self, device_id: &str, xfer: &str) -> bool {
        let gone = self.asks.lock().unwrap().remove(&(device_id.to_owned(), xfer.to_owned())).is_some();
        if gone {
            self.emit_status();
        }
        gone
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
        // A forgotten phone leaves nothing waiting for it.
        self.offers.lock().unwrap().retain(|_, o| o.device_id != device_id);
        self.asks.lock().unwrap().retain(|(d, _), _| d != device_id);
        self.parked.lock().unwrap().retain(|(owner, _), p| {
            let keep = owner != device_id;
            if !keep {
                files::discard(&p.tmp);
            }
            keep
        });
        Ok(removed)
    }

    /// Offers a local file to a connected phone; `temp` files (a folder's zip)
    /// are deleted with the offer. The phone is asked; nothing is sent until it pulls.
    pub fn offer_file(&self, device_id: &str, path: &Path, temp: bool) -> anyhow::Result<()> {
        let meta = std::fs::metadata(path).context("cannot read that file")?;
        anyhow::ensure!(meta.is_file(), "that is not a file");
        let name = files::safe_name(&path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()).context("bad file name")?;
        let conns = self.conns.lock().unwrap();
        let live: Vec<_> = conns.values().filter(|c| c.device_id == device_id).collect();
        anyhow::ensure!(!live.is_empty(), "that phone is not connected");
        let xfer = b64(&random_bytes::<9>());
        let now = Instant::now();
        let mut offers = self.offers.lock().unwrap();
        offers.retain(|_, o| o.created.elapsed() < files::OFFER_FOR);
        offers.insert(xfer.clone(), Offer { device_id: device_id.into(), path: path.into(), name: name.clone(), size: meta.len(), created: now, last: now, temp });
        for c in live {
            let _ = c.tx.send(Outbound::Offer { xfer: xfer.clone(), name: name.clone(), size: meta.len() });
        }
        Ok(())
    }

    /// Path and size of an offer made to this phone, while it is still open.
    pub fn offer(&self, device_id: &str, xfer: &str) -> Option<(PathBuf, u64)> {
        self.offers.lock().unwrap().get(xfer).filter(|o| o.device_id == device_id && o.created.elapsed() < files::OFFER_FOR).map(|o| (o.path.clone(), o.size))
    }

    /// The offer's name if a progress event is due for it.
    pub fn offer_progress(&self, device_id: &str, xfer: &str) -> Option<String> {
        let mut offers = self.offers.lock().unwrap();
        let o = offers.get_mut(xfer).filter(|o| o.device_id == device_id)?;
        if o.last.elapsed() < std::time::Duration::from_millis(250) {
            return None;
        }
        o.last = Instant::now();
        Some(o.name.clone())
    }

    pub fn take_offer(&self, device_id: &str, xfer: &str) -> Option<Offer> {
        let mut offers = self.offers.lock().unwrap();
        if offers.get(xfer).is_some_and(|o| o.device_id == device_id) { offers.remove(xfer) } else { None }
    }

    /// Offers still open for this phone, to show again when it reconnects.
    pub fn pending_offers(&self, device_id: &str) -> Vec<(String, String, u64)> {
        let mut offers = self.offers.lock().unwrap();
        offers.retain(|_, o| o.created.elapsed() < files::OFFER_FOR);
        offers.iter().filter(|(_, o)| o.device_id == device_id).map(|(x, o)| (x.clone(), o.name.clone(), o.size)).collect()
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
        let requests: Vec<_> = {
            let names: HashMap<String, String> = self.store.devices().unwrap_or_default().into_iter().map(|d| (d.id, d.name)).collect();
            let mut asks = self.asks.lock().unwrap();
            asks.retain(|_, a| a.at.elapsed() < files::ASK_FOR);
            let mut v: Vec<_> = asks.iter().filter(|(_, a)| a.accepted.is_none()).map(|((d, x), a)| (a.at, json!({ "xfer": x, "device": names.get(d).cloned().unwrap_or_default(), "name": a.name, "size": a.size }))).collect();
            v.sort_by_key(|(at, _)| *at);
            v.into_iter().map(|(_, j)| j).collect()
        };
        json!({
            "host_id": self.host_id,
            "name": self.name(),
            "os": self.os,
            "port": self.port,
            "addresses": lan_addresses(),
            "fingerprint": fingerprint(self.keys.public.as_bytes()),
            "auto_accept_files": self.auto_accept.load(Ordering::Relaxed),
            "requests": requests,
            "remote_input": self.system.remote_input.load(std::sync::atomic::Ordering::Relaxed),
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
