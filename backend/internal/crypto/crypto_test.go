package crypto

import (
	"bytes"
	"testing"
)

// handshake runs the full two-sided derivation and returns both bound
// sessions, mirroring what server.go and the Android client do on the wire.
func handshake(t *testing.T, hostCode, clientCode []byte) (host, client *Session) {
	t.Helper()

	hostID, err := NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	clientID, err := NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	hostEph, err := NewEphemeral()
	if err != nil {
		t.Fatal(err)
	}
	clientEph, err := NewEphemeral()
	if err != nil {
		t.Fatal(err)
	}
	challenge, err := RandomBytes(ChallengeSize)
	if err != nil {
		t.Fatal(err)
	}

	host, err = DeriveSession(hostID, hostEph, clientID.PublicKey(), clientEph.PublicKey(), challenge, hostCode)
	if err != nil {
		t.Fatal(err)
	}
	client, err = DeriveSession(clientID, clientEph, hostID.PublicKey(), hostEph.PublicKey(), challenge, clientCode)
	if err != nil {
		t.Fatal(err)
	}
	if err := host.AsHost(); err != nil {
		t.Fatal(err)
	}
	if err := client.AsClient(); err != nil {
		t.Fatal(err)
	}
	return host, client
}

func TestHandshakeAgreesAndRoundTrips(t *testing.T) {
	code := []byte("pair-code-123")
	host, client := handshake(t, code, code)

	// Both sides must reach the same proof, which is what authenticates the
	// handshake on the wire.
	if err := host.VerifyProof("client", "daemon-1", []byte("pub"),
		client.Proof("client", "daemon-1", []byte("pub"))); err != nil {
		t.Fatalf("proofs disagree: %v", err)
	}

	// client -> host
	msg := []byte(`{"action":"display.brightness.set"}`)
	frame, err := client.Seal(msg)
	if err != nil {
		t.Fatal(err)
	}
	got, err := host.Open(frame)
	if err != nil {
		t.Fatalf("host could not open client frame: %v", err)
	}
	if !bytes.Equal(got, msg) {
		t.Fatalf("round trip mismatch: %q != %q", got, msg)
	}

	// host -> client, opposite direction key
	ev := []byte(`{"action":"host.state"}`)
	frame, err = host.Seal(ev)
	if err != nil {
		t.Fatal(err)
	}
	if got, err = client.Open(frame); err != nil {
		t.Fatalf("client could not open host frame: %v", err)
	}
	if !bytes.Equal(got, ev) {
		t.Fatalf("round trip mismatch: %q != %q", got, ev)
	}
}

func TestWrongPairingCodeFailsProof(t *testing.T) {
	host, client := handshake(t, []byte("correct-code"), []byte("guessed-code"))

	err := host.VerifyProof("client", "daemon-1", []byte("pub"),
		client.Proof("client", "daemon-1", []byte("pub")))
	if err == nil {
		t.Fatal("a client with the wrong pairing code was accepted")
	}
}

func TestReplayedFrameRejected(t *testing.T) {
	code := []byte("code")
	host, client := handshake(t, code, code)

	frame, err := client.Seal([]byte("first"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := host.Open(frame); err != nil {
		t.Fatal(err)
	}
	if _, err := host.Open(frame); err == nil {
		t.Fatal("replayed frame was accepted")
	}
}

func TestTamperedCiphertextRejected(t *testing.T) {
	code := []byte("code")
	host, client := handshake(t, code, code)

	frame, err := client.Seal([]byte("set brightness 10"))
	if err != nil {
		t.Fatal(err)
	}
	frame[len(frame)-1] ^= 0x01 // flip a bit in the auth tag
	if _, err := host.Open(frame); err == nil {
		t.Fatal("tampered frame was accepted")
	}
}

func TestIdentityRoundTripsThroughSeed(t *testing.T) {
	id, err := NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	restored, err := IdentityFromSeed(id.Seed())
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(id.PublicKey(), restored.PublicKey()) {
		t.Fatal("identity did not survive persistence round trip")
	}
}
