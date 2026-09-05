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
- Transfer throughput and speed calculations (MB/s or Mb/s) are sampled over rolling time windows and broadcast to the user interface.

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
4. **Sequential Transfer Queue**: Multiple selected files stream sequentially off disk through an upload queue, avoiding socket contention and ack-window starvation.
5. **Safe File Naming**: File names with colons (common in camera and screen recording timestamps) and path prefixes are sanitized automatically so they are accepted safely by the desktop daemon.

---

## 🔔 Foreground Service & Notifications

On Android, transferring large multi-gigabyte video or archive files requires keeping network sockets alive when the user switches to other apps:
- The transfer engine runs inside `TransferService`, an Android **Foreground Service** with `foregroundServiceType="dataSync"`.
- A persistent notification displays real-time progress percentage, transfer speed, and a **Cancel** action button.
