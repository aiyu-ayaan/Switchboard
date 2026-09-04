package crypto

import (
	"encoding/hex"
	"testing"
)

// TestPrintInteropVector emits the fixed vector pinned by the Android unit
// test in mobile/app/src/test. Run with -v to regenerate after a protocol
// change, then update both sides together.
func TestPrintInteropVector(t *testing.T) {
	seed := func(b byte) []byte {
		s := make([]byte, 32)
		for i := range s {
			s[i] = b + byte(i)
		}
		return s
	}
	hostID, _ := IdentityFromSeed(seed(1))
	clientID, _ := IdentityFromSeed(seed(100))
	hostEphPriv, _ := IdentityFromSeed(seed(50))
	clientEphPriv, _ := IdentityFromSeed(seed(150))
	hostEph := &Ephemeral{priv: hostEphPriv.priv}
	clientEph := &Ephemeral{priv: clientEphPriv.priv}

	challenge := seed(200)
	code := []byte("pairing-code")

	client, err := DeriveSession(clientID, clientEph, hostID.PublicKey(), hostEph.PublicKey(), challenge, code)
	if err != nil {
		t.Fatal(err)
	}
	host, err := DeriveSession(hostID, hostEph, clientID.PublicKey(), clientEph.PublicKey(), challenge, code)
	if err != nil {
		t.Fatal(err)
	}
	if err := client.AsClient(); err != nil {
		t.Fatal(err)
	}
	if err := host.AsHost(); err != nil {
		t.Fatal(err)
	}

	frame, err := client.Seal([]byte("switchboard-interop"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := host.Open(frame); err != nil {
		t.Fatalf("host cannot open its own peer frame: %v", err)
	}

	t.Logf("hostIdentityPub  = %s", hex.EncodeToString(hostID.PublicKey()))
	t.Logf("hostEphemeralPub = %s", hex.EncodeToString(hostEph.PublicKey()))
	t.Logf("clientIdentityPub= %s", hex.EncodeToString(clientID.PublicKey()))
	hostFrame, err := host.Seal([]byte("host-to-client"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := client.Open(hostFrame); err != nil {
		t.Fatalf("client cannot open host frame: %v", err)
	}

	t.Logf("clientProof      = %s", hex.EncodeToString(client.Proof("client", "daemon-fixed", clientID.PublicKey())))
	t.Logf("hostProof        = %s", hex.EncodeToString(host.Proof("host", "daemon-fixed", clientID.PublicKey())))
	t.Logf("clientFrame      = %s", hex.EncodeToString(frame))
	t.Logf("hostFrame        = %s", hex.EncodeToString(hostFrame))
}
