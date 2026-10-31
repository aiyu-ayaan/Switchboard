//! SQLite: the desktop's identity and the phones it trusts.

use std::path::Path;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::Result;
use rusqlite::{Connection, OptionalExtension, params};
use serde::Serialize;
use switchboard_core::crypto::{KeyPair, random_bytes};
use switchboard_core::b64;

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct Device {
    pub id: String,
    pub name: String,
    #[serde(skip)]
    pub static_pk: [u8; 32],
    pub paired_at: i64,
    pub last_seen: i64,
}

pub struct Store {
    db: Mutex<Connection>,
}

pub fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

impl Store {
    pub fn open(path: &Path) -> Result<Self> {
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        Self::init(Connection::open(path)?)
    }

    #[cfg(test)]
    pub fn memory() -> Result<Self> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(db: Connection) -> Result<Self> {
        db.execute_batch(
            "PRAGMA journal_mode = WAL;
             CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value BLOB NOT NULL);
             CREATE TABLE IF NOT EXISTS devices (
                 id TEXT PRIMARY KEY,
                 name TEXT NOT NULL,
                 static_pk BLOB NOT NULL,
                 paired_at INTEGER NOT NULL,
                 last_seen INTEGER NOT NULL
             );",
        )?;
        Ok(Self { db: Mutex::new(db) })
    }

    fn meta(&self, key: &str) -> Result<Option<Vec<u8>>> {
        Ok(self.db.lock().unwrap().query_row("SELECT value FROM meta WHERE key = ?1", [key], |r| r.get(0)).optional()?)
    }

    fn set_meta(&self, key: &str, value: &[u8]) -> Result<()> {
        self.db.lock().unwrap().execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?1, ?2)", params![key, value])?;
        Ok(())
    }

    /// Host id and static key pair, created on first run.
    // shortcut: the host secret sits in the user's data dir protected by file
    // permissions only; move it to the OS keychain (keyring crate) later.
    pub fn identity(&self) -> Result<(String, KeyPair)> {
        if let (Some(id), Some(sk)) = (self.meta("host_id")?, self.meta("host_sk")?) {
            return Ok((String::from_utf8(id)?, KeyPair::from_secret(&sk)?));
        }
        let id = b64(&random_bytes::<12>());
        let kp = KeyPair::generate();
        self.set_meta("host_id", id.as_bytes())?;
        self.set_meta("host_sk", &kp.secret.to_bytes())?;
        Ok((id, kp))
    }

    pub fn host_name(&self) -> Result<Option<String>> {
        Ok(self.meta("host_name")?.and_then(|b| String::from_utf8(b).ok()))
    }

    pub fn set_host_name(&self, name: &str) -> Result<()> {
        self.set_meta("host_name", name.as_bytes())
    }

    pub fn device(&self, id: &str) -> Result<Option<Device>> {
        Ok(self
            .db
            .lock()
            .unwrap()
            .query_row("SELECT id, name, static_pk, paired_at, last_seen FROM devices WHERE id = ?1", [id], row)
            .optional()?)
    }

    pub fn devices(&self) -> Result<Vec<Device>> {
        let db = self.db.lock().unwrap();
        let mut st = db.prepare("SELECT id, name, static_pk, paired_at, last_seen FROM devices ORDER BY last_seen DESC")?;
        let rows = st.query_map([], row)?.collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(rows)
    }

    /// Pairing again from the same phone replaces its old key.
    pub fn add_device(&self, id: &str, name: &str, static_pk: &[u8; 32]) -> Result<()> {
        let t = now();
        self.db.lock().unwrap().execute(
            "INSERT OR REPLACE INTO devices (id, name, static_pk, paired_at, last_seen) VALUES (?1, ?2, ?3, ?4, ?4)",
            params![id, name, &static_pk[..], t],
        )?;
        Ok(())
    }

    pub fn touch(&self, id: &str, name: &str) -> Result<()> {
        self.db.lock().unwrap().execute("UPDATE devices SET last_seen = ?2, name = ?3 WHERE id = ?1", params![id, now(), name])?;
        Ok(())
    }

    pub fn remove_device(&self, id: &str) -> Result<bool> {
        Ok(self.db.lock().unwrap().execute("DELETE FROM devices WHERE id = ?1", [id])? > 0)
    }
}

fn row(r: &rusqlite::Row) -> rusqlite::Result<Device> {
    let pk: Vec<u8> = r.get(2)?;
    Ok(Device {
        id: r.get(0)?,
        name: r.get(1)?,
        static_pk: pk.try_into().unwrap_or([0; 32]),
        paired_at: r.get(3)?,
        last_seen: r.get(4)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn identity_is_stable() {
        let s = Store::memory().unwrap();
        let (a, ka) = s.identity().unwrap();
        let (b, kb) = s.identity().unwrap();
        assert_eq!(a, b);
        assert_eq!(ka.public, kb.public);
    }

    #[test]
    fn device_lifecycle() {
        let s = Store::memory().unwrap();
        s.add_device("p1", "Pixel", &[9; 32]).unwrap();
        assert_eq!(s.device("p1").unwrap().unwrap().static_pk, [9; 32]);
        s.add_device("p1", "Pixel 9", &[8; 32]).unwrap();
        assert_eq!(s.devices().unwrap().len(), 1);
        assert_eq!(s.device("p1").unwrap().unwrap().name, "Pixel 9");
        assert!(s.remove_device("p1").unwrap());
        assert!(s.device("p1").unwrap().is_none());
    }
}
