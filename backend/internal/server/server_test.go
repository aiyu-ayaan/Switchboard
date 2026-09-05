package server

import (
	"encoding/base64"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"

	"switchboard/backend/internal/config"
	"switchboard/backend/internal/crypto"
	"switchboard/backend/internal/db"
	"switchboard/backend/internal/protocol"
	"switchboard/backend/internal/system"
)

var b64 = base64.RawURLEncoding

// testClient is a minimal mobile client: the same handshake the Android
// SwitchboardClient performs, so this exercises the real wire format.
type testClient struct {
	conn     *websocket.Conn
	session  *crypto.Session
	deviceID string
}

func newHarness(t *testing.T) (*Server, *httptest.Server) {
	t.Helper()

	store, err := db.Open(filepath.Join(t.TempDir(), "test.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { store.Close() })

	control := system.NewController()
	t.Cleanup(control.Close)

	srv, err := New(&config.Config{Port: 0}, store, control)
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(srv.Handler())
	t.Cleanup(ts.Close)
	return srv, ts
}

// dial performs the handshake. identity is reused across calls to simulate the
// same phone reconnecting; pairingCode is nil for a resume.
func dial(t *testing.T, ts *httptest.Server, identity *crypto.Identity, mode string, pairingCode []byte) (*testClient, error) {
	t.Helper()

	url := "ws" + strings.TrimPrefix(ts.URL, "http") + "/ws"
	conn, _, err := websocket.DefaultDialer.Dial(url, nil)
	if err != nil {
		t.Fatal(err)
	}

	var hello helloMessage
	if err := conn.ReadJSON(&hello); err != nil {
		t.Fatal(err)
	}

	hostID, err := b64.DecodeString(hello.IdentityKey)
	if err != nil {
		t.Fatal(err)
	}
	hostEph, err := b64.DecodeString(hello.EphemeralKey)
	if err != nil {
		t.Fatal(err)
	}
	challenge, err := b64.DecodeString(hello.Challenge)
	if err != nil {
		t.Fatal(err)
	}

	eph, err := crypto.NewEphemeral()
	if err != nil {
		t.Fatal(err)
	}
	session, err := crypto.DeriveSession(identity, eph, hostID, hostEph, challenge, pairingCode)
	if err != nil {
		t.Fatal(err)
	}
	if err := session.AsClient(); err != nil {
		t.Fatal(err)
	}

	auth := authMessage{
		Mode:         mode,
		IdentityKey:  b64.EncodeToString(identity.PublicKey()),
		EphemeralKey: b64.EncodeToString(eph.PublicKey()),
		DeviceName:   "Test Phone",
		Proof:        b64.EncodeToString(session.Proof("client", hello.DaemonID, identity.PublicKey())),
	}
	if err := conn.WriteJSON(auth); err != nil {
		t.Fatal(err)
	}

	var result authResult
	if err := conn.ReadJSON(&result); err != nil {
		t.Fatal(err)
	}
	if !result.OK {
		conn.Close()
		return nil, &authError{result.Error}
	}

	// The host must prove it holds the same session key, or it is an impostor.
	hostProof, err := b64.DecodeString(result.Proof)
	if err != nil {
		t.Fatal(err)
	}
	if err := session.VerifyProof("host", hello.DaemonID, identity.PublicKey(), hostProof); err != nil {
		t.Fatalf("host failed to prove itself: %v", err)
	}

	t.Cleanup(func() { conn.Close() })
	return &testClient{conn: conn, session: session, deviceID: result.DeviceID}, nil
}

type authError struct{ msg string }

func (e *authError) Error() string { return e.msg }

// call sends an encrypted command and returns the first envelope that carries
// the matching correlation ID, skipping the state events pushed alongside it.
func (c *testClient) call(t *testing.T, action string, payload any) *protocol.Envelope {
	t.Helper()

	req, err := protocol.New(protocol.TypeCommand, action, payload)
	if err != nil {
		t.Fatal(err)
	}
	raw, err := protocol.EncodeFrame(req, nil)
	if err != nil {
		t.Fatal(err)
	}
	frame, err := c.session.Seal(raw)
	if err != nil {
		t.Fatal(err)
	}
	if err := c.conn.WriteMessage(websocket.BinaryMessage, frame); err != nil {
		t.Fatal(err)
	}

	c.conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	for {
		_, frame, err := c.conn.ReadMessage()
		if err != nil {
			t.Fatalf("reading reply to %s: %v", action, err)
		}
		plain, err := c.session.Open(frame)
		if err != nil {
			t.Fatalf("decrypting reply to %s: %v", action, err)
		}
		env, _, err := protocol.DecodeFrame(plain)
		if err != nil {
			t.Fatal(err)
		}
		if env.ID == req.ID {
			return env
		}
	}
}

func TestPairThenResumeAndCommand(t *testing.T) {
	srv, ts := newHarness(t)

	identity, err := crypto.NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	code := []byte(srv.PairingInfo().Code)

	// First contact: pairs using the QR code.
	paired, err := dial(t, ts, identity, modePair, code)
	if err != nil {
		t.Fatalf("pairing rejected: %v", err)
	}
	if paired.deviceID == "" {
		t.Fatal("pairing returned no device ID")
	}

	// The encrypted command channel works after the handshake.
	if got := paired.call(t, protocol.ActionPing, nil); got.Type != protocol.TypeResponse {
		t.Fatalf("ping returned %s: %s", got.Type, got.Payload)
	}

	devices, err := srv.Devices()
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 1 || devices[0].Name != "Test Phone" {
		t.Fatalf("expected one stored device named Test Phone, got %+v", devices)
	}
	paired.conn.Close()

	// Reconnecting needs no QR code: the stored identity key is the credential.
	resumed, err := dial(t, ts, identity, modeResume, nil)
	if err != nil {
		t.Fatalf("resume rejected: %v", err)
	}
	if resumed.deviceID != paired.deviceID {
		t.Fatalf("resume changed device ID: %s -> %s", paired.deviceID, resumed.deviceID)
	}
}

func TestResumeRejectedForUnpairedDevice(t *testing.T) {
	_, ts := newHarness(t)

	stranger, err := crypto.NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := dial(t, ts, stranger, modeResume, nil); err == nil {
		t.Fatal("an unpaired device was allowed to resume")
	}
}

func TestPairRejectedWithWrongCode(t *testing.T) {
	_, ts := newHarness(t)

	identity, err := crypto.NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := dial(t, ts, identity, modePair, []byte("not-the-real-code")); err == nil {
		t.Fatal("pairing succeeded with the wrong code")
	}
}

func TestRevokedDeviceCannotReconnect(t *testing.T) {
	srv, ts := newHarness(t)

	identity, err := crypto.NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	code := []byte(srv.PairingInfo().Code)
	paired, err := dial(t, ts, identity, modePair, code)
	if err != nil {
		t.Fatal(err)
	}

	if err := srv.RevokeDevice(paired.deviceID); err != nil {
		t.Fatal(err)
	}
	if _, err := dial(t, ts, identity, modeResume, nil); err == nil {
		t.Fatal("a revoked device reconnected")
	}
}

func TestRotatePairingInvalidatesOldCode(t *testing.T) {
	srv, ts := newHarness(t)

	oldCode := []byte(srv.PairingInfo().Code)
	if _, err := srv.RotatePairing(); err != nil {
		t.Fatal(err)
	}

	identity, err := crypto.NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := dial(t, ts, identity, modePair, oldCode); err == nil {
		t.Fatal("a rotated-away pairing code still worked")
	}
}

func TestPairingCodeIsTypeable(t *testing.T) {
	srv, _ := newHarness(t)

	seen := map[string]bool{}
	for i := 0; i < 50; i++ {
		info, err := srv.RotatePairing()
		if err != nil {
			t.Fatal(err)
		}
		if len(info.Code) != pairingCodeLength {
			t.Fatalf("code %q is %d characters, want %d", info.Code, len(info.Code), pairingCodeLength)
		}
		for _, c := range info.Code {
			if !strings.ContainsRune(pairingAlphabet, c) {
				t.Fatalf("code %q contains %q, which is not in the typeable alphabet", info.Code, c)
			}
		}
		// The QR must carry the same secret the user would type, or scanning
		// and typing would produce different sessions.
		if !strings.Contains(info.QRPayload, info.Code) {
			t.Fatalf("QR payload does not carry the displayed code: %s", info.QRPayload)
		}
		seen[info.Code] = true
	}
	if len(seen) != 50 {
		t.Fatalf("rotation repeated a code: %d unique out of 50", len(seen))
	}
}

// A phone stays paired across a daemon restart: both halves of the credential
// come back from SQLite rather than being regenerated. If the host minted a new
// identity on boot, every previously paired device would silently fall back to
// needing the QR code again.
func TestPairingSurvivesDaemonRestart(t *testing.T) {
	dbPath := filepath.Join(t.TempDir(), "restart.db")

	identity, err := crypto.NewIdentity()
	if err != nil {
		t.Fatal(err)
	}

	// --- First run: pair a phone, then shut the daemon down. ---
	store, err := db.Open(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	control := system.NewController()
	srv, err := New(&config.Config{Port: 0}, store, control)
	if err != nil {
		t.Fatal(err)
	}
	ts := httptest.NewServer(srv.Handler())

	before := srv.PairingInfo()
	paired, err := dial(t, ts, identity, modePair, []byte(before.Code))
	if err != nil {
		t.Fatalf("pairing rejected: %v", err)
	}
	paired.conn.Close()
	ts.Close()
	control.Close()
	if err := store.Close(); err != nil {
		t.Fatal(err)
	}

	// --- Restart: same database file, a brand new Server. ---
	reopened, err := db.Open(dbPath)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { reopened.Close() })

	control2 := system.NewController()
	t.Cleanup(control2.Close)

	srv2, err := New(&config.Config{Port: 0}, reopened, control2)
	if err != nil {
		t.Fatal(err)
	}
	ts2 := httptest.NewServer(srv2.Handler())
	t.Cleanup(ts2.Close)

	after := srv2.PairingInfo()
	if after.DaemonID != before.DaemonID {
		t.Fatalf("daemon ID changed across restart: %s -> %s", before.DaemonID, after.DaemonID)
	}
	// The phone pins this key at pairing time and checks it on every resume.
	if after.HostKey != before.HostKey {
		t.Fatalf("host identity key changed across restart: %s -> %s", before.HostKey, after.HostKey)
	}

	devices, err := srv2.Devices()
	if err != nil {
		t.Fatal(err)
	}
	if len(devices) != 1 {
		t.Fatalf("expected the paired device to survive the restart, got %+v", devices)
	}

	// The credential that matters: resume with no pairing code at all.
	resumed, err := dial(t, ts2, identity, modeResume, nil)
	if err != nil {
		t.Fatalf("resume after restart rejected: %v", err)
	}
	if resumed.deviceID != paired.deviceID {
		t.Fatalf("device ID changed across restart: %s -> %s", paired.deviceID, resumed.deviceID)
	}
}

// TestMediaStateAndArtworkOverTheWire walks the path a phone actually takes:
// read the media snapshot out of host.state, then fetch the cover art it names
// as a separate command. The IDs must agree, or a client caching artwork by ID
// re-fetches the same image on every snapshot it receives.
func TestMediaStateAndArtworkOverTheWire(t *testing.T) {
	srv, ts := newHarness(t)

	identity, err := crypto.NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	client, err := dial(t, ts, identity, modePair, []byte(srv.PairingInfo().Code))
	if err != nil {
		t.Fatalf("pairing rejected: %v", err)
	}

	state := srv.control.State(srv.DaemonID())
	hasMedia := false
	for _, c := range state.Capabilities {
		if c == "media" {
			hasMedia = true
		}
	}
	if !hasMedia {
		t.Skip("host reports no media capability")
	}

	reply := client.call(t, protocol.ActionMediaArtwork, nil)
	if reply.Type != protocol.TypeResponse {
		t.Fatalf("media.artwork returned %s: %s", reply.Type, reply.Payload)
	}
	var artwork protocol.MediaArtwork
	if err := reply.Decode(&artwork); err != nil {
		t.Fatalf("decoding artwork: %v", err)
	}

	switch {
	case state.Media.ArtworkID == "":
		if artwork.ArtworkID != "" {
			t.Fatalf("artwork arrived for a snapshot that advertised none: %q", artwork.ArtworkID)
		}
	case artwork.ArtworkID != state.Media.ArtworkID:
		t.Fatalf("artwork id %q does not match snapshot id %q",
			artwork.ArtworkID, state.Media.ArtworkID)
	case artwork.Data == "":
		t.Fatal("snapshot advertised artwork but none came back")
	}
}
