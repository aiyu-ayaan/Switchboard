package transfer

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"math/rand"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"switchboard/backend/internal/protocol"
)

const testDevice = "device-1"

// link wires two managers into each other so a test runs the real frame
// sequence — offer, accept, chunk, ack, complete — instead of poking at the
// engine's internals. Frames are delivered synchronously, which makes a
// finished transfer observable without sleeping.
type link struct {
	t        *testing.T
	sender   *Manager
	receiver *Manager
	done     chan protocol.FileProgress

	// Frames cross goroutines: the pump runs on its own, and the test reads
	// these while it is running.
	mu        sync.Mutex
	minOffset int64 // lowest chunk offset seen, to prove a resume skipped bytes
	chunks    int
	delivered int64
	status    string
	// beforeChunk runs on the delivery path, so a test can cut the link at a
	// chosen point in the stream rather than at a guessed moment in time.
	beforeChunk func()

	// down simulates a dropped socket: frames are refused exactly as the
	// daemon refuses them when a device holds no connection.
	down bool
	// firstAfterCut is the offset of the first chunk delivered once the link
	// is restored, which is what tells a resume apart from a restart.
	firstAfterCut int64
	cut           bool
}

func newLink(t *testing.T, downloadDir string) *link {
	t.Helper()
	l := &link{t: t, minOffset: -1, done: make(chan protocol.FileProgress, 64)}

	l.sender = NewManager(
		func() string { return downloadDir },
		func(_, action string, payload any, blob []byte) error { return l.toReceiver(action, payload, blob) },
		func(e Event) {
			l.mu.Lock()
			l.status = e.Status
			l.mu.Unlock()
			switch e.Status {
			case protocol.TransferCompleted, protocol.TransferFailed, protocol.TransferCancelled:
				l.done <- e.FileProgress
			}
		},
	)
	l.receiver = NewManager(
		func() string { return downloadDir },
		func(_, action string, payload any, _ []byte) error { return l.toSender(action, payload) },
		func(Event) {},
	)
	return l
}

func (l *link) toReceiver(action string, payload any, blob []byte) error {
	l.mu.Lock()
	hook := l.beforeChunk
	l.mu.Unlock()
	if hook != nil {
		hook()
	}
	l.mu.Lock()
	down := l.down
	l.mu.Unlock()
	if down {
		return errors.New("transfer: device is not connected")
	}

	switch p := payload.(type) {
	case protocol.FileOffer:
		return l.receiver.Offer(testDevice, p)
	case protocol.FileChunk:
		l.mu.Lock()
		l.chunks++
		l.delivered += int64(len(blob))
		if l.minOffset < 0 || p.Offset < l.minOffset {
			l.minOffset = p.Offset
		}
		if l.cut && l.firstAfterCut < 0 {
			l.firstAfterCut = p.Offset
		}
		l.mu.Unlock()
		return l.receiver.Chunk(p, blob)
	case protocol.FileControl:
		return l.receiver.Control(p)
	}
	l.t.Fatalf("unexpected frame to receiver: %s", action)
	return nil
}

func (l *link) toSender(action string, payload any) error {
	l.mu.Lock()
	down := l.down
	l.mu.Unlock()
	if down {
		return errors.New("transfer: device is not connected")
	}
	switch p := payload.(type) {
	case protocol.FileAccept:
		return l.sender.Accept(p)
	case protocol.FileAck:
		return l.sender.Ack(p)
	case protocol.FileComplete:
		return l.sender.Complete(p)
	case protocol.FileControl:
		return l.sender.Control(p)
	}
	l.t.Fatalf("unexpected frame to sender: %s", action)
	return nil
}

// parked reports whether the sender has suspended itself after losing the
// link, which is what the daemon observes as a paused transfer.
func (l *link) parked() bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.status == protocol.TransferPaused
}

func (l *link) bytesDelivered() int64 {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.delivered
}

func (l *link) drop() {
	l.mu.Lock()
	l.down = true
	l.mu.Unlock()
}

func (l *link) restore() {
	l.mu.Lock()
	l.down, l.cut, l.beforeChunk = false, true, nil
	l.mu.Unlock()
}

func (l *link) resumedAt() int64 {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.firstAfterCut
}

func (l *link) wait() protocol.FileProgress {
	l.t.Helper()
	select {
	case p := <-l.done:
		return p
	case <-time.After(30 * time.Second):
		l.t.Fatal("transfer did not finish")
		return protocol.FileProgress{}
	}
}

// writeFile lays down deterministic pseudo-random bytes: real content, so a
// digest check would catch a swapped or truncated chunk rather than matching
// a file of zeroes against itself.
func writeFile(t *testing.T, path string, size int) []byte {
	t.Helper()
	data := make([]byte, size)
	rng := rand.New(rand.NewSource(int64(size)))
	rng.Read(data)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	return data
}

func sum(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

func TestRoundTrip(t *testing.T) {
	src := t.TempDir()
	dst := t.TempDir()
	// Several chunks and several ack windows, with a tail that is not a whole
	// chunk: a size that divides evenly would hide an off-by-one at the end.
	data := writeFile(t, filepath.Join(src, "report.pdf"), 5*1024*1024+7)

	l := newLink(t, dst)
	if _, err := l.sender.Send(testDevice, filepath.Join(src, "report.pdf")); err != nil {
		t.Fatal(err)
	}
	final := l.wait()
	if final.Status != protocol.TransferCompleted {
		t.Fatalf("status = %q, error = %q", final.Status, final.Error)
	}
	if final.Transferred != int64(len(data)) {
		t.Fatalf("transferred %d bytes, want %d", final.Transferred, len(data))
	}

	got, err := os.ReadFile(filepath.Join(dst, "report.pdf"))
	if err != nil {
		t.Fatal(err)
	}
	if sum(got) != sum(data) {
		t.Fatal("received file does not match the source digest")
	}
	if len(got) != len(data) {
		t.Fatalf("received %d bytes, want %d", len(got), len(data))
	}
	if _, err := os.Stat(filepath.Join(dst, "report.pdf.part")); !os.IsNotExist(err) {
		t.Fatal("the .part file survived a completed transfer")
	}
}

func TestResumeSkipsTransferredBytes(t *testing.T) {
	src := t.TempDir()
	dst := t.TempDir()
	data := writeFile(t, filepath.Join(src, "archive.zip"), 3*1024*1024)

	// A previous attempt left the first megabyte on disk.
	const held = 1024 * 1024
	if err := os.WriteFile(filepath.Join(dst, "archive.zip.part"), data[:held], 0o600); err != nil {
		t.Fatal(err)
	}

	l := newLink(t, dst)
	if _, err := l.sender.Send(testDevice, filepath.Join(src, "archive.zip")); err != nil {
		t.Fatal(err)
	}
	if final := l.wait(); final.Status != protocol.TransferCompleted {
		t.Fatalf("status = %q, error = %q", final.Status, final.Error)
	}
	if l.minOffset != held {
		t.Fatalf("first chunk offset = %d, want %d: the sender restarted instead of seeking",
			l.minOffset, held)
	}

	got, err := os.ReadFile(filepath.Join(dst, "archive.zip"))
	if err != nil {
		t.Fatal(err)
	}
	if sum(got) != sum(data) {
		t.Fatal("resumed file does not match the source digest")
	}
}

func TestCorruptDigestLeavesNoFile(t *testing.T) {
	dst := t.TempDir()
	data := make([]byte, 4096)

	var complete protocol.FileComplete
	m := NewManager(
		func() string { return dst },
		func(_, action string, payload any, _ []byte) error {
			if c, ok := payload.(protocol.FileComplete); ok {
				complete = c
			}
			return nil
		},
		func(Event) {},
	)

	// The offer claims a digest the bytes cannot produce, which is what a
	// corrupted or tampered-with stream looks like from the receiver's side.
	if err := m.Offer(testDevice, protocol.FileOffer{
		TransferID: "bad", Name: "photo.jpg", Size: int64(len(data)),
		SHA256: strings.Repeat("0", 64), Direction: protocol.DirectionUpload,
	}); err != nil {
		t.Fatal(err)
	}
	if err := m.Chunk(protocol.FileChunk{
		TransferID: "bad", Offset: 0, Last: true,
	}, data); err != nil {
		t.Fatal(err)
	}

	if complete.OK {
		t.Fatal("receiver accepted a file whose digest did not match")
	}
	for _, name := range []string{"photo.jpg", "photo.jpg.part"} {
		if _, err := os.Stat(filepath.Join(dst, name)); !os.IsNotExist(err) {
			t.Fatalf("%s exists after a digest failure", name)
		}
	}
}

func TestCancelRemovesPartFile(t *testing.T) {
	dst := t.TempDir()
	m := NewManager(
		func() string { return dst },
		func(string, string, any, []byte) error { return nil },
		func(Event) {},
	)

	if err := m.Offer(testDevice, protocol.FileOffer{
		TransferID: "cancel-me", Name: "movie.mkv", Size: 1 << 20,
		Direction: protocol.DirectionUpload,
	}); err != nil {
		t.Fatal(err)
	}
	if err := m.Chunk(protocol.FileChunk{
		TransferID: "cancel-me", Offset: 0,
	}, make([]byte, 4096)); err != nil {
		t.Fatal(err)
	}
	part := filepath.Join(dst, "movie.mkv.part")
	if _, err := os.Stat(part); err != nil {
		t.Fatalf("expected a .part file mid-transfer: %v", err)
	}

	if err := m.Control(protocol.FileControl{
		TransferID: "cancel-me", Action: protocol.ControlCancel,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(part); !os.IsNotExist(err) {
		t.Fatal("cancel left the .part file behind")
	}
}

func TestDestPath(t *testing.T) {
	dir := t.TempDir()

	unsafe := []string{
		"../escape.txt",
		"..\\escape.txt",
		"sub/dir.txt",
		"sub\\dir.txt",
		"/etc/passwd",
		"C:\\Windows\\system32\\evil.dll",
		"C:relative.txt",
		"..",
		".",
		"",
		"nul\x00.txt",
	}
	for _, name := range unsafe {
		if got, err := destPath(dir, name); err == nil {
			t.Errorf("destPath(%q) = %q, want an error", name, got)
		}
	}

	safe, err := destPath(dir, "report.pdf")
	if err != nil {
		t.Fatal(err)
	}
	if safe != filepath.Join(dir, "report.pdf") {
		t.Fatalf("destPath = %q, want %q", safe, filepath.Join(dir, "report.pdf"))
	}

	// A name already taken must not be silently overwritten.
	if err := os.WriteFile(safe, []byte("first"), 0o600); err != nil {
		t.Fatal(err)
	}
	second, err := destPath(dir, "report.pdf")
	if err != nil {
		t.Fatal(err)
	}
	if second != filepath.Join(dir, "report (1).pdf") {
		t.Fatalf("collision resolved to %q, want %q", second, filepath.Join(dir, "report (1).pdf"))
	}
}

func TestSanitizeOfferName(t *testing.T) {
	cases := map[string]string{
		"Recording 2024-05-07 23:44:12.mp4": "Recording 2024-05-07 23-44-12.mp4",
		"primary:Valorant/clip.mp4":          "clip.mp4",
		"../escape/video:part.mkv":          "video-part.mkv",
		"":                                  "file",
		"   ":                               "file",
		"..":                                "file",
	}
	for in, want := range cases {
		got := sanitizeOfferName(in)
		if got != want {
			t.Errorf("sanitizeOfferName(%q) = %q, want %q", in, got, want)
		}
	}
}


// A dropped connection must not cost the bytes already moved. This cuts the
// link mid-file, parks both ends the way the daemon does when a socket dies,
// restores it, and checks that the transfer picks up rather than starting the
// file again — which on a large file over flaky Wi-Fi is the whole difference
// between a transfer that eventually finishes and one that never can.
func TestReconnectResumesInsteadOfRestarting(t *testing.T) {
	src := t.TempDir()
	dst := t.TempDir()
	data := writeFile(t, filepath.Join(src, "video.mp4"), 6*1024*1024)

	l := newLink(t, dst)
	l.firstAfterCut = -1

	// Drop the link once enough chunks have landed that a restart would be
	// obvious in the offsets.
	l.beforeChunk = func() {
		l.mu.Lock()
		enough := l.chunks >= 6
		l.mu.Unlock()
		if enough {
			l.drop()
		}
	}

	if _, err := l.sender.Send(testDevice, filepath.Join(src, "video.mp4")); err != nil {
		t.Fatal(err)
	}

	// Wait for the sender to notice the dead link and park itself.
	deadline := time.Now().Add(10 * time.Second)
	for !l.parked() {
		if time.Now().After(deadline) {
			t.Fatal("the sender never parked after the link dropped")
		}
		time.Sleep(10 * time.Millisecond)
	}

	// What the daemon does on disconnect, then on reconnect.
	l.sender.Detach(testDevice)
	l.receiver.Detach(testDevice)

	held := l.bytesDelivered()
	if held == 0 {
		t.Fatal("nothing was delivered before the cut, so the test proves nothing")
	}

	l.restore()
	l.sender.Reattach(testDevice)

	if final := l.wait(); final.Status != protocol.TransferCompleted {
		t.Fatalf("status = %q, error = %q", final.Status, final.Error)
	}
	if resumed := l.resumedAt(); resumed <= 0 {
		t.Fatalf("the first chunk after reconnecting was at offset %d: "+
			"the sender restarted the file instead of resuming", resumed)
	}

	got, err := os.ReadFile(filepath.Join(dst, "video.mp4"))
	if err != nil {
		t.Fatal(err)
	}
	if sum(got) != sum(data) {
		t.Fatal("the resumed file does not match the source digest")
	}
}
