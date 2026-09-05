# Encrypted File Transfer Subsystem

This document covers the design, protocol, and Android integration of Switchboard''s local peer-to-peer file transfer engine.

---

## ⚡ Design Goals

1. **Pure Local Transfer**: Files move directly across the local Wi-Fi / LAN without being staged or routed through external cloud infrastructure.
2. **Authenticated Encryption**: Every chunk is encrypted with AEAD (ChaCha20-Poly1305) using the established session secret.
3. **End-to-End Integrity**: Every transfer verifies complete cryptographic SHA-256 checksums before finalizing files on disk.
4. **Zero Dangerous Permissions**: On Android, files are selected and saved via Android''s modern **Storage Access Framework (SAF)**, eliminating the need for broad storage access permissions.
5. **Resilient Background Execution**: Long-running transfers remain active when the mobile app is minimized via Android Foreground Services with persistent progress notifications.

---

## 🔄 Transfer Protocol Workflow

```
       Sender (Mobile or Desktop)              Receiver (Desktop or Mobile)
                   │                                         │
                   │─────── 1. file_transfer_init ──────────>│
                   │    (name, size, sha256, direction)      │
                   │                                         │
                   │<────── 2. file_transfer_accept ─────────│
                   │           (transferId, ready)           │
                   │                                         │
                   │─────── 3. Encrypted Chunk Stream ──────>│
                   │    [Nonce + AEAD Ciphertext + Tag]      │
                   │    (64 KB chunks, buffered writes)      │
                   │                                         │
                   │─────── 4. file_transfer_complete ──────>│
                   │                                         │
                   │<────── 5. file_transfer_verified ───────│
                   │          (SHA-256 hash match)           │
```

### 1. Handshake & Allocation
1. The sender initiates transfer by sending a `file_transfer_init` packet declaring filename, file size, SHA-256 checksum, and direction.
2. The receiver validates available disk space in the designated download directory and returns `file_transfer_accept` with a unique `transferId`.

### 2. Chunked Streaming
- Files are streamed in 256 KiB chunks over the existing encrypted session — no second socket and no second key exchange.
- **Chunk bytes travel beside the JSON envelope, not inside it.** Every frame is
  already a binary WebSocket message carrying an AEAD ciphertext, so base64 in
  the envelope would inflate every byte by a third and buy an encode on one
  CPU and a decode on the other to arrive in the same place. A frame is
  `0x01 | uint32 metadata length | envelope JSON | raw bytes`; a control frame
  is `0x00 | envelope JSON`. The overhead on a 256 KiB chunk is under 256 bytes.
- **Offsets are authoritative.** The receiver writes each chunk *at* its stated
  offset rather than appending, so a duplicated or reordered frame overwrites
  the same bytes instead of corrupting the file.
- **Acks are flow control, not reliability.** The transport already guarantees
  delivery. Without a window a desktop SSD saturates the link faster than a
  phone commits to storage, and the excess piles up in socket buffers on both
  ends — so the sender stays within 2 MiB of the last acknowledgement.
- **Nothing is ever whole in memory.** Both ends move one chunk at a time and
  hash as bytes pass, which is what makes a multi-GB file a bounded-memory
  operation rather than an allocation failure.
- **Four files move at a time, per device.** Offering a whole folder at once
  put a dozen pumps on one socket: each crawled against the others, the phone's
  single frame reader serialised them, and every bar advanced in bursts. Both
  ends now admit four and queue the rest, which is enough to cover the digest
  pass and handshake round trip of one file with the bytes of another.
- **Acks are batched at 512 KiB.** Confirming every 256 KiB chunk doubled the
  frame count for no gain — those acks share the socket with the chunks they
  are pacing — while staying comfortably inside the 2 MiB window.
- **Progress is reported at most four times a second.** Republishing on every
  chunk rebuilt and recomposed the whole transfer list a hundred times a second
  on a fast link, which cost more than the transfer it was reporting on.
- **Speeds are smoothed, not averaged.** Both ends blend each window's sample
  into a running estimate (70% previous), so a figure that would otherwise
  flicker between 4 and 90 MB/s reads as one number. A cumulative average since
  the start cannot show a link recovering or stalling at all.
- **Verifying and publishing a received file happen off the frame path.** The
  digest pass and the copy into the user's folder are two more full reads of
  the file; run inline they blocked the channel where the *next* file's chunks
  were already queued, which is what made a batch arrive in bursts with a long
  stall at each file boundary.

The desktop reads the moving numbers from the live transfer map rather than
from stored history: rows are persisted on the same quarter-second throttle and
carry no rate column at all. `GET /local/files/history` returns the transfer
list on its own so the UI can poll it four times a second while a file is
moving, without dragging a DDC/CI display probe along with it.

### 3. Surviving a dropped connection

A lost socket is **not** a failed transfer. When a device disconnects, both
ends *park* whatever was in flight:

- The receiver keeps its `.part` file and closes only the handle.
- The sender stops its pump and remembers the file it was reading.
- Both show the transfer as paused, "waiting to reconnect".

When the device comes back, the sender re-offers the **same transfer id**. The
receiver reopens the `.part`, reports the offset it already holds, and the
sender seeks there — so an interrupted 4 GB file resumes at the byte it
reached instead of starting again. This reuses the same resume path a manual
pause takes, rather than a second mechanism written for reconnects.

A parked transfer is not immortal: one whose device never returns is failed
after ten minutes, because a parked receive holds an open file handle and a
phone that walked out of range must not pin one forever.

### 4. Verification & Atomicity
- Incoming chunks are written to a temporary staging file (`.part` beside the destination).
- As chunks arrive, the receiver continuously updates an incremental SHA-256 digest.
- Once the final chunk is received, the digest is compared to the initiator's declared hash.
- Upon successful match, the temporary file is atomically renamed to the final destination filename. If validation fails, the staging file is discarded immediately.

---

## 📱 Android Storage Access Framework (SAF) Integration

### Folder Selection
Switchboard avoids legacy or high-risk permissions like `READ_EXTERNAL_STORAGE` or `MANAGE_EXTERNAL_STORAGE`:
1. The user selects their preferred download location using Android's system `ACTION_OPEN_DOCUMENT_TREE` picker.
2. The returned `Uri` is granted persistent read/write permissions via `contentResolver.takePersistableUriPermission(uri, ...)`.
3. Incoming files are created inside the chosen directory using `DocumentFile.createFile()`.

**The picker is opened with a starting location**, not with `null`. Launched
with no hint it resumes wherever it was last left — which is wherever the
send-a-file picker left it, typically an album under Videos or Images. Those
are media roots, and **a media root cannot be granted as a tree at all**, so
the picker shows no way to select the folder in front of you and the setting
appears broken. `EXTRA_INITIAL_URI` therefore points at the already-chosen
folder, or at `primary:Download` on the external storage provider when none
has been chosen yet. It is a hint, and a picker may ignore it.

**A tree that cannot be persisted is refused, not stored.** Not every picker
returns a `Uri` carrying `FLAG_GRANT_PERSISTABLE_URI_PERMISSION`, and taking
one that does not throws `SecurityException`. The save folder is recorded only
once the grant is actually held; otherwise the settings row says so, rather
than leaving a tap that silently did nothing and a folder that would fail on
the next incoming file.

### Outgoing File & Folder Transfer
When sending files from phone to PC:
1. **Multi-File Selection**: The user taps **Files** to launch `ACTION_OPEN_DOCUMENT` / `ACTION_OPEN_MULTIPLE_DOCUMENTS` to select one or multiple files at once.
2. **Folder Selection**: The user taps **Folder** to launch `ACTION_OPEN_DOCUMENT_TREE`. Switchboard recursively traverses all files inside the selected directory and queues them for transfer.
3. **Android Share Sheet**: Switchboard declares `ACTION_SEND` and `ACTION_SEND_MULTIPLE` intent filters, allowing users to share media and files directly from Gallery or File Managers into Switchboard.
4. **Bounded Parallel Transfer Queue**: Selected files stream off disk through an upload queue drained by four workers, so one file's digest pass or handshake wait does not leave the link idle — while the cap still prevents the socket contention and ack-window starvation an unbounded fan-out causes.
5. **Safe File Naming**: File names with colons (common in camera and screen recording timestamps) and path prefixes are sanitized automatically so they are accepted safely by the desktop daemon.

---

## 🔔 Foreground Service & Notifications

On Android, transferring large multi-gigabyte video or archive files requires keeping network sockets alive when the user switches to other apps:
- The transfer engine runs inside `TransferService`, an Android **Foreground Service** with `foregroundServiceType="dataSync"`.
- A persistent notification displays real-time progress percentage, transfer speed, and a **Cancel** action button.
- **The notification is rebuilt off the main thread and at most once a second.** Posting one is four synchronous binder round trips to the system server — one per `PendingIntent` plus the `notify` — and the transfer flow ticks four times a second per file. Several files in flight put well over sixty IPCs a second on the UI thread, which froze the app while bytes were moving. The intents are resolved once and reused; a status change still posts immediately, because that is when the action buttons change.

### Keeping the UI thread out of the transfer path

Everything below was found by asking what a Compose callback or a `Dispatchers.Main` collector actually does per file and per frame:

- **Queueing a picked file is not free.** Each one costs three or four binder calls into whichever `DocumentsProvider` owns it — taking the persistable permission, reading the display name, falling back to `openFileDescriptor` for a size the cursor omitted, resolving the MIME type — and picking a *folder* is a cursor query per directory for the whole tree. Reached straight from a tap, a multi-select of a few dozen froze the app before a byte moved. Queueing runs on the engine's IO scope.
- **Inbound frames are never logged per event.** The connection collector runs on `Dispatchers.Main.immediate`; interpolating each frame's JSON payload into a log line put that work on the UI thread for the length of every transfer.
- **The inbound flow cannot drop a frame.** `callbackFlow` defaults to 64 slots and its emitters are `trySend`, so a consumer that fell behind lost frames rather than slowing the socket. A dropped chunk is a hole in the received file and a digest mismatch after gigabytes. The queue is unbounded, which is bounded in practice: the sender stops once it is a window ahead of the acks that queue feeds.
- **Allocation is a UI-thread cost too.** A sealed 256 KiB chunk used to be copied five times between the read buffer and the socket. Two of those were removable — the read buffer is already free once `Frame.encode` has copied it, and `nonce + cipher.doFinal(...)` allocated the ciphertext only to copy all of it again — and the GC pauses they bought landed on the main thread as stutter.
