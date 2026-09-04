// Package crypto implements Switchboard's zero-password pairing and the
// authenticated encryption used on every WebSocket frame.
//
// Handshake (see development/devdocs/security-pairing.md):
//
//	host   -> client  hello { daemonId, hostIdentityPub, hostEphemeralPub, challenge }
//	client -> host    auth  { mode, clientIdentityPub, clientEphemeralPub, name, proof }
//	host   -> client  authOk / error
//
// The session key mixes two Diffie-Hellman results:
//
//	ephemeral x ephemeral  forward secrecy: a stolen identity key cannot
//	                       decrypt previously recorded sessions
//	identity  x identity   mutual authentication: only the paired device, or
//	                       the holder of the QR code, derives the same key
//
// During pairing the QR code's pairing code is folded into the KDF salt, so a
// network attacker who never saw the QR cannot complete the handshake even
// though the host identity key is broadcast publicly.
package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/ecdh"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"io"

	"golang.org/x/crypto/hkdf"
)

const (
	// KeySize is the X25519 public/private key length.
	KeySize = 32
	// ChallengeSize is the per-connection random salt contributed by the host.
	ChallengeSize = 32
	// NonceSize is the AES-GCM nonce length.
	NonceSize = 12

	infoSession = "switchboard-session-v1"
	infoH2C     = "switchboard-host-to-client-v1"
	infoC2H     = "switchboard-client-to-host-v1"
	infoProof   = "switchboard-proof-v1"
)

var (
	// ErrBadProof means the peer could not prove possession of the expected
	// keys: wrong pairing code, unknown device, or an active MITM.
	ErrBadProof = errors.New("crypto: handshake proof mismatch")
	// ErrNonceExhausted means the per-direction counter wrapped. The session
	// must be torn down rather than risk nonce reuse.
	ErrNonceExhausted = errors.New("crypto: nonce counter exhausted")
)

// Identity is a long-lived X25519 keypair. The host persists one in SQLite;
// each mobile client persists one in EncryptedSharedPreferences.
type Identity struct {
	priv *ecdh.PrivateKey
}

// NewIdentity generates a fresh identity keypair.
func NewIdentity() (*Identity, error) {
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	return &Identity{priv: priv}, nil
}

// IdentityFromSeed restores an identity from its stored 32-byte private key.
func IdentityFromSeed(seed []byte) (*Identity, error) {
	priv, err := ecdh.X25519().NewPrivateKey(seed)
	if err != nil {
		return nil, err
	}
	return &Identity{priv: priv}, nil
}

// Seed returns the raw private key for persistence. Callers must store it
// where only the host user can read it.
func (i *Identity) Seed() []byte { return i.priv.Bytes() }

// PublicKey returns the raw 32-byte public key.
func (i *Identity) PublicKey() []byte { return i.priv.PublicKey().Bytes() }

// PublicKeyB64 returns the public key encoded for the QR payload.
func (i *Identity) PublicKeyB64() string {
	return base64.RawURLEncoding.EncodeToString(i.PublicKey())
}

// Ephemeral is a single-use keypair generated per connection.
type Ephemeral struct {
	priv *ecdh.PrivateKey
}

// NewEphemeral generates a per-connection keypair.
func NewEphemeral() (*Ephemeral, error) {
	priv, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	return &Ephemeral{priv: priv}, nil
}

// PublicKey returns the raw 32-byte ephemeral public key.
func (e *Ephemeral) PublicKey() []byte { return e.priv.PublicKey().Bytes() }

// RandomBytes returns n cryptographically random bytes.
func RandomBytes(n int) ([]byte, error) {
	b := make([]byte, n)
	if _, err := io.ReadFull(rand.Reader, b); err != nil {
		return nil, err
	}
	return b, nil
}

// DeriveSession performs the double Diffie-Hellman and expands the result into
// the two directional AES-256-GCM keys plus a proof key.
//
// peerIdentityPub and peerEphemeralPub are the raw 32-byte keys received from
// the peer. pairingCode is the QR code secret during pairing, or nil when
// resuming an already-trusted device.
func DeriveSession(id *Identity, eph *Ephemeral, peerIdentityPub, peerEphemeralPub, challenge, pairingCode []byte) (*Session, error) {
	peerID, err := ecdh.X25519().NewPublicKey(peerIdentityPub)
	if err != nil {
		return nil, fmt.Errorf("crypto: bad peer identity key: %w", err)
	}
	peerEph, err := ecdh.X25519().NewPublicKey(peerEphemeralPub)
	if err != nil {
		return nil, fmt.Errorf("crypto: bad peer ephemeral key: %w", err)
	}

	ephShared, err := eph.priv.ECDH(peerEph)
	if err != nil {
		return nil, err
	}
	idShared, err := id.priv.ECDH(peerID)
	if err != nil {
		return nil, err
	}

	ikm := make([]byte, 0, len(ephShared)+len(idShared))
	ikm = append(ikm, ephShared...)
	ikm = append(ikm, idShared...)

	// The pairing code joins the salt: without it an eavesdropper who knows
	// both public identity keys still cannot reach the same master secret.
	salt := make([]byte, 0, len(challenge)+len(pairingCode))
	salt = append(salt, challenge...)
	salt = append(salt, pairingCode...)

	master := make([]byte, 32)
	if _, err := io.ReadFull(hkdf.New(sha256.New, ikm, salt, []byte(infoSession)), master); err != nil {
		return nil, err
	}

	s := &Session{}
	for _, spec := range []struct {
		info string
		dst  *[]byte
	}{
		{infoH2C, &s.hostToClient},
		{infoC2H, &s.clientToHost},
		{infoProof, &s.proofKey},
	} {
		k := make([]byte, 32)
		if _, err := io.ReadFull(hkdf.New(sha256.New, master, nil, []byte(spec.info)), k); err != nil {
			return nil, err
		}
		*spec.dst = k
	}
	return s, nil
}

// Session holds the derived directional keys for one connection.
type Session struct {
	hostToClient []byte
	clientToHost []byte
	proofKey     []byte

	sendCtr uint64
	recvCtr uint64

	sendAEAD cipher.AEAD
	recvAEAD cipher.AEAD
}

// AsHost binds the session to the host send/receive directions.
func (s *Session) AsHost() error { return s.bind(s.hostToClient, s.clientToHost) }

// AsClient binds the session to the client send/receive directions.
func (s *Session) AsClient() error { return s.bind(s.clientToHost, s.hostToClient) }

func (s *Session) bind(send, recv []byte) error {
	sb, err := aes.NewCipher(send)
	if err != nil {
		return err
	}
	rb, err := aes.NewCipher(recv)
	if err != nil {
		return err
	}
	if s.sendAEAD, err = cipher.NewGCM(sb); err != nil {
		return err
	}
	if s.recvAEAD, err = cipher.NewGCM(rb); err != nil {
		return err
	}
	return nil
}

// Proof binds the handshake to both identities so each side can verify the
// other derived the same key. label distinguishes the two directions.
func (s *Session) Proof(label, daemonID string, clientPub []byte) []byte {
	m := hmac.New(sha256.New, s.proofKey)
	m.Write([]byte(label))
	m.Write([]byte(daemonID))
	m.Write(clientPub)
	return m.Sum(nil)
}

// VerifyProof compares a received proof in constant time.
func (s *Session) VerifyProof(label, daemonID string, clientPub, got []byte) error {
	if subtle.ConstantTimeCompare(s.Proof(label, daemonID, clientPub), got) != 1 {
		return ErrBadProof
	}
	return nil
}

// nonce builds a deterministic per-direction nonce: an 8-byte counter in the
// low bytes. Counters never repeat within a session and each direction has its
// own key, so the GCM nonce-reuse requirement is satisfied without randomness.
func nonce(ctr uint64) []byte {
	n := make([]byte, NonceSize)
	binary.BigEndian.PutUint64(n[NonceSize-8:], ctr)
	return n
}

// Seal encrypts one frame. The nonce is prefixed so the peer can detect gaps.
func (s *Session) Seal(plaintext []byte) ([]byte, error) {
	if s.sendCtr == ^uint64(0) {
		return nil, ErrNonceExhausted
	}
	n := nonce(s.sendCtr)
	s.sendCtr++
	return append(n, s.sendAEAD.Seal(nil, n, plaintext, nil)...), nil
}

// Open decrypts one frame and enforces strictly increasing counters, which
// rejects both replayed and reordered frames.
func (s *Session) Open(frame []byte) ([]byte, error) {
	if len(frame) < NonceSize+16 {
		return nil, errors.New("crypto: short frame")
	}
	n := frame[:NonceSize]
	ctr := binary.BigEndian.Uint64(n[NonceSize-8:])
	if ctr < s.recvCtr {
		return nil, errors.New("crypto: replayed or reordered frame")
	}
	pt, err := s.recvAEAD.Open(nil, n, frame[NonceSize:], nil)
	if err != nil {
		return nil, err
	}
	s.recvCtr = ctr + 1
	return pt, nil
}
