//! Files moving between a phone and this computer, inside the encrypted
//! channel. A phone uploads in order, one chunk at a time, each waiting for
//! its result; the computer offers a file and the phone pulls it the same way.
//! The frames are authenticated, so a matching size is all that is left to check.
//!
//! Both directions survive a dropped connection: a half-finished upload is
//! parked for a day and picked up where it stopped, and an offer stays
//! available for an hour.

use std::collections::HashMap;
use std::io::SeekFrom;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

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
/// Folders a phone sends keep their structure, up to this deep.
const MAX_DEPTH: usize = 8;
const WINDOWS_RESERVED: [&str; 12] = ["CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "LPT1", "LPT2", "LPT3", "LPT4"];
/// How long a half-finished upload waits for its phone to come back.
const PARK_FOR: Duration = Duration::from_secs(24 * 3600);
/// How long an offer waits to be taken.
pub const OFFER_FOR: Duration = Duration::from_secs(3600);
/// How long a phone's upload waits for the person at this computer to answer.
pub const ASK_FOR: Duration = Duration::from_secs(120);
/// The error a phone sees while it waits; it asks again until it gets an answer.
pub const WAITING: &str = "waiting for approval on the computer";
/// Progress events no faster than this, per transfer.
const PROGRESS_EVERY: Duration = Duration::from_millis(250);

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

/// A relative path from a phone (`photos/2026/a.jpg`) as safe components.
/// `..` anywhere refuses the whole path rather than being quietly dropped.
pub fn safe_rel(raw: &str) -> Option<Vec<String>> {
    let parts: Vec<&str> = raw.split(['/', '\\']).filter(|p| !p.is_empty() && *p != ".").collect();
    if parts.is_empty() || parts.len() > MAX_DEPTH || parts.contains(&"..") {
        return None;
    }
    parts.into_iter().map(safe_name).collect()
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

/// Deletes a leftover partial upload.
pub fn discard(path: &Path) {
    let _ = std::fs::remove_file(path);
}

/// Removes half-written files nobody came back for, from before this run.
pub fn sweep(dir: &Path) {
    let Ok(rd) = std::fs::read_dir(dir) else { return };
    for e in rd.flatten() {
        let n = e.file_name().to_string_lossy().into_owned();
        let old = e.metadata().and_then(|m| m.modified()).ok().and_then(|t| t.elapsed().ok()).is_some_and(|a| a > PARK_FOR);
        if n.starts_with('.') && n.ends_with(".part") && old {
            let _ = std::fs::remove_file(e.path());
        }
    }
}

struct Upload {
    file: File,
    tmp: PathBuf,
    rel: Vec<String>,
    size: u64,
    written: u64,
    last: Instant,
}

/// An upload whose phone went away: where it stopped, and until when it may resume.
pub struct Parked {
    pub tmp: PathBuf,
    rel: Vec<String>,
    size: u64,
    written: u64,
    at: Instant,
}

/// Parked uploads, by (phone, transfer id).
pub type ParkedMap = Arc<Mutex<HashMap<(String, String), Parked>>>;

fn reap(parked: &ParkedMap) {
    parked.lock().unwrap().retain(|_, p| {
        let keep = p.at.elapsed() < PARK_FOR;
        if !keep {
            let _ = std::fs::remove_file(&p.tmp);
        }
        keep
    });
}

/// An upload a phone announced and the person has not answered yet.
pub struct Ask {
    pub name: String,
    pub size: u64,
    pub at: Instant,
    pub accepted: Option<bool>,
}

pub struct Received {
    pub name: String,
    pub size: u64,
    pub path: PathBuf,
}

/// One phone's uploads in flight. When the connection ends they are parked
/// so the phone can resume; finished and cancelled ones leave nothing behind.
pub struct Uploads {
    dir: PathBuf,
    owner: String,
    parked: ParkedMap,
    active: HashMap<String, Upload>,
}

impl Drop for Uploads {
    fn drop(&mut self) {
        let mut parked = self.parked.lock().unwrap();
        for (xfer, u) in self.active.drain() {
            parked.insert((self.owner.clone(), xfer), Parked { tmp: u.tmp, rel: u.rel, size: u.size, written: u.written, at: Instant::now() });
        }
    }
}

impl Uploads {
    pub fn new(dir: PathBuf, owner: String, parked: ParkedMap) -> Self {
        Self { dir, owner, parked, active: HashMap::new() }
    }

    fn has(&self, xfer: &str) -> bool {
        self.active.contains_key(xfer)
    }

    /// Starts an upload, or continues a parked one. Returns how many bytes
    /// the computer already has; the phone sends from there.
    async fn start(&mut self, xfer: &str, name: &str, size: u64) -> Result<u64> {
        ensure!(!xfer.is_empty() && xfer.len() <= 64, "bad transfer id");
        ensure!(!self.has(xfer), "transfer already started");
        ensure!(self.active.len() < MAX_ACTIVE, "too many files at once");
        ensure!(size <= MAX_FILE, "file is too large");
        let rel = safe_rel(name).context("bad file name")?;
        reap(&self.parked);

        let old = self.parked.lock().unwrap().remove(&(self.owner.clone(), xfer.to_owned()));
        if let Some(p) = old {
            if p.rel == rel && p.size == size {
                if let Ok(mut f) = OpenOptions::new().write(true).open(&p.tmp).await {
                    // Trust only what is really on disk.
                    if f.metadata().await.is_ok_and(|m| m.len() >= p.written) {
                        f.set_len(p.written).await?;
                        f.seek(SeekFrom::Start(p.written)).await?;
                        self.active.insert(xfer.to_owned(), Upload { file: f, tmp: p.tmp, rel, size, written: p.written, last: Instant::now() });
                        return Ok(p.written);
                    }
                }
            }
            let _ = std::fs::remove_file(&p.tmp);
        }

        tokio::fs::create_dir_all(&self.dir).await?;
        let tmp = self.dir.join(format!(".{}.part", b64(&random_bytes::<8>())));
        let file = OpenOptions::new().write(true).create_new(true).open(&tmp).await?;
        self.active.insert(xfer.to_owned(), Upload { file, tmp, rel, size, written: 0, last: Instant::now() });
        Ok(0)
    }

    async fn chunk(&mut self, xfer: &str, offset: u64, data: &str) -> Result<()> {
        let up = self.active.get_mut(xfer).context("unknown transfer")?;
        let bytes = unb64(data).context("bad data")?;
        ensure!(bytes.len() <= FILE_CHUNK, "chunk too large");
        ensure!(offset == up.written, "chunk out of order");
        ensure!(up.written + bytes.len() as u64 <= up.size, "more data than announced");
        up.file.write_all(&bytes).await?;
        // On disk before the result goes back, so a dropped connection can resume from here.
        up.file.flush().await?;
        up.written += bytes.len() as u64;
        Ok(())
    }

    /// (name, done, total) when a progress event is due.
    fn progress(&mut self, xfer: &str) -> Option<(String, u64, u64)> {
        let up = self.active.get_mut(xfer)?;
        if up.last.elapsed() < PROGRESS_EVERY {
            return None;
        }
        up.last = Instant::now();
        Some((up.rel.last().cloned().unwrap_or_default(), up.written, up.size))
    }

    async fn finish(&mut self, xfer: &str) -> Result<Received> {
        let mut up = self.active.remove(xfer).context("unknown transfer")?;
        let result = async {
            ensure!(up.written == up.size, "file is incomplete");
            up.file.flush().await?;
            Ok(())
        }
        .await;
        let Upload { file, tmp, rel, size, .. } = up;
        drop(file);
        let moved = result.and_then(|()| {
            let (name, folders) = rel.split_last().context("empty path")?;
            let mut dir = self.dir.clone();
            dir.extend(folders);
            std::fs::create_dir_all(&dir)?;
            let path = reserve(&dir, name)?;
            // Renaming over the empty reserved file keeps the chosen name unique.
            std::fs::rename(&tmp, &path)?;
            Ok(path)
        });
        if moved.is_err() {
            let _ = std::fs::remove_file(&tmp);
        }
        let path = moved?;
        let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        Ok(Received { name, size, path })
    }

    fn cancel(&mut self, xfer: &str) -> Option<String> {
        let u = self.active.remove(xfer)?;
        let _ = std::fs::remove_file(&u.tmp);
        Some(u.rel.last().cloned().unwrap_or_default())
    }
}

/// A file the desktop has offered one phone. The path never leaves this
/// process: the phone names the offer, not the file.
pub struct Offer {
    pub device_id: String,
    pub path: PathBuf,
    pub name: String,
    pub size: u64,
    pub created: Instant,
    pub last: Instant,
    /// A zip made for this offer; deleted with it.
    pub temp: bool,
}

impl Drop for Offer {
    fn drop(&mut self) {
        if self.temp {
            let _ = std::fs::remove_file(&self.path);
            if let Some(dir) = self.path.parent() {
                let _ = std::fs::remove_dir(dir);
            }
        }
    }
}

/// Zips a folder (symlinks are skipped) into a temporary file for offering.
pub fn zip_dir(dir: &Path) -> Result<(PathBuf, String)> {
    use std::io::Write;
    let name = safe_name(&dir.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default()).unwrap_or_else(|| "folder".into());
    let out_dir = std::env::temp_dir().join(format!("switchboard-{}", b64(&random_bytes::<6>())));
    std::fs::create_dir_all(&out_dir)?;
    let zip_path = out_dir.join(format!("{name}.zip"));
    let mut zip = zip::ZipWriter::new(std::fs::File::create(&zip_path)?);
    let opts = zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated).large_file(true);
    let mut stack = vec![(dir.to_path_buf(), PathBuf::from(&name))];
    let mut entries = 0usize;
    while let Some((path, inside)) = stack.pop() {
        for e in std::fs::read_dir(&path)?.flatten() {
            let ty = e.file_type()?;
            let child = inside.join(e.file_name());
            entries += 1;
            ensure!(entries <= 200_000, "that folder has too many files");
            if ty.is_dir() {
                stack.push((e.path(), child));
            } else if ty.is_file() {
                zip.start_file(child.to_string_lossy().replace('\\', "/"), opts)?;
                std::io::copy(&mut std::fs::File::open(e.path())?, &mut zip)?;
            }
        }
    }
    zip.flush()?;
    zip.finish()?;
    Ok((zip_path, format!("{name}.zip")))
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

/// Runs one file command from a phone. Returns the result's `data`, if any:
/// for `FileSend`, the byte the phone should resume from.
pub async fn handle(app: &App, device_id: &str, uploads: &mut Uploads, cmd: &Command) -> Result<Option<String>> {
    let who = || app.store.device(device_id).ok().flatten().map(|d| d.name).unwrap_or_default();
    let record = |name: &str, size: u64, dir: &str, status: &str| {
        let _ = app.store.add_transfer(&who(), name, size, dir, status);
    };
    match cmd {
        Command::FileSend { xfer, name, size } => {
            // A parked upload was approved when it began; resuming it asks nothing new.
            let resuming = app.parked.lock().unwrap().contains_key(&(device_id.to_owned(), xfer.clone()));
            if !resuming && !uploads.has(xfer) {
                app.gate_upload(device_id, xfer, name, *size)?;
            }
            Ok(Some(uploads.start(xfer, name, *size).await?.to_string()))
        }
        Command::FileChunk { xfer, offset, data } => {
            uploads.chunk(xfer, *offset, data).await?;
            if let Some((name, done, total)) = uploads.progress(xfer) {
                app.emit("file_progress", json!({ "dir": "in", "device": who(), "xfer": xfer, "name": name, "done": done, "total": total }));
            }
            Ok(None)
        }
        Command::FileDone { xfer } => {
            if uploads.has(xfer) {
                let r = uploads.finish(xfer).await?;
                record(&r.name, r.size, "in", "done");
                app.emit("file_received", json!({ "device": who(), "xfer": xfer, "name": r.name, "size": r.size, "path": r.path }));
            } else if let Some(o) = app.take_offer(device_id, xfer) {
                record(&o.name, o.size, "out", "done");
                app.emit("file_sent", json!({ "device": who(), "xfer": xfer, "name": o.name, "size": o.size }));
            } else {
                bail!("unknown transfer");
            }
            Ok(None)
        }
        Command::FileCancel { xfer } => {
            if app.withdraw_upload(device_id, xfer) {
                return Ok(None);
            }
            if let Some(name) = uploads.cancel(xfer) {
                record(&name, 0, "in", "cancelled");
                app.emit("file_cancelled", json!({ "device": who(), "xfer": xfer, "name": name }));
            } else {
                let o = app.take_offer(device_id, xfer).context("unknown transfer")?;
                record(&o.name, o.size, "out", "declined");
                app.emit("file_declined", json!({ "device": who(), "xfer": xfer, "name": o.name }));
            }
            Ok(None)
        }
        Command::FileRead { xfer, offset, len } => {
            let (path, size) = app.offer(device_id, xfer).context("unknown transfer")?;
            let piece = read_piece(&path, size, *offset, *len).await?;
            if let Some(name) = app.offer_progress(device_id, xfer) {
                let done = *offset + piece.len() as u64;
                app.emit("file_progress", json!({ "dir": "out", "device": who(), "xfer": xfer, "name": name, "done": done, "total": size }));
            }
            Ok(Some(b64(&piece)))
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

    fn uploads(dir: &Path, parked: &ParkedMap) -> Uploads {
        Uploads::new(dir.to_path_buf(), "phone".into(), parked.clone())
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

    #[test]
    fn folders_keep_their_shape_but_never_climb() {
        assert_eq!(safe_rel("photos/2026\\a.jpg").unwrap(), ["photos", "2026", "a.jpg"]);
        assert_eq!(safe_rel("/abs/./x.txt").unwrap(), ["abs", "x.txt"]);
        assert_eq!(safe_rel("a/../../x"), None);
        assert_eq!(safe_rel(".."), None);
        assert_eq!(safe_rel(""), None);
        assert_eq!(safe_rel("a/b/c/d/e/f/g/h/i.txt"), None, "too deep");
        assert_eq!(safe_rel("a/CON/x").unwrap(), ["a", "_CON", "x"]);
    }

    #[tokio::test]
    async fn upload_lands_under_a_free_name() {
        let dir = tmpdir("up");
        let parked = ParkedMap::default();
        for round in 0..2 {
            let mut u = uploads(&dir, &parked);
            assert_eq!(u.start("t", "note.txt", 5).await.unwrap(), 0);
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
    async fn folder_upload_recreates_the_folders() {
        let dir = tmpdir("tree");
        let mut u = uploads(&dir, &ParkedMap::default());
        u.start("t", "trip/day1/a.txt", 2).await.unwrap();
        u.chunk("t", 0, &b64(b"hi")).await.unwrap();
        let r = u.finish("t").await.unwrap();
        assert_eq!(r.path, dir.join("trip").join("day1").join("a.txt"));
        assert_eq!(std::fs::read(r.path).unwrap(), b"hi");
    }

    #[tokio::test]
    async fn a_dropped_upload_resumes_where_it_stopped() {
        let dir = tmpdir("resume");
        let parked = ParkedMap::default();
        {
            let mut u = uploads(&dir, &parked);
            u.start("t", "big.bin", 6).await.unwrap();
            u.chunk("t", 0, &b64(b"abc")).await.unwrap();
            // The connection drops here: the upload is parked, not deleted.
        }
        let mut u = uploads(&dir, &parked);
        assert_eq!(u.start("t", "big.bin", 6).await.unwrap(), 3, "resumes after the bytes already there");
        u.chunk("t", 3, &b64(b"def")).await.unwrap();
        let r = u.finish("t").await.unwrap();
        assert_eq!(std::fs::read(r.path).unwrap(), b"abcdef");
    }

    #[tokio::test]
    async fn a_changed_file_starts_over_and_other_phones_cannot_resume() {
        let dir = tmpdir("noresume");
        let parked = ParkedMap::default();
        {
            let mut u = uploads(&dir, &parked);
            u.start("t", "a.bin", 6).await.unwrap();
            u.chunk("t", 0, &b64(b"abc")).await.unwrap();
        }
        let mut other = Uploads::new(dir.clone(), "someone else".into(), parked.clone());
        assert_eq!(other.start("t", "a.bin", 6).await.unwrap(), 0);
        let mut u = uploads(&dir, &parked);
        assert_eq!(u.start("t", "a.bin", 7).await.unwrap(), 0, "a different size is a different file");
        let parts = std::fs::read_dir(&dir).unwrap().flatten().filter(|e| e.file_name().to_string_lossy().ends_with(".part")).count();
        assert_eq!(parts, 2, "the abandoned partial was removed");
    }

    #[tokio::test]
    async fn bad_uploads_are_refused_and_cancelled_ones_vanish() {
        let dir = tmpdir("bad");
        let parked = ParkedMap::default();
        let mut u = uploads(&dir, &parked);
        u.start("t", "a.bin", 4).await.unwrap();
        assert!(u.chunk("t", 2, &b64(b"xx")).await.is_err(), "gap");
        assert!(u.chunk("t", 0, &b64(b"xxxxx")).await.is_err(), "too long");
        u.chunk("t", 0, &b64(b"xx")).await.unwrap();
        assert!(u.finish("t").await.is_err(), "short file");
        assert!(u.chunk("nope", 0, "AA").await.is_err());
        assert!(u.start("t2", "..", 1).await.is_err());
        u.start("t3", "b.bin", 1).await.unwrap();
        assert!(u.cancel("t3").is_some());
        drop(u);
        assert!(parked.lock().unwrap().is_empty(), "nothing to resume");
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

    #[test]
    fn a_folder_becomes_a_zip_with_its_structure() {
        let dir = tmpdir("zipme").join("My Album");
        std::fs::create_dir_all(dir.join("sub")).unwrap();
        std::fs::write(dir.join("a.txt"), b"A").unwrap();
        std::fs::write(dir.join("sub").join("b.txt"), b"B").unwrap();
        let (path, name) = zip_dir(&dir).unwrap();
        assert_eq!(name, "My Album.zip");
        let mut z = zip::ZipArchive::new(std::fs::File::open(&path).unwrap()).unwrap();
        let mut names: Vec<String> = (0..z.len()).map(|i| z.by_index(i).unwrap().name().to_owned()).collect();
        names.sort();
        assert_eq!(names, ["My Album/a.txt", "My Album/sub/b.txt"]);
        // The offer deletes its zip when it goes away.
        drop(Offer { device_id: "d".into(), path: path.clone(), name, size: 1, created: Instant::now(), last: Instant::now(), temp: true });
        assert!(!path.exists());
    }
}
