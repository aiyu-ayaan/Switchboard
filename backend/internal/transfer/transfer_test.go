package transfer

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"math/rand"
	"os"
	"path/filepath"
	"strings"
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
	t         *testing.T
	sender    *Manager
	receiver  *Manager
	minOffset int64 // lowest chunk offset seen, to prove a resume skipped bytes
	chunks    int
	done      chan protocol.FileProgress
}

func newLink(t *testing.T, downloadDir string) *link {
	t.Helper()
	l := &link{t: t, minOffset: -1, done: make(chan protocol.FileProgress, 64)}

	l.sender = NewManager(
		func() string { return downloadDir },
		func(_, action string, payload any) error { return l.toReceiver(action, payload) },
		func(e Event) {
			switch e.Status {
			case protocol.TransferCompleted, protocol.TransferFailed, protocol.TransferCancelled:
				l.done <- e.FileProgress
			}
		},
	)
	l.receiver = NewManager(
		func() string { return downloadDir },
		func(_, action string, payload any) error { return l.toSender(action, payload) },
		func(Event) {},
	)
	return l
}

func (l *link) toReceiver(action string, payload any) error {
	switch p := payload.(type) {
	case protocol.FileOffer:
		return l.receiver.Offer(testDevice, p)
	case protocol.FileChunk:
		l.chunks++
		if l.minOffset < 0 || p.Offset < l.minOffset {
			l.minOffset = p.Offset
		}
		return l.receiver.Chunk(p)
	case protocol.FileControl:
		return l.receiver.Control(p)
	}
	l.t.Fatalf("unexpected frame to receiver: %s", action)
	return nil
}

func (l *link) toSender(action string, payload any) error {
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
		func(_, action string, payload any) error {
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
		TransferID: "bad", Offset: 0,
		Data: base64.StdEncoding.EncodeToString(data), Last: true,
	}); err != nil {
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
		func(string, string, any) error { return nil },
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
		Data: base64.StdEncoding.EncodeToString(make([]byte, 4096)),
	}); err != nil {
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
