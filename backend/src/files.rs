//! Files moving between a phone and this computer, inside the encrypted
//! channel. A phone uploads in order, one chunk at a time, each waiting for
//! its result; the computer offers a file and the phone pulls it the same way.
//! The frames are authenticated, so a matching size is all that is left to check.

use std::collections::HashMap;
use std::io::SeekFrom;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail, ensure};
use serde_json::json;
use switchboard_core::crypto::random_bytes;
use switchboard_core::protocol::{Command, FILE_CHUNK};
use switchboard_core::{b64, unb64};
use tokio::fs::{File, OpenOptions};
use tokio::io::{AsyncReadExt, AsyncSeekExt, AsyncWriteExt};

use crate::app::App;

const MAX_FILE: u64 = 16 << 30;
/// Uploads open at once per phone.
const MAX_ACTIVE: usize = 8;
const WINDOWS_RESERVED: [&str; 12] = ["CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "LPT1", "LPT2", "LPT3", "LPT4"];

/// Where received files land: `Downloads/Switchboard`.
pub fn inbox() -> PathBuf {
    dirs::download_dir().or_else(dirs::home_dir).unwrap_or_else(|| PathBuf::from(".")).join("Switchboard")
}

/// A file name that cannot leave the inbox or trip the filesystem: no
/// directories, no control or reserved characters, 200 characters at most.
pub fn safe_name(raw: &str) -> Option<String> {
    let base = raw.rsplit(['/', '\\']).next().unwrap_or("");
    let cleaned: String = base.chars().filter(|c| !c.is_control() && !matches!(c, ':' | '*' | '?' | '"' | '<' | '>' | '|')).take(200).collect();
    let cleaned = cleaned.trim().trim_end_matches('.').trim().to_owned();
    if cleaned.is_empty() {
        return None;
    }
    let stem = cleaned.split('.').next().unwrap_or("").to_ascii_uppercase();
    Some(if WINDOWS_RESERVED.contains(&stem.as_str()) { format!("_{cleaned}") } else { cleaned })
}

/// Creates the file under a free name (`a.txt`, `a (1).txt`, …) so nothing is overwritten.
fn reserve(dir: &Path, name: &str) -> Result<PathBuf> {
    let (stem, ext) = match name.rfind('.') {
        Some(i) if i > 0 => name.split_at(i),
        _ => (name, ""),
    };
    for n in 0..1000 {
        let path = dir.join(if n == 0 { name.to_owned() } else { format!("{stem} ({n}){ext}") });
        match std::fs::OpenOptions::new().write(true).create_new(true).open(&path) {
            Ok(_) => return Ok(path),
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => {}
            Err(e) => return Err(e.into()),
        }
    }
    bail!("too many files named {name}")
}

struct Upload {
    file: File,
    tmp: PathBuf,
    name: String,
    size: u64,
    written: u64,
}

pub struct Received {
    pub name: String,
    pub size: u64,
    pub path: PathBuf,
}

/// One phone's uploads in flight. Half-written files vanish with the connection.
pub struct Uploads {
    dir: PathBuf,
    active: HashMap<String, Upload>,
}

impl Drop for Uploads {
    fn drop(&mut self) {
        for u in self.active.values() {
            let _ = std::fs::remove_file(&u.tmp);
        }
    }
}

impl Uploads {
    pub fn new(dir: PathBuf) -> Self {
        Self { dir, active: HashMap::new() }
    }

    fn has(&self, xfer: &str) -> bool {
        self.active.contains_key(xfer)
    }

    async fn start(&mut self, xfer: &str, name: &str, size: u64) -> Result<()> {
        ensure!(!xfer.is_empty() && xfer.len() <= 64, "bad transfer id");
        ensure!(!self.has(xfer), "transfer already started");
        ensure!(self.active.len() < MAX_ACTIVE, "too many files at once");
        ensure!(size <= MAX_FILE, "file is too large");
        let name = safe_name(name).context("bad file name")?;
        tokio::fs::create_dir_all(&self.dir).await?;
        let tmp = self.dir.join(format!(".{}.part", b64(&random_bytes::<8>())));
        let file = OpenOptions::new().write(true).create_new(true).open(&tmp).await?;
        self.active.insert(xfer.to_owned(), Upload { file, tmp, name, size, written: 0 });
        Ok(())
    }

    async fn chunk(&mut self, xfer: &str, offset: u64, data: &str) -> Result<()> {
        let up = self.active.get_mut(xfer).context("unknown transfer")?;
        let bytes = unb64(data).context("bad data")?;
        ensure!(bytes.len() <= FILE_CHUNK, "chunk too large");
        ensure!(offset == up.written, "chunk out of order");
        ensure!(up.written + bytes.len() as u64 <= up.size, "more data than announced");
        up.file.write_all(&bytes).await?;
        up.written += bytes.len() as u64;
        Ok(())
    }

    async fn finish(&mut self, xfer: &str) -> Result<Received> {
        let mut up = self.active.remove(xfer).context("unknown transfer")?;
        let result = async {
            ensure!(up.written == up.size, "file is incomplete");
            up.file.flush().await?;
            Ok(())
        }
        .await;
        let Upload { file, tmp, name, size, .. } = up;
        drop(file);
        let moved = result.and_then(|()| {
            let path = reserve(&self.dir, &name)?;
            // Renaming over the empty reserved file keeps the chosen name unique.
            std::fs::rename(&tmp, &path)?;
            Ok(path)
        });
        if moved.is_err() {
            let _ = std::fs::remove_file(&tmp);
        }
        let path = moved?;
        Ok(Received { name: path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or(name), size, path })
    }

    fn cancel(&mut self, xfer: &str) -> bool {
        match self.active.remove(xfer) {
            Some(u) => {
                let _ = std::fs::remove_file(&u.tmp);
                true
            }
            None => false,
        }
    }
}

/// A file the desktop has offered one phone. The path never leaves this
/// process: the phone names the offer, not the file.
pub struct Offer {
    pub device_id: String,
    pub path: PathBuf,
    pub name: String,
    pub size: u64,
}

async fn read_piece(path: &Path, size: u64, offset: u64, len: u32) -> Result<Vec<u8>> {
    ensure!(offset <= size, "offset is past the end of the file");
    let n = (len as usize).min(FILE_CHUNK).min((size - offset) as usize);
    let mut f = File::open(path).await.context("the file is gone")?;
    f.seek(SeekFrom::Start(offset)).await?;
    let mut buf = vec![0; n];
    f.read_exact(&mut buf).await.context("the file changed while sending")?;
    Ok(buf)
}

/// Runs one file command from a phone. Returns the result's `data`, if any.
pub async fn handle(app: &App, device_id: &str, uploads: &mut Uploads, cmd: &Command) -> Result<Option<String>> {
    let who = || app.store.device(device_id).ok().flatten().map(|d| d.name).unwrap_or_default();
    match cmd {
        Command::FileSend { xfer, name, size } => uploads.start(xfer, name, *size).await.map(|()| None),
        Command::FileChunk { xfer, offset, data } => uploads.chunk(xfer, *offset, data).await.map(|()| None),
        Command::FileDone { xfer } => {
            if uploads.has(xfer) {
                let r = uploads.finish(xfer).await?;
                app.emit("file_received", json!({ "device": who(), "name": r.name, "size": r.size, "path": r.path }));
            } else if let Some(o) = app.take_offer(device_id, xfer) {
                app.emit("file_sent", json!({ "device": who(), "name": o.name, "size": o.size }));
            } else {
                bail!("unknown transfer");
            }
            Ok(None)
        }
        Command::FileCancel { xfer } => {
            if !uploads.cancel(xfer) {
                let o = app.take_offer(device_id, xfer).context("unknown transfer")?;
                app.emit("file_declined", json!({ "device": who(), "name": o.name }));
            }
            Ok(None)
        }
        Command::FileRead { xfer, offset, len } => {
            let (path, size) = app.offer(device_id, xfer).context("unknown transfer")?;
            Ok(Some(b64(&read_piece(&path, size, *offset, *len).await?)))
        }
        _ => bail!("not a file command"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmpdir(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("sb-files-{tag}-{}", b64(&random_bytes::<6>())));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn names_cannot_escape_or_trip_the_filesystem() {
        assert_eq!(safe_name("../../etc/passwd").as_deref(), Some("passwd"));
        assert_eq!(safe_name("C:\\Users\\me\\a.txt").as_deref(), Some("a.txt"));
        assert_eq!(safe_name("a:b*c?.txt").as_deref(), Some("abc.txt"));
        assert_eq!(safe_name("nul.txt").as_deref(), Some("_nul.txt"));
        assert_eq!(safe_name("dir/"), None);
        assert_eq!(safe_name(".."), None);
        assert_eq!(safe_name("  . "), None);
        assert_eq!(safe_name(&"x".repeat(500)).unwrap().len(), 200);
    }

    #[tokio::test]
    async fn upload_lands_under_a_free_name() {
        let dir = tmpdir("up");
        for round in 0..2 {
            let mut u = Uploads::new(dir.clone());
            u.start("t", "note.txt", 5).await.unwrap();
            u.chunk("t", 0, &b64(b"hel")).await.unwrap();
            u.chunk("t", 3, &b64(b"lo")).await.unwrap();
            let r = u.finish("t").await.unwrap();
            assert_eq!(std::fs::read(&r.path).unwrap(), b"hello");
            assert_eq!(r.name, if round == 0 { "note.txt" } else { "note (1).txt" });
        }
        let leftovers = std::fs::read_dir(&dir).unwrap().flatten().filter(|e| e.file_name().to_string_lossy().ends_with(".part")).count();
        assert_eq!(leftovers, 0);
    }

    #[tokio::test]
    async fn bad_uploads_are_refused_and_cleaned_up() {
        let dir = tmpdir("bad");
        let mut u = Uploads::new(dir.clone());
        u.start("t", "a.bin", 4).await.unwrap();
        assert!(u.chunk("t", 2, &b64(b"xx")).await.is_err(), "gap");
        assert!(u.chunk("t", 0, &b64(b"xxxxx")).await.is_err(), "too long");
        u.chunk("t", 0, &b64(b"xx")).await.unwrap();
        assert!(u.finish("t").await.is_err(), "short file");
        assert!(u.chunk("nope", 0, "AA").await.is_err());
        assert!(u.start("t2", "..", 1).await.is_err());
        u.start("t3", "b.bin", 1).await.unwrap();
        drop(u);
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 0, "partial files are removed");
    }

    #[tokio::test]
    async fn offered_file_is_read_in_pieces() {
        let dir = tmpdir("read");
        let p = dir.join("f.bin");
        std::fs::write(&p, b"0123456789").unwrap();
        assert_eq!(read_piece(&p, 10, 0, 4).await.unwrap(), b"0123");
        assert_eq!(read_piece(&p, 10, 8, 100).await.unwrap(), b"89");
        assert_eq!(read_piece(&p, 10, 10, 4).await.unwrap(), b"");
        assert!(read_piece(&p, 10, 11, 4).await.is_err());
    }
}
