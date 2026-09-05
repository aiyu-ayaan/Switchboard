package server

import (
	"crypto/ecdh"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"strings"
	"testing"

	"switchboard/backend/internal/protocol"
)

// enrolled registers a fresh biometric key for deviceID and returns it. The
// store is written directly: enrolment itself refuses on a host with no
// password, which is a separate rule with its own test.
func enrolled(t *testing.T, s *Server, deviceID string) *ecdsa.PrivateKey {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.store.SaveUnlockKey(deviceID, der); err != nil {
		t.Fatal(err)
	}
	return key
}

// prove signs the message the host will reconstruct, the way the phone does.
func prove(t *testing.T, key *ecdsa.PrivateKey, daemonID string, challenge []byte) protocol.UnlockProof {
	t.Helper()
	digest := sha256.Sum256(protocol.UnlockMessage(daemonID, challenge))
	signature, err := ecdsa.SignASN1(rand.Reader, key, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	return protocol.UnlockProof{
		Challenge: base64.StdEncoding.EncodeToString(challenge),
		Signature: base64.StdEncoding.EncodeToString(signature),
	}
}

func challengeFor(t *testing.T, s *Server, c *client) []byte {
	t.Helper()
	encoded, err := s.issueUnlockChallenge(c)
	if err != nil {
		t.Fatal(err)
	}
	challenge, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		t.Fatal(err)
	}
	return challenge
}

func TestUnlockAcceptsAProperProof(t *testing.T) {
	srv, _ := newHarness(t)
	c := &client{deviceID: "phone-1"}
	key := enrolled(t, srv, c.deviceID)

	proof := prove(t, key, srv.daemonID, challengeFor(t, srv, c))
	if err := srv.verifyUnlock(c, proof); err != nil {
		t.Fatalf("a correctly signed challenge was rejected: %v", err)
	}
}

// The nonce is the only thing between a captured signature and a replay, so
// spending it must not depend on the outcome of the attempt.
func TestUnlockChallengeIsSingleUse(t *testing.T) {
	srv, _ := newHarness(t)
	c := &client{deviceID: "phone-1"}
	key := enrolled(t, srv, c.deviceID)

	proof := prove(t, key, srv.daemonID, challengeFor(t, srv, c))
	if err := srv.verifyUnlock(c, proof); err != nil {
		t.Fatal(err)
	}
	if err := srv.verifyUnlock(c, proof); err == nil {
		t.Fatal("the same proof unlocked twice")
	}
}

func TestUnlockChallengeIsSpentByAFailedAttempt(t *testing.T) {
	srv, _ := newHarness(t)
	c := &client{deviceID: "phone-1"}
	key := enrolled(t, srv, c.deviceID)
	challenge := challengeFor(t, srv, c)

	bad := prove(t, key, srv.daemonID, challenge)
	bad.Signature = base64.StdEncoding.EncodeToString([]byte("not a signature"))
	if err := srv.verifyUnlock(c, bad); err == nil {
		t.Fatal("a garbage signature was accepted")
	}
	// A live challenge after a failure would let an attacker grind against it.
	if err := srv.verifyUnlock(c, prove(t, key, srv.daemonID, challenge)); err == nil {
		t.Fatal("the challenge survived a failed attempt")
	}
}

// A proof is bound to one host, so one lifted off another desktop is useless.
func TestUnlockProofDoesNotTransferBetweenHosts(t *testing.T) {
	srv, _ := newHarness(t)
	c := &client{deviceID: "phone-1"}
	key := enrolled(t, srv, c.deviceID)

	proof := prove(t, key, "some-other-daemon", challengeFor(t, srv, c))
	if err := srv.verifyUnlock(c, proof); err == nil {
		t.Fatal("a proof signed for another daemon was accepted")
	}
}

// Holding the session key is not enough: the phone must also hold the key its
// keystore only releases behind a fingerprint.
func TestUnlockRejectsAnUnenrolledDevice(t *testing.T) {
	srv, _ := newHarness(t)
	c := &client{deviceID: "phone-1"}
	key := enrolled(t, srv, c.deviceID)
	proof := prove(t, key, srv.daemonID, challengeFor(t, srv, c))

	stranger := &client{deviceID: "phone-2"}
	challengeFor(t, srv, stranger)
	if err := srv.verifyUnlock(stranger, proof); err == nil {
		t.Fatal("an unenrolled device unlocked the host")
	}
}

func TestUnlockNeedsAnOutstandingChallenge(t *testing.T) {
	srv, _ := newHarness(t)
	c := &client{deviceID: "phone-1"}
	key := enrolled(t, srv, c.deviceID)

	proof := prove(t, key, srv.daemonID, []byte("a challenge nobody issued"))
	if err := srv.verifyUnlock(c, proof); err == nil {
		t.Fatal("a self-issued challenge was accepted")
	}
}

// Forgetting a device must take its unlock key with it, or re-pairing would
// silently restore a right the user withdrew.
func TestRevokingADeviceDropsItsUnlockKey(t *testing.T) {
	srv, _ := newHarness(t)
	if err := srv.store.UpsertDevice("phone-1", "Phone", []byte("public-key")); err != nil {
		t.Fatal(err)
	}
	enrolled(t, srv, "phone-1")

	if err := srv.store.RevokeDevice("phone-1"); err != nil {
		t.Fatal(err)
	}
	if _, err := srv.store.UnlockKey("phone-1"); err == nil {
		t.Fatal("the unlock key outlived the device")
	}
}

func TestUnlockKeyMustBeP256(t *testing.T) {
	if _, err := parseUnlockKey([]byte("not DER at all")); err == nil {
		t.Fatal("garbage was accepted as a public key")
	}

	// An X25519 key is well-formed PKIX but cannot verify a signature.
	x, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	der, err := x509.MarshalPKIXPublicKey(x.PublicKey())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := parseUnlockKey(der); err == nil {
		t.Fatal("a non-ECDSA key was accepted")
	}

	p384, err := ecdsa.GenerateKey(elliptic.P384(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	if der, err := x509.MarshalPKIXPublicKey(&p384.PublicKey); err == nil {
		if _, err := parseUnlockKey(der); err == nil {
			t.Fatal("a P-384 key was accepted; the curve must be pinned")
		}
	}
}

// UnlockMessage frames its fields so no two different inputs can produce the
// same bytes, which is what stops a proof for one host reading as another.
func TestUnlockMessageIsUnambiguous(t *testing.T) {
	a := protocol.UnlockMessage("daemon", []byte("AB"))
	b := protocol.UnlockMessage("daemonAB", nil)
	if string(a) == string(b) {
		t.Fatal("field boundaries are not encoded; the message is ambiguous")
	}
	if !strings.HasPrefix(string(a), "switchboard-unlock-v1\x00") {
		t.Fatal("the domain-separation label is missing")
	}
}
