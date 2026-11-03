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

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct TransferRow {
    pub at: i64,
    pub device: String,
    pub name: String,
    pub size: u64,
    /// "in" (phone to computer) or "out".
    pub dir: String,
    /// "done", "cancelled" or "declined".
    pub status: String,
}

pub struct Store {
    db: Mutex<Connection>,
    vault: Option<Box<dyn Vault>>,
}

/// Where the host's secret key lives. The OS keychain in the real app; tests
/// pass a fake or none at all.
pub trait Vault: Send + Sync {
    fn get(&self, host_id: &str) -> Result<Option<Vec<u8>>>;
    fn set(&self, host_id: &str, secret: &[u8]) -> Result<()>;
}

/// Secret Service (Linux) or Credential Manager (Windows). One entry per
/// host id, so a dev profile and the installed app never share a key.
#[cfg(any(target_os = "linux", windows))]
struct Keychain;

#[cfg(any(target_os = "linux", windows))]
impl Keychain {
    /// The Linux backend runs its own async runtime; keep it off ours. A session
    /// bus with no Secret Service on it can stall for many seconds, so give up
    /// after a few and let the caller fall back.
    fn off_runtime<T: Send + 'static>(f: impl FnOnce() -> Result<T> + Send + 'static) -> Result<T> {
        let (tx, rx) = std::sync::mpsc::channel();
        std::thread::spawn(move || {
            let _ = tx.send(f());
        });
        rx.recv_timeout(std::time::Duration::from_secs(4)).unwrap_or_else(|_| anyhow::bail!("the keychain did not answer"))
    }
}

#[cfg(any(target_os = "linux", windows))]
impl Vault for Keychain {
    fn get(&self, host_id: &str) -> Result<Option<Vec<u8>>> {
        let host_id = host_id.to_owned();
        Self::off_runtime(move || match keyring::Entry::new("switchboard", &format!("host-key:{host_id}"))?.get_secret() {
            Ok(v) => Ok(Some(v)),
            Err(keyring::Error::NoEntry) => Ok(None),
            Err(e) => Err(e.into()),
        })
    }

    fn set(&self, host_id: &str, secret: &[u8]) -> Result<()> {
        let (host_id, secret) = (host_id.to_owned(), secret.to_vec());
        Self::off_runtime(move || Ok(keyring::Entry::new("switchboard", &format!("host-key:{host_id}"))?.set_secret(&secret)?))
    }
}

pub fn now() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs() as i64).unwrap_or(0)
}

#[cfg(unix)]
fn owner_only(path: &Path) {
    use std::os::unix::fs::PermissionsExt;
    let _ = std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600));
}

// shortcut: Windows files inherit the user profile's ACL, which is already per user.
#[cfg(not(unix))]
fn owner_only(_: &Path) {}

impl Store {
    pub fn open(path: &Path) -> Result<Self> {
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir)?;
        }
        #[cfg(any(target_os = "linux", windows))]
        let vault: Option<Box<dyn Vault>> = Some(Box::new(Keychain));
        #[cfg(not(any(target_os = "linux", windows)))]
        let vault: Option<Box<dyn Vault>> = None;
        let store = Self::init(Connection::open(path)?, vault)?;
        // The database can hold the host secret on a machine with no keychain,
        // and the transfer history names files: for this user only. Also fixes
        // a file an older version made readable by everyone.
        for suffix in ["", "-wal", "-shm"] {
            let mut p = path.as_os_str().to_owned();
            p.push(suffix);
            owner_only(Path::new(&p));
        }
        Ok(store)
    }

    #[cfg(test)]
    pub fn memory() -> Result<Self> {
        Self::init(Connection::open_in_memory()?, None)
    }

    #[cfg(test)]
    fn memory_with(vault: impl Vault + 'static) -> Result<Self> {
        Self::init(Connection::open_in_memory()?, Some(Box::new(vault)))
    }

    fn init(db: Connection, vault: Option<Box<dyn Vault>>) -> Result<Self> {
        db.execute_batch(
            "PRAGMA journal_mode = WAL;
             CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value BLOB NOT NULL);
             CREATE TABLE IF NOT EXISTS devices (
                 id TEXT PRIMARY KEY,
                 name TEXT NOT NULL,
                 static_pk BLOB NOT NULL,
                 paired_at INTEGER NOT NULL,
                 last_seen INTEGER NOT NULL
             );
             CREATE TABLE IF NOT EXISTS transfers (
                 id INTEGER PRIMARY KEY AUTOINCREMENT,
                 at INTEGER NOT NULL,
                 device TEXT NOT NULL,
                 name TEXT NOT NULL,
                 size INTEGER NOT NULL,
                 dir TEXT NOT NULL,
                 status TEXT NOT NULL
             );",
        )?;
        Ok(Self { db: Mutex::new(db), vault })
    }

    fn meta(&self, key: &str) -> Result<Option<Vec<u8>>> {
        Ok(self.db.lock().unwrap().query_row("SELECT value FROM meta WHERE key = ?1", [key], |r| r.get(0)).optional()?)
    }

    fn set_meta(&self, key: &str, value: &[u8]) -> Result<()> {
        self.db.lock().unwrap().execute("INSERT OR REPLACE INTO meta (key, value) VALUES (?1, ?2)", params![key, value])?;
        Ok(())
    }

    fn del_meta(&self, key: &str) -> Result<()> {
        self.db.lock().unwrap().execute("DELETE FROM meta WHERE key = ?1", [key])?;
        Ok(())
    }

    /// Host id and static key pair, created on first run. The secret key goes
    /// to the OS keychain; a database from before that moves its key there
    /// on first start. With no keychain (a headless box) it stays in the
    /// database, protected by file permissions only.
    pub fn identity(&self) -> Result<(String, KeyPair)> {
        if let Some(id) = self.meta("host_id")? {
            let id = String::from_utf8(id)?;
            if let Some(sk) = self.load_secret(&id)? {
                return Ok((id, KeyPair::from_secret(&sk)?));
            }
            tracing::warn!("host key is missing from the keychain; creating a new identity");
        }
        let id = b64(&random_bytes::<12>());
        let kp = KeyPair::generate();
        self.save_secret(&id, &kp.secret.to_bytes())?;
        self.set_meta("host_id", id.as_bytes())?;
        Ok((id, kp))
    }

    fn load_secret(&self, id: &str) -> Result<Option<Vec<u8>>> {
        let legacy = self.meta("host_sk")?;
        let Some(vault) = &self.vault else { return Ok(legacy) };
        match vault.get(id) {
            Ok(Some(sk)) => {
                if legacy.is_some() {
                    self.del_meta("host_sk")?;
                }
                Ok(Some(sk))
            }
            Ok(None) => {
                let Some(sk) = legacy else { return Ok(None) };
                // Drop the database copy only once the keychain returns the same bytes.
                if vault.set(id, &sk).is_ok() && matches!(vault.get(id), Ok(Some(back)) if back == sk) {
                    self.del_meta("host_sk")?;
                    tracing::info!("moved the host key into the OS keychain");
                }
                Ok(Some(sk))
            }
            // A locked or missing keychain must never read as "no key": that would mint a new identity and orphan every phone.
            Err(e) => match legacy {
                Some(sk) => {
                    tracing::warn!("keychain unavailable, using the key in the database: {e:#}");
                    Ok(Some(sk))
                }
                None => Err(e.context("cannot read the host key from the OS keychain")),
            },
        }
    }

    fn save_secret(&self, id: &str, sk: &[u8]) -> Result<()> {
        if let Some(vault) = &self.vault {
            match vault.set(id, sk) {
                Ok(()) => return Ok(()),
                Err(e) => tracing::warn!("no OS keychain, keeping the host key in the database: {e:#}"),
            }
        }
        self.set_meta("host_sk", sk)
    }

    /// Whether phones may drive the keyboard and mouse. Off unless the person at the computer turned it on.
    pub fn remote_input(&self) -> Result<bool> {
        Ok(self.meta("remote_input")?.is_some_and(|v| v == b"1"))
    }

    pub fn set_remote_input(&self, on: bool) -> Result<()> {
        self.set_meta("remote_input", if on { b"1" } else { b"0" })
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

    /// One finished, cancelled or declined transfer; only the newest 500 are kept.
    pub fn add_transfer(&self, device: &str, name: &str, size: u64, dir: &str, status: &str) -> Result<()> {
        let db = self.db.lock().unwrap();
        db.execute("INSERT INTO transfers (at, device, name, size, dir, status) VALUES (?1, ?2, ?3, ?4, ?5, ?6)", params![now(), device, name, size as i64, dir, status])?;
        db.execute("DELETE FROM transfers WHERE id NOT IN (SELECT id FROM transfers ORDER BY id DESC LIMIT 500)", [])?;
        Ok(())
    }

    pub fn transfers(&self, limit: u32) -> Result<Vec<TransferRow>> {
        let db = self.db.lock().unwrap();
        let mut st = db.prepare("SELECT at, device, name, size, dir, status FROM transfers ORDER BY id DESC LIMIT ?1")?;
        let rows = st
            .query_map([limit], |r| Ok(TransferRow { at: r.get(0)?, device: r.get(1)?, name: r.get(2)?, size: r.get::<_, i64>(3)? as u64, dir: r.get(4)?, status: r.get(5)? }))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        Ok(rows)
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

    #[derive(Default, Clone)]
    struct FakeVault(std::sync::Arc<Mutex<Option<Vec<u8>>>>, bool);

    impl Vault for FakeVault {
        fn get(&self, _: &str) -> Result<Option<Vec<u8>>> {
            anyhow::ensure!(!self.1, "locked");
            Ok(self.0.lock().unwrap().clone())
        }
        fn set(&self, _: &str, s: &[u8]) -> Result<()> {
            anyhow::ensure!(!self.1, "locked");
            *self.0.lock().unwrap() = Some(s.to_vec());
            Ok(())
        }
    }

    #[test]
    fn key_lives_in_the_keychain_not_the_database() {
        let v = FakeVault::default();
        let s = Store::memory_with(v.clone()).unwrap();
        let (id, kp) = s.identity().unwrap();
        assert!(s.meta("host_sk").unwrap().is_none());
        assert_eq!(v.0.lock().unwrap().as_deref(), Some(&kp.secret.to_bytes()[..]));
        assert_eq!(s.identity().unwrap().0, id);
    }

    #[test]
    fn old_database_key_moves_to_the_keychain() {
        let plain = Store::memory().unwrap();
        let (id, kp) = plain.identity().unwrap();
        assert!(plain.meta("host_sk").unwrap().is_some(), "no keychain keeps it in the database");
        let v = FakeVault::default();
        let s = Store::memory_with(v.clone()).unwrap();
        s.set_meta("host_id", id.as_bytes()).unwrap();
        s.set_meta("host_sk", &kp.secret.to_bytes()).unwrap();
        let (id2, kp2) = s.identity().unwrap();
        assert_eq!((id2, kp2.public), (id, kp.public));
        assert!(s.meta("host_sk").unwrap().is_none());
        assert!(v.0.lock().unwrap().is_some());
    }

    #[test]
    fn locked_keychain_never_mints_a_new_identity() {
        let ok = FakeVault::default();
        let s = Store::memory_with(ok).unwrap();
        s.identity().unwrap();
        // Same database, keychain now failing, key already moved out of it.
        let locked = Store { db: Mutex::new(Connection::open_in_memory().unwrap()), vault: Some(Box::new(FakeVault(Default::default(), true))) };
        locked.db.lock().unwrap().execute_batch("CREATE TABLE meta (key TEXT PRIMARY KEY, value BLOB NOT NULL);").unwrap();
        locked.set_meta("host_id", b"abc").unwrap();
        assert!(locked.identity().is_err());
    }

    #[cfg(unix)]
    #[test]
    fn database_is_for_this_user_only() {
        use std::os::unix::fs::PermissionsExt;
        let dir = std::env::temp_dir().join(format!("sb-store-{}", b64(&switchboard_core::crypto::random_bytes::<6>())));
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("switchboard.db");
        // A database an older version left readable by everyone.
        std::fs::write(&path, b"").unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o644)).unwrap();
        let s = Store::open(&path).unwrap();
        s.set_meta("k", b"v").unwrap();
        assert_eq!(std::fs::metadata(&path).unwrap().permissions().mode() & 0o777, 0o600);
        std::fs::remove_dir_all(&dir).unwrap();
    }

    #[test]
    fn history_keeps_the_newest() {
        let s = Store::memory().unwrap();
        for i in 0..505 {
            s.add_transfer("Pixel", &format!("f{i}"), i, "in", "done").unwrap();
        }
        let t = s.transfers(3).unwrap();
        assert_eq!(t[0].name, "f504");
        assert_eq!(t.len(), 3);
        assert_eq!(s.transfers(1000).unwrap().len(), 500);
    }

    #[test]
    fn remote_input_is_off_until_allowed() {
        let s = Store::memory().unwrap();
        assert!(!s.remote_input().unwrap());
        s.set_remote_input(true).unwrap();
        assert!(s.remote_input().unwrap());
        s.set_remote_input(false).unwrap();
        assert!(!s.remote_input().unwrap());
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
