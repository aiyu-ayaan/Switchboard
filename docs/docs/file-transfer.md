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
- Files are streamed in chunks (typically 64 KB).
- Each chunk is framed with its chunk index and an AEAD authentication tag.
- Transfer throughput and speed calculations (MB/s or Mb/s) are sampled over rolling time windows and broadcast to the user interface.

### 3. Verification & Atomicity
- Incoming chunks are written to a temporary staging file (`.switchboard.tmp`).
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

### Outgoing File Picker
When sending files from phone to PC:
1. The user taps **Choose** to launch `ACTION_OPEN_DOCUMENT`.
2. The file is streamed directly from the `content://` resolver URI without needing to copy the file into app cache first.

---

## 🔔 Foreground Service & Notifications

On Android, transferring large multi-gigabyte video or archive files requires keeping network sockets alive when the user switches to other apps:
- The transfer engine runs inside `TransferService`, an Android **Foreground Service** with `foregroundServiceType="dataSync"`.
- A persistent notification displays real-time progress percentage, transfer speed, and a **Cancel** action button.
