package server

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"

	"switchboard/backend/internal/crypto"
	"switchboard/backend/internal/db"
	"switchboard/backend/internal/protocol"
)

// Handshake modes sent by the client.
const (
	modePair   = "pair"   // first contact, carries the QR pairing code
	modeResume = "resume" // already-trusted device reconnecting
)

// helloMessage is the host's opening plaintext frame. Publishing the host
// identity key is safe: without the pairing code an attacker still cannot
// derive the session key.
type helloMessage struct {
	Type         string `json:"type"`
	DaemonID     string `json:"daemonId"`
	HostName     string `json:"hostName"`
	IdentityKey  string `json:"identityKey"`
	EphemeralKey string `json:"ephemeralKey"`
	Challenge    string `json:"challenge"`
}

// authMessage is the client's plaintext reply.
type authMessage struct {
	Mode         string `json:"mode"`
	IdentityKey  string `json:"identityKey"`
	EphemeralKey string `json:"ephemeralKey"`
	DeviceName   string `json:"deviceName"`
	Proof        string `json:"proof"`
}

// authResult closes the handshake.
type authResult struct {
	Type     string `json:"type"`
	OK       bool   `json:"ok"`
	DeviceID string `json:"deviceId,omitempty"`
	HostName string `json:"hostName,omitempty"`
	Proof    string `json:"proof,omitempty"`
	Error    string `json:"error,omitempty"`
}

// client is one authenticated mobile connection.
type client struct {
	id       string
	deviceID string
	conn     *websocket.Conn
	session  *crypto.Session

	writeMu sync.Mutex
	once    sync.Once
}

func (c *client) close() {
	c.once.Do(func() { c.conn.Close() })
}

// send encrypts and writes one envelope.
func (c *client) send(env *protocol.Envelope) {
	payload, err := json.Marshal(env)
	if err != nil {
		log.Printf("client %s: marshal: %v", c.id, err)
		return
	}
	c.writeMu.Lock()
	defer c.writeMu.Unlock()

	frame, err := c.session.Seal(payload)
	if err != nil {
		log.Printf("client %s: seal: %v", c.id, err)
		c.close()
		return
	}
	c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	if err := c.conn.WriteMessage(websocket.BinaryMessage, frame); err != nil {
		c.close()
	}
}

func (s *Server) handleWebSocket(w http.ResponseWriter, r *http.Request) {
	conn, err := upgrader.Upgrade(w, r, nil)
	if err != nil {
		return
	}

	c, err := s.handshake(conn)
	if err != nil {
		log.Printf("handshake from %s rejected: %v", r.RemoteAddr, err)
		conn.Close()
		return
	}

	s.addClient(c)
	log.Printf("device %q connected (%s)", c.deviceID, r.RemoteAddr)
	defer func() {
		s.removeClient(c)
		c.close()
		log.Printf("device %q disconnected", c.deviceID)
	}()

	// Push the opening snapshot so the phone renders without asking.
	if env, err := protocol.New(protocol.TypeEvent, protocol.ActionHostState, s.control.State(s.daemonID)); err == nil {
		c.send(env)
	}

	s.readLoop(c)
}

// handshake runs the plaintext key agreement and authenticates the device.
func (s *Server) handshake(conn *websocket.Conn) (*client, error) {
	eph, err := crypto.NewEphemeral()
	if err != nil {
		return nil, err
	}
	challenge, err := crypto.RandomBytes(crypto.ChallengeSize)
	if err != nil {
		return nil, err
	}

	b64 := base64.RawURLEncoding
	hello := helloMessage{
		Type:         "hello",
		DaemonID:     s.daemonID,
		HostName:     s.control.HostName(),
		IdentityKey:  s.identity.PublicKeyB64(),
		EphemeralKey: b64.EncodeToString(eph.PublicKey()),
		Challenge:    b64.EncodeToString(challenge),
	}
	conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	if err := conn.WriteJSON(hello); err != nil {
		return nil, err
	}

	var auth authMessage
	conn.SetReadDeadline(time.Now().Add(30 * time.Second))
	if err := conn.ReadJSON(&auth); err != nil {
		return nil, err
	}

	clientID, err := b64.DecodeString(auth.IdentityKey)
	if err != nil || len(clientID) != crypto.KeySize {
		return nil, reject(conn, "malformed identity key")
	}
	clientEph, err := b64.DecodeString(auth.EphemeralKey)
	if err != nil || len(clientEph) != crypto.KeySize {
		return nil, reject(conn, "malformed ephemeral key")
	}
	proof, err := b64.DecodeString(auth.Proof)
	if err != nil {
		return nil, reject(conn, "malformed proof")
	}

	// Resolve the device and the pairing secret that must be mixed into the
	// key schedule.
	var (
		device      *db.Device
		pairingCode []byte
		pairing     bool
	)
	switch auth.Mode {
	case modePair:
		code, ok := s.currentPairingCode()
		if !ok {
			return nil, reject(conn, "pairing window closed; show a new code")
		}
		pairingCode, pairing = code, true

	case modeResume:
		device, err = s.store.DeviceByPublicKey(clientID)
		if errors.Is(err, db.ErrNotFound) {
			return nil, reject(conn, "device not paired")
		} else if err != nil {
			return nil, reject(conn, "device lookup failed")
		}

	default:
		return nil, reject(conn, "unknown handshake mode")
	}

	session, err := crypto.DeriveSession(s.identity, eph, clientID, clientEph, challenge, pairingCode)
	if err != nil {
		return nil, reject(conn, "key agreement failed")
	}
	if err := session.VerifyProof("client", s.daemonID, clientID, proof); err != nil {
		// Same message for a bad code and an unknown device: do not tell an
		// attacker which half they got right.
		return nil, reject(conn, "authentication failed")
	}
	if err := session.AsHost(); err != nil {
		return nil, err
	}

	if pairing {
		name := auth.DeviceName
		if name == "" {
			name = "Unnamed device"
		}
		// Re-pairing an already-known key keeps its device ID so existing
		// references stay valid.
		if existing, err := s.store.DeviceByPublicKey(clientID); err == nil {
			device = existing
			device.Name = name
		} else {
			device = &db.Device{ID: uuid.NewString(), Name: name}
		}
		if err := s.store.UpsertDevice(device.ID, name, clientID); err != nil {
			return nil, reject(conn, "could not store device")
		}
	}
	if err := s.store.TouchDevice(device.ID); err != nil {
		log.Printf("touch device %s: %v", device.ID, err)
	}

	// The host proves it too, so the client detects an impostor host.
	result := authResult{
		Type:     "authResult",
		OK:       true,
		DeviceID: device.ID,
		HostName: s.control.HostName(),
		Proof:    b64.EncodeToString(session.Proof("host", s.daemonID, clientID)),
	}
	conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	if err := conn.WriteJSON(result); err != nil {
		return nil, err
	}

	conn.SetReadDeadline(time.Time{})
	return &client{id: uuid.NewString(), deviceID: device.ID, conn: conn, session: session}, nil
}

// reject reports the failure to the client and returns it as an error.
func reject(conn *websocket.Conn, reason string) error {
	conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	conn.WriteJSON(authResult{Type: "authResult", OK: false, Error: reason})
	return errors.New(reason)
}

// readLoop services encrypted command frames until the socket closes.
func (s *Server) readLoop(c *client) {
	const pongWait = 90 * time.Second
	c.conn.SetReadLimit(1 << 20)
	c.conn.SetReadDeadline(time.Now().Add(pongWait))
	c.conn.SetPongHandler(func(string) error {
		return c.conn.SetReadDeadline(time.Now().Add(pongWait))
	})

	go c.keepalive()

	for {
		kind, frame, err := c.conn.ReadMessage()
		if err != nil {
			return
		}
		if kind != websocket.BinaryMessage {
			// Everything after the handshake must be encrypted.
			return
		}
		plaintext, err := c.session.Open(frame)
		if err != nil {
			log.Printf("device %q: dropping frame: %v", c.deviceID, err)
			return
		}
		var env protocol.Envelope
		if err := json.Unmarshal(plaintext, &env); err != nil {
			c.send(protocol.Errorf("", "", "malformed envelope"))
			continue
		}
		c.conn.SetReadDeadline(time.Now().Add(pongWait))
		s.dispatch(c, &env)
	}
}

// keepalive pings so dead phones (screen off, out of Wi-Fi range) are noticed
// rather than lingering as phantom connected devices.
func (c *client) keepalive() {
	ticker := time.NewTicker(30 * time.Second)
	defer ticker.Stop()
	for range ticker.C {
		c.writeMu.Lock()
		c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
		err := c.conn.WriteMessage(websocket.PingMessage, nil)
		c.writeMu.Unlock()
		if err != nil {
			c.close()
			return
		}
	}
}
