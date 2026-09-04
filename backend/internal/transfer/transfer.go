// Package transfer moves files between the daemon and a paired client over
// the frames defined in package protocol.
//
// Both directions share one Manager and one transfer record: whichever side
// holds the file streams chunks, and the side receiving them drives the pace
// by acknowledging. Nothing here buffers a whole file — a transfer costs one
// open file handle and a single chunk of memory regardless of file size.
package transfer

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"

	"switchboard/backend/internal/protocol"
)

const (
	// ackInterval is how many bytes the receiver takes before confirming.
	// Acking every chunk would double the frame count for no gain; 512 KiB is
	// a 64 KiB multiple so the window lands on a chunk boundary whatever
	// protocol.ChunkSize is later tuned to.
	ackInterval = 512 * 1024

	// sendWindow bounds how far ahead of the last ack the sender may run.
	// Without it a local SSD outruns a phone's writer and the unacknowledged
	// frames pile up in the socket's write buffer until the daemon is holding
	// the whole file in memory after all.
	sendWindow = 4 * ackInterval

	// progressInterval throttles telemetry. At ChunkSize a LAN link lands
	// hundreds of chunks a second, and a UI asked to redraw that often costs
	// more CPU than the transfer it is reporting on.
	progressInterval = 250 * time.Millisecond

	// rateSmoothing weights the previous rate estimate against the newest
	// sample. Raw per-window rates swing wildly with disk and Wi-Fi jitter,
	// which reads as a broken transfer rather than a fast one.
	rateSmoothing = 0.7
)

// Event is one progress update plus the fields history needs but the wire
// frame deliberately omits: the peer, where the file landed, and its digest.
type Event struct {
	protocol.FileProgress
	DeviceID string
	Path     string
	SHA256   string
}

// Manager tracks every live transfer. It is safe for concurrent use.
type Manager struct {
	// downloadDir is read per offer rather than captured once, so changing
	// the setting takes effect on the next file instead of at next restart.
	downloadDir func() string
	send        func(deviceID, action string, payload any) error
	onEvent     func(Event)

	mu     sync.Mutex
	active map[string]*transfer
}

// NewManager wires the engine to its transport and its telemetry sink.
func NewManager(downloadDir func() string, send func(deviceID, action string, payload any) error,
	onEvent func(Event)) *Manager {
	return &Manager{
		downloadDir: downloadDir,
		send:        send,
		onEvent:     onEvent,
		active:      map[string]*transfer{},
	}
}

// transfer is one file in flight. Every mutable field is guarded by mu; cond
// is how a paused or ack-starved sender goroutine is woken.
type transfer struct {
	mgr       *Manager
	id        string
	deviceID  string
	name      string
	direction string
	dir       string // receiving only: where the finished file belongs
	path      string // source file, or the final destination once known
	part      string // receiving only: the .part file bytes land in
	size      int64
	wantSHA   string

	mu   sync.Mutex
	cond *sync.Cond

	file     *os.File
	hash     hash.Hash
	hashedTo int64
	// rehash records that a chunk landed out of order, so the rolling hash no
	// longer describes the file and the digest must be recomputed from disk.
	rehash bool

	done   int64 // bytes written (receiving) or acknowledged (sending)
	sent   int64 // sending only: bytes handed to the transport
	acked  int64 // receiving only: bytes covered by the last ack sent
	status string
	errMsg string

	startedAt, finishedAt int64

	lastEmit  time.Time
	rateAt    time.Time
	rateBytes int64
	bps       float64
}

// ---- Receiving (peer -> daemon) ----

// Offer opens the destination for an incoming file and answers with the byte
// count already on disk, so an interrupted transfer resumes instead of
// re-sending everything.
func (m *Manager) Offer(deviceID string, o protocol.FileOffer) error {
	if o.TransferID == "" {
		return errors.New("transfer: offer carries no transfer id")
	}
	dir := m.downloadDir()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return m.refuse(deviceID, o, err)
	}
	final, err := destPath(dir, o.Name)
	if err != nil {
		return m.refuse(deviceID, o, err)
	}

	// Bytes land in a .part sibling so a half-received file is never mistaken
	// for a finished one by the user or by the next offer's collision check.
	part := final + ".part"
	f, err := os.OpenFile(part, os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return m.refuse(deviceID, o, err)
	}
	info, err := f.Stat()
	if err != nil {
		f.Close()
		return m.refuse(deviceID, o, err)
	}

	offset := info.Size()
	if offset > o.Size {
		// A leftover .part longer than the offer belongs to a different file
		// that happened to share a name. Resuming onto it would splice two
		// files together.
		if err := f.Truncate(0); err != nil {
			f.Close()
			return m.refuse(deviceID, o, err)
		}
		offset = 0
	}

	// Rehash the partial file once here rather than storing hash state across
	// runs: one sequential read of what is already on disk is cheap next to
	// re-transferring it, and it keeps the rolling hash valid from this point.
	h := sha256.New()
	if offset > 0 {
		if _, err := io.Copy(h, io.LimitReader(f, offset)); err != nil {
			f.Close()
			return m.refuse(deviceID, o, err)
		}
	}

	t := &transfer{
		mgr: m, id: o.TransferID, deviceID: deviceID, name: o.Name,
		direction: protocol.DirectionUpload,
		dir:       dir, path: final, part: part,
		size: o.Size, wantSHA: o.SHA256,
		file: f, hash: h, hashedTo: offset,
		done: offset, acked: offset,
		status:    protocol.TransferActive,
		startedAt: time.Now().UnixMilli(),
	}
	t.cond = sync.NewCond(&t.mu)
	m.add(t)

	t.mu.Lock()
	t.emitLocked(true)
	t.mu.Unlock()

	return m.send(deviceID, protocol.ActionFileAccept, protocol.FileAccept{
		TransferID: o.TransferID, Offset: offset, Accepted: true,
	})
}

func (m *Manager) refuse(deviceID string, o protocol.FileOffer, cause error) error {
	m.send(deviceID, protocol.ActionFileAccept, protocol.FileAccept{
		TransferID: o.TransferID, Accepted: false, Reason: cause.Error(),
	})
	return cause
}

// Chunk writes one slice at its authoritative offset. Offset is trusted over
// arrival order, so a duplicated or reordered frame overwrites the same bytes
// instead of corrupting the file.
func (m *Manager) Chunk(c protocol.FileChunk) error {
	t := m.get(c.TransferID)
	if t == nil {
		return fmt.Errorf("transfer: chunk for unknown transfer %s", c.TransferID)
	}
	data, err := base64.StdEncoding.DecodeString(c.Data)
	if err != nil {
		return t.fail(fmt.Errorf("transfer: malformed chunk: %w", err))
	}

	t.mu.Lock()
	if t.status != protocol.TransferActive {
		// Cancelled or paused mid-flight: drop the bytes rather than writing
		// into a file the user asked us to stop touching.
		t.mu.Unlock()
		return nil
	}
	if _, err := t.file.WriteAt(data, c.Offset); err != nil {
		t.mu.Unlock()
		return t.fail(err)
	}
	if c.Offset == t.hashedTo {
		t.hash.Write(data)
		t.hashedTo += int64(len(data))
	} else {
		t.rehash = true
	}
	if end := c.Offset + int64(len(data)); end > t.done {
		t.done = end
	}
	ack := c.Last || t.done-t.acked >= ackInterval
	if ack {
		t.acked = t.done
	}
	t.emitLocked(false)
	done, received := c.Last, t.done
	t.mu.Unlock()

	if ack && !done {
		m.send(t.deviceID, protocol.ActionFileAck, protocol.FileAck{
			TransferID: t.id, Received: received,
		})
	}
	if done {
		return t.finish()
	}
	return nil
}

// finish verifies the digest, promotes the .part file to its real name, and
// tells the sender whether the bytes survived the trip.
func (t *transfer) finish() error {
	t.mu.Lock()

	sum := hex.EncodeToString(t.hash.Sum(nil))
	if t.rehash {
		// Out-of-order chunks invalidated the rolling hash, so pay for one
		// full read of the file we just wrote rather than trusting it blind.
		t.file.Sync()
		if s, err := hashFile(t.part); err == nil {
			sum = s
		} else {
			t.mu.Unlock()
			return t.fail(err)
		}
	}
	if t.wantSHA != "" && !strings.EqualFold(sum, t.wantSHA) {
		t.file.Close()
		os.Remove(t.part)
		t.terminalLocked(protocol.TransferFailed, "digest mismatch")
		t.mu.Unlock()
		t.mgr.remove(t.id)
		return t.mgr.send(t.deviceID, protocol.ActionFileComplete, protocol.FileComplete{
			TransferID: t.id, OK: false, SHA256: sum, Error: "digest mismatch",
		})
	}
	if err := t.file.Close(); err != nil {
		t.mu.Unlock()
		return t.fail(err)
	}

	// Another file may have claimed the name while this one was in flight,
	// and os.Rename will not silently replace it on every platform.
	final := t.path
	if _, err := os.Stat(final); err == nil {
		if p, err := destPath(t.dir, t.name); err == nil {
			final = p
		}
	}
	if err := os.Rename(t.part, final); err != nil {
		t.mu.Unlock()
		return t.fail(err)
	}
	t.path = final
	t.terminalLocked(protocol.TransferCompleted, "")
	t.mu.Unlock()
	t.mgr.remove(t.id)

	return t.mgr.send(t.deviceID, protocol.ActionFileComplete, protocol.FileComplete{
		TransferID: t.id, OK: true, SHA256: sum,
	})
}

// ---- Sending (daemon -> peer) ----

// Send offers a local file to a connected device and returns the transfer ID
// the UI tracks it by. Bytes do not move until the peer accepts.
func (m *Manager) Send(deviceID, path string) (string, error) {
	info, err := os.Stat(path)
	if err != nil {
		return "", err
	}
	if info.IsDir() {
		return "", fmt.Errorf("transfer: %s is a directory", path)
	}
	// Hashed up front because the offer carries the digest: the receiver has
	// nothing to verify against otherwise, and a file that changes mid-send
	// should fail loudly rather than arrive silently wrong.
	sum, err := hashFile(path)
	if err != nil {
		return "", err
	}

	t := &transfer{
		mgr: m, id: uuid.NewString(), deviceID: deviceID,
		name: filepath.Base(path), direction: protocol.DirectionDownload,
		path: path, size: info.Size(), wantSHA: sum,
		status:    protocol.TransferPending,
		startedAt: time.Now().UnixMilli(),
	}
	t.cond = sync.NewCond(&t.mu)
	m.add(t)

	t.mu.Lock()
	t.emitLocked(true)
	t.mu.Unlock()

	if err := m.send(deviceID, protocol.ActionFileOffer, protocol.FileOffer{
		TransferID: t.id, Name: t.name, Size: t.size, SHA256: sum,
		Direction: protocol.DirectionDownload,
	}); err != nil {
		t.fail(err)
		return "", err
	}
	return t.id, nil
}

// Accept starts streaming from the offset the receiver already holds.
func (m *Manager) Accept(a protocol.FileAccept) error {
	t := m.get(a.TransferID)
	if t == nil {
		return fmt.Errorf("transfer: accept for unknown transfer %s", a.TransferID)
	}
	if !a.Accepted {
		reason := a.Reason
		if reason == "" {
			reason = "receiver declined"
		}
		return t.fail(errors.New(reason))
	}
	t.mu.Lock()
	if t.status != protocol.TransferPending {
		t.mu.Unlock()
		return nil
	}
	t.status = protocol.TransferActive
	t.sent, t.done = a.Offset, a.Offset
	t.emitLocked(true)
	t.mu.Unlock()

	go t.pump(a.Offset)
	return nil
}

// Ack advances the sender's window. Without this the pump blocks once
// sendWindow bytes are outstanding.
func (m *Manager) Ack(a protocol.FileAck) error {
	t := m.get(a.TransferID)
	if t == nil {
		return nil
	}
	t.mu.Lock()
	if a.Received > t.done {
		t.done = a.Received
	}
	t.emitLocked(false)
	t.cond.Broadcast()
	t.mu.Unlock()
	return nil
}

// Complete closes out a send once the receiver has verified the bytes.
func (m *Manager) Complete(c protocol.FileComplete) error {
	t := m.get(c.TransferID)
	if t == nil {
		return nil
	}
	t.mu.Lock()
	if c.OK {
		t.done = t.size
		t.terminalLocked(protocol.TransferCompleted, "")
	} else {
		msg := c.Error
		if msg == "" {
			msg = "receiver rejected the transfer"
		}
		t.terminalLocked(protocol.TransferFailed, msg)
	}
	t.cond.Broadcast()
	t.mu.Unlock()
	m.remove(t.id)
	return nil
}

// pump streams the file in ChunkSize slices, blocking whenever the receiver
// is more than sendWindow bytes behind or the user has paused.
func (t *transfer) pump(offset int64) {
	f, err := os.Open(t.path)
	if err != nil {
		t.fail(err)
		return
	}
	defer f.Close()

	// An empty file has no chunk to hang the "last" flag on, so send one
	// explicitly rather than leaving the receiver waiting forever.
	if t.size == 0 {
		t.mgr.send(t.deviceID, protocol.ActionFileChunk, protocol.FileChunk{
			TransferID: t.id, Offset: 0, Data: "", Last: true,
		})
		return
	}

	buf := make([]byte, protocol.ChunkSize)
	for offset < t.size {
		if !t.waitForWindow() {
			return
		}
		n, err := f.ReadAt(buf, offset)
		if n == 0 {
			if err != nil && !errors.Is(err, io.EOF) {
				t.fail(err)
			}
			return
		}
		last := offset+int64(n) >= t.size

		t.mu.Lock()
		t.sent = offset + int64(n)
		t.emitLocked(false)
		t.mu.Unlock()

		if err := t.mgr.send(t.deviceID, protocol.ActionFileChunk, protocol.FileChunk{
			TransferID: t.id, Offset: offset,
			Data: base64.StdEncoding.EncodeToString(buf[:n]),
			Last: last,
		}); err != nil {
			t.fail(err)
			return
		}
		offset += int64(n)
		if last {
			// The transfer is not finished until file.complete says the
			// digest matched, so leave it live and stop reading.
			return
		}
	}
}

// waitForWindow blocks until there is room to send, reporting false when the
// transfer has been cancelled or has failed underneath us.
func (t *transfer) waitForWindow() bool {
	t.mu.Lock()
	defer t.mu.Unlock()
	for {
		switch t.status {
		case protocol.TransferCancelled, protocol.TransferFailed, protocol.TransferCompleted:
			return false
		case protocol.TransferPaused:
			t.cond.Wait()
			continue
		}
		if t.sent-t.done >= sendWindow {
			t.cond.Wait()
			continue
		}
		return true
	}
}

// ---- Control ----

// Control applies a pause, resume, or cancel that arrived from the peer.
func (m *Manager) Control(c protocol.FileControl) error {
	t := m.get(c.TransferID)
	if t == nil {
		return fmt.Errorf("transfer: control for unknown transfer %s", c.TransferID)
	}
	return t.apply(c.Action)
}

// ControlLocal applies a control the desktop UI issued and mirrors it to the
// peer, which is the side that has to actually stop or restart sending.
func (m *Manager) ControlLocal(transferID, action string) error {
	t := m.get(transferID)
	if t == nil {
		return fmt.Errorf("transfer: control for unknown transfer %s", transferID)
	}
	if err := t.apply(action); err != nil {
		return err
	}
	return m.send(t.deviceID, protocol.ActionFileControl, protocol.FileControl{
		TransferID: transferID, Action: action,
	})
}

func (t *transfer) apply(action string) error {
	t.mu.Lock()
	switch action {
	case protocol.ControlPause:
		if t.status == protocol.TransferActive {
			t.status = protocol.TransferPaused
			t.emitLocked(true)
		}
	case protocol.ControlResume:
		if t.status == protocol.TransferPaused {
			t.status = protocol.TransferActive
			t.emitLocked(true)
		}
	case protocol.ControlCancel:
		// The .part file goes with the transfer: a cancel means the user does
		// not want the file, so leaving a partial behind is just litter. A
		// pause keeps it, which is what makes resume possible.
		if t.file != nil {
			t.file.Close()
			t.file = nil
			os.Remove(t.part)
		}
		t.terminalLocked(protocol.TransferCancelled, "")
		t.cond.Broadcast()
		t.mu.Unlock()
		t.mgr.remove(t.id)
		return nil
	default:
		t.mu.Unlock()
		return fmt.Errorf("transfer: unknown control action %q", action)
	}
	t.cond.Broadcast()
	t.mu.Unlock()
	return nil
}

// ---- Bookkeeping ----

func (m *Manager) add(t *transfer) {
	m.mu.Lock()
	m.active[t.id] = t
	m.mu.Unlock()
}

func (m *Manager) get(id string) *transfer {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.active[id]
}

func (m *Manager) remove(id string) {
	m.mu.Lock()
	delete(m.active, id)
	m.mu.Unlock()
}

func (t *transfer) fail(cause error) error {
	t.mu.Lock()
	if t.file != nil {
		t.file.Close()
		t.file = nil
	}
	t.terminalLocked(protocol.TransferFailed, cause.Error())
	t.cond.Broadcast()
	t.mu.Unlock()
	t.mgr.remove(t.id)
	return cause
}

func (t *transfer) terminalLocked(status, msg string) {
	t.status = status
	t.errMsg = msg
	t.finishedAt = time.Now().UnixMilli()
	t.emitLocked(true)
}

// emitLocked publishes telemetry, skipping anything inside progressInterval
// of the last update unless the caller needs this one seen (a status change,
// or the final frame the UI settles on).
func (t *transfer) emitLocked(force bool) {
	now := time.Now()
	if !force && now.Sub(t.lastEmit) < progressInterval {
		return
	}
	if !t.rateAt.IsZero() {
		if dt := now.Sub(t.rateAt).Seconds(); dt > 0 {
			instant := float64(t.done-t.rateBytes) / dt
			if t.bps == 0 {
				t.bps = instant
			} else {
				t.bps = rateSmoothing*t.bps + (1-rateSmoothing)*instant
			}
		}
	}
	t.rateAt, t.rateBytes, t.lastEmit = now, t.done, now

	t.mgr.onEvent(Event{
		FileProgress: protocol.FileProgress{
			TransferID:  t.id,
			Name:        t.name,
			Direction:   t.direction,
			Status:      t.status,
			Transferred: t.done,
			Size:        t.size,
			BytesPerSec: int64(t.bps),
			Error:       t.errMsg,
			StartedAt:   t.startedAt,
			FinishedAt:  t.finishedAt,
		},
		DeviceID: t.deviceID,
		Path:     t.path,
		SHA256:   t.wantSHA,
	})
}

// destPath resolves an offered file name to a path inside dir.
//
// The name comes from a paired but still remote peer, so a separator, a parent
// reference, or a volume in it would let that peer write anywhere the daemon
// can reach. Nothing is stripped or normalised: a name that is not a plain
// file name is refused outright, because quietly rewriting it would store the
// file somewhere neither side expects. A colon is refused everywhere, not just
// on Windows, so the same name is accepted or rejected on every host: on NTFS
// it opens an alternate data stream rather than the file it appears to name.
func destPath(dir, name string) (string, error) {
	switch {
	case name == "" || name == "." || name == "..",
		strings.ContainsAny(name, `/\:`+"\x00"),
		filepath.IsAbs(name),
		filepath.VolumeName(name) != "":
		return "", fmt.Errorf("transfer: unsafe file name %q", name)
	}

	ext := filepath.Ext(name)
	stem := strings.TrimSuffix(name, ext)
	candidate := filepath.Join(dir, name)
	for i := 1; ; i++ {
		if _, err := os.Stat(candidate); errors.Is(err, fs.ErrNotExist) {
			return candidate, nil
		}
		if i > 999 {
			return "", fmt.Errorf("transfer: too many files named %q", name)
		}
		candidate = filepath.Join(dir, fmt.Sprintf("%s (%d)%s", stem, i, ext))
	}
}

func hashFile(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := io.Copy(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
