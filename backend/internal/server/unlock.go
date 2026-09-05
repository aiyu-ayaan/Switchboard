package server

// Fingerprint-gated unlock, host side.
//
// The host never sees a fingerprint, and does not have to believe the phone
// when it claims to have checked one. Android holds an ECDSA P-256 key whose
// private half the keystore releases only after a successful biometric
// prompt; the host holds the public half and checks a signature over a nonce
// it issued itself. A tampered app can skip its own prompt, but it cannot
// produce the signature, so "a finger was presented" is proved by the phone's
// secure hardware rather than asserted by its software.
//
// Everything here runs on the connection's read goroutine, which dispatches
// one frame at a time, so the per-client challenge needs no lock.

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/x509"
	"encoding/base64"
	"errors"
	"fmt"
	"log"

	"switchboard/backend/internal/crypto"
	"switchboard/backend/internal/protocol"
)

// unlockChallengeSize is a full 256 bits: the nonce is the only thing standing
// between a captured signature and a replay, so it is not worth shortening.
const unlockChallengeSize = 32

// enrollUnlock records a device's biometric-gated public key.
func (s *Server) enrollUnlock(c *client, req protocol.UnlockEnroll) error {
	if !s.control.UnlockSupported() {
		return errors.New("no unlock password is enrolled on this host")
	}
	// Enrolment is accepted only from an unlocked desktop. Pairing happens
	// with the owner at the machine, but a phone taken afterwards must not be
	// able to grant itself the lock screen while the owner is away from it.
	if s.control.IsLocked() {
		return errors.New("unlock the desktop before enrolling a fingerprint")
	}

	der, err := base64.StdEncoding.DecodeString(req.PublicKey)
	if err != nil {
		return fmt.Errorf("unlock key: %w", err)
	}
	if _, err := parseUnlockKey(der); err != nil {
		return err
	}
	return s.store.SaveUnlockKey(c.deviceID, der)
}

// issueUnlockChallenge hands out a fresh nonce, replacing any outstanding one.
func (s *Server) issueUnlockChallenge(c *client) (string, error) {
	nonce, err := crypto.RandomBytes(unlockChallengeSize)
	if err != nil {
		return "", err
	}
	c.unlockChallenge = nonce
	return base64.StdEncoding.EncodeToString(nonce), nil
}

// unlock releases the workstation, but only behind verifyUnlock.
func (s *Server) unlock(c *client, req protocol.UnlockProof) error {
	if err := s.verifyUnlock(c, req); err != nil {
		return err
	}
	return s.control.Unlock()
}

// verifyUnlock decides whether this proof earns an unlock. It is kept apart
// from the act so the decision can be tested on a host that has no lock
// screen to open.
func (s *Server) verifyUnlock(c *client, req protocol.UnlockProof) error {
	// The challenge is spent before it is checked. Leaving it live through a
	// failed verification would let a caller grind attempts against one nonce.
	issued := c.unlockChallenge
	c.unlockChallenge = nil
	if len(issued) == 0 {
		return errors.New("no unlock challenge outstanding")
	}

	challenge, err := base64.StdEncoding.DecodeString(req.Challenge)
	if err != nil {
		return fmt.Errorf("unlock challenge: %w", err)
	}
	if subtle.ConstantTimeCompare(issued, challenge) != 1 {
		return errors.New("unlock challenge does not match")
	}
	signature, err := base64.StdEncoding.DecodeString(req.Signature)
	if err != nil {
		return fmt.Errorf("unlock signature: %w", err)
	}

	der, err := s.store.UnlockKey(c.deviceID)
	if err != nil {
		return errors.New("this device has not enrolled a fingerprint")
	}
	pub, err := parseUnlockKey(der)
	if err != nil {
		return err
	}

	digest := sha256.Sum256(protocol.UnlockMessage(s.daemonID, challenge))
	if !ecdsa.VerifyASN1(pub, digest[:], signature) {
		// Worth a line in the log: the only way here is a device holding the
		// session key but not the enrolled biometric key.
		log.Printf("device %q: unlock signature rejected", c.deviceID)
		return errors.New("unlock signature rejected")
	}
	return nil
}

// parseUnlockKey accepts only what Android's keystore can hold in hardware and
// Go can verify from the standard library, so neither side needs a dependency
// and neither can be talked into a curve the other treats differently.
func parseUnlockKey(der []byte) (*ecdsa.PublicKey, error) {
	key, err := x509.ParsePKIXPublicKey(der)
	if err != nil {
		return nil, fmt.Errorf("unlock key: %w", err)
	}
	pub, ok := key.(*ecdsa.PublicKey)
	if !ok || pub.Curve != elliptic.P256() {
		return nil, errors.New("unlock key: want an ECDSA P-256 public key")
	}
	return pub, nil
}
