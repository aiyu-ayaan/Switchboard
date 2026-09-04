// Package server hosts the Switchboard daemon: an encrypted WebSocket
// endpoint for mobile clients and a loopback-only HTTP API for the Electron
// desktop UI.
package server

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/gorilla/websocket"

	"switchboard/backend/internal/config"
	"switchboard/backend/internal/crypto"
	"switchboard/backend/internal/db"
	"switchboard/backend/internal/protocol"
	"switchboard/backend/internal/system"
	"switchboard/backend/internal/transfer"
)

// pairingWindow is how long a displayed QR code stays valid. Short enough that
// a screenshot left on a desk is not a standing invitation.
const pairingWindow = 5 * time.Minute

// Server is the Switchboard communication daemon.
type Server struct {
	cfg      *config.Config
	store    *db.Database
	control  *system.Controller
	identity *crypto.Identity
	daemonID string

	http      *http.Server
	transfers *transfer.Manager

	mu       sync.RWMutex
	pairing  pairingToken
	settings Settings
	clients  map[string]*client // by connection ID
}

type pairingToken struct {
	code    []byte
	expires time.Time
}

// New builds a Server, loading or creating the persistent host identity.
func New(cfg *config.Config, store *db.Database, control *system.Controller) (*Server, error) {
	daemonID, seed, err := store.HostIdentity()
	switch {
	case errors.Is(err, db.ErrNotFound):
		identity, err := crypto.NewIdentity()
		if err != nil {
			return nil, err
		}
		daemonID = uuid.NewString()
		if err := store.SaveHostIdentity(daemonID, identity.Seed()); err != nil {
			return nil, err
		}
		return newServer(cfg, store, control, identity, daemonID)
	case err != nil:
		return nil, err
	}

	identity, err := crypto.IdentityFromSeed(seed)
	if err != nil {
		return nil, fmt.Errorf("server: stored host identity is corrupt: %w", err)
	}
	return newServer(cfg, store, control, identity, daemonID)
}

func newServer(cfg *config.Config, store *db.Database, control *system.Controller,
	identity *crypto.Identity, daemonID string) (*Server, error) {
	settings, err := loadSettings(store)
	if err != nil {
		return nil, err
	}
	s := &Server{
		cfg:      cfg,
		store:    store,
		control:  control,
		identity: identity,
		daemonID: daemonID,
		settings: settings,
		clients:  map[string]*client{},
	}
	s.transfers = transfer.NewManager(s.downloadDir, s.sendToDevice, s.onTransferEvent)
	if _, err := s.RotatePairing(); err != nil {
		return nil, err
	}

	mux := http.NewServeMux()
	mux.HandleFunc("/ws", s.handleWebSocket)
	s.registerLocalAPI(mux)

	s.http = &http.Server{
		Addr:              fmt.Sprintf(":%d", cfg.Port),
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	return s, nil
}

// DaemonID is the stable identifier shown in QR codes and stored by clients.
func (s *Server) DaemonID() string { return s.daemonID }

// ListenAndServe blocks serving the daemon until the context is cancelled.
func (s *Server) ListenAndServe(ctx context.Context) error {
	go func() {
		<-ctx.Done()
		shutdown, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		s.http.Shutdown(shutdown)
	}()

	log.Printf("switchboard daemon %s listening on %s", s.daemonID, s.http.Addr)
	if err := s.http.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
		return err
	}
	return nil
}

// ---- Pairing ----

// RotatePairing issues a fresh pairing code and returns the QR payload.
func (s *Server) RotatePairing() (PairingInfo, error) {
	code, err := newPairingCode()
	if err != nil {
		return PairingInfo{}, err
	}
	s.mu.Lock()
	s.pairing = pairingToken{code: code, expires: time.Now().Add(pairingWindow)}
	s.mu.Unlock()
	return s.PairingInfo(), nil
}

// pairingAlphabet is Crockford base32 without the characters that are read
// wrong when copied off a screen: I, L, O and U.
const pairingAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// pairingCodeLength gives 10 x 5 = 50 bits of entropy, which is far beyond
// guessable inside the five-minute window and still short enough to type.
const pairingCodeLength = 10

// newPairingCode returns the shared secret shown on screen.
//
// The same string is embedded in the QR code and typed during manual entry, and
// it is mixed verbatim into the key schedule, so scanning and typing produce
// exactly the same session. There is no second, weaker code.
func newPairingCode() ([]byte, error) {
	raw, err := crypto.RandomBytes(pairingCodeLength)
	if err != nil {
		return nil, err
	}
	code := make([]byte, pairingCodeLength)
	for i, b := range raw {
		code[i] = pairingAlphabet[int(b)%len(pairingAlphabet)]
	}
	return code, nil
}

// PairingInfo describes the currently displayed pairing offer.
type PairingInfo struct {
	DaemonID  string `json:"daemonId"`
	HostName  string `json:"hostName"`
	Host      string `json:"host"`
	Port      int    `json:"port"`
	HostKey   string `json:"hostKey"`
	Code      string `json:"code"`
	ExpiresAt int64  `json:"expiresAt"`
	QRPayload string `json:"qrPayload"`
}

// PairingInfo renders the current pairing offer, including the exact JSON the
// QR code should encode.
func (s *Server) PairingInfo() PairingInfo {
	s.mu.RLock()
	token := s.pairing
	s.mu.RUnlock()

	info := PairingInfo{
		DaemonID:  s.daemonID,
		HostName:  s.control.HostName(),
		Host:      localIP(),
		Port:      s.cfg.Port,
		HostKey:   s.identity.PublicKeyB64(),
		Code:      string(token.code),
		ExpiresAt: token.expires.UnixMilli(),
	}
	payload, _ := json.Marshal(map[string]any{
		"v":        1,
		"daemonId": info.DaemonID,
		"hostName": info.HostName,
		"host":     info.Host,
		"port":     info.Port,
		"hostKey":  info.HostKey,
		"code":     info.Code,
	})
	info.QRPayload = string(payload)
	return info
}

// currentPairingCode returns the code if it is still valid.
func (s *Server) currentPairingCode() ([]byte, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if time.Now().After(s.pairing.expires) {
		return nil, false
	}
	return s.pairing.code, true
}

// ---- Devices ----

// Devices lists paired devices, marking the ones currently connected.
func (s *Server) Devices() ([]db.Device, error) {
	devices, err := s.store.ListDevices()
	if err != nil {
		return nil, err
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	online := map[string]bool{}
	for _, c := range s.clients {
		online[c.deviceID] = true
	}
	for i := range devices {
		devices[i].Online = online[devices[i].ID]
	}
	return devices, nil
}

// RevokeDevice forgets a device and immediately drops any socket it holds, so
// revocation takes effect on a live connection rather than at next handshake.
func (s *Server) RevokeDevice(deviceID string) error {
	if err := s.store.RevokeDevice(deviceID); err != nil {
		return err
	}
	s.mu.Lock()
	var doomed []*client
	for _, c := range s.clients {
		if c.deviceID == deviceID {
			doomed = append(doomed, c)
		}
	}
	s.mu.Unlock()

	for _, c := range doomed {
		c.close()
	}
	return nil
}

func (s *Server) addClient(c *client) {
	s.mu.Lock()
	s.clients[c.id] = c
	s.mu.Unlock()
}

func (s *Server) removeClient(c *client) {
	s.mu.Lock()
	delete(s.clients, c.id)
	s.mu.Unlock()
}

// Broadcast pushes the current host state to every connected mobile client.
// Called after any change so a slider moved on one phone, or in the desktop
// UI, is reflected on the others.
func (s *Server) Broadcast() {
	env, err := protocol.New(protocol.TypeEvent, protocol.ActionHostState, s.hostState())
	if err != nil {
		log.Printf("broadcast: %v", err)
		return
	}
	s.mu.RLock()
	clients := make([]*client, 0, len(s.clients))
	for _, c := range s.clients {
		clients = append(clients, c)
	}
	s.mu.RUnlock()

	for _, c := range clients {
		c.send(env)
	}
}

// hostState is the snapshot pushed to clients.
//
// The files capability is appended here rather than inside system.Controller:
// transfers are a service the daemon itself provides, not an OS control the
// controller can probe a machine for.
func (s *Server) hostState() protocol.HostState {
	state := s.control.State(s.daemonID)
	state.Capabilities = append(state.Capabilities, "files")
	return state
}

// sendToDevice delivers one frame to every socket a device holds. It is what
// the transfer manager writes through, so the engine never learns about
// connections, sessions, or the WebSocket at all.
func (s *Server) sendToDevice(deviceID, action string, payload any) error {
	env, err := protocol.New(protocol.TypeCommand, action, payload)
	if err != nil {
		return err
	}
	targets := s.clientsFor(deviceID)
	if len(targets) == 0 {
		return fmt.Errorf("server: device %s is not connected", deviceID)
	}
	for _, c := range targets {
		c.send(env)
	}
	return nil
}

// clientsFor returns every live socket a device holds. A phone that
// reconnected before the daemon noticed the old socket die briefly has two.
func (s *Server) clientsFor(deviceID string) []*client {
	s.mu.RLock()
	defer s.mu.RUnlock()
	targets := make([]*client, 0, 1)
	for _, c := range s.clients {
		if c.deviceID == deviceID {
			targets = append(targets, c)
		}
	}
	return targets
}

// onTransferEvent persists progress and mirrors it to the phone. The desktop
// UI reads the same rows back on its state poll, so both surfaces see one
// version of the truth rather than two counters drifting apart.
func (s *Server) onTransferEvent(e transfer.Event) {
	if err := s.store.SaveTransfer(db.Transfer{
		ID: e.TransferID, DeviceID: e.DeviceID, Name: e.Name, Size: e.Size,
		Direction: e.Direction, Status: e.Status, Path: e.Path, SHA256: e.SHA256,
		Transferred: e.Transferred, StartedAt: e.StartedAt,
		FinishedAt: e.FinishedAt, Error: e.Error,
	}); err != nil {
		log.Printf("transfer %s: save: %v", e.TransferID, err)
	}
	if env, err := protocol.New(protocol.TypeEvent, protocol.ActionFileProgress, e.FileProgress); err == nil {
		for _, c := range s.clientsFor(e.DeviceID) {
			c.send(env)
		}
	}
}

// transferHistory renders stored rows as the progress shape both UIs already
// know, so history and live transfers are one list rather than two schemas.
func (s *Server) transferHistory() ([]protocol.FileProgress, error) {
	rows, err := s.store.ListTransfers(50)
	if err != nil {
		return nil, err
	}
	history := make([]protocol.FileProgress, 0, len(rows))
	for _, r := range rows {
		history = append(history, transferProgress(r))
	}
	return history, nil
}

// LocalTransfer is a history row as the desktop UI sees it: the wire progress
// plus the two things only this machine may know.
//
// Path and the owning device stay out of protocol.FileProgress deliberately.
// A phone has no use for a host filesystem path and every reason not to be
// told one, so the path reaches the loopback API — which is what has to open
// the containing folder — and stops there.
type LocalTransfer struct {
	protocol.FileProgress
	DeviceID   string `json:"deviceId,omitempty"`
	DeviceName string `json:"deviceName,omitempty"`
	Path       string `json:"path,omitempty"`
}

// localTransferHistory is transferHistory for the desktop UI, naming each
// transfer's device so the list reads as "from Pixel 8" rather than a UUID.
func (s *Server) localTransferHistory() ([]LocalTransfer, error) {
	rows, err := s.store.ListTransfers(50)
	if err != nil {
		return nil, err
	}
	names := map[string]string{}
	if devices, err := s.store.ListDevices(); err == nil {
		for _, d := range devices {
			names[d.ID] = d.Name
		}
	}
	history := make([]LocalTransfer, 0, len(rows))
	for _, r := range rows {
		history = append(history, LocalTransfer{
			FileProgress: transferProgress(r),
			DeviceID:     r.DeviceID,
			DeviceName:   names[r.DeviceID],
			Path:         r.Path,
		})
	}
	return history, nil
}

func transferProgress(r db.Transfer) protocol.FileProgress {
	return protocol.FileProgress{
		TransferID: r.ID, Name: r.Name, Direction: r.Direction, Status: r.Status,
		Transferred: r.Transferred, Size: r.Size,
		Error: r.Error, StartedAt: r.StartedAt, FinishedAt: r.FinishedAt,
	}
}

// mediaPollInterval is how often the daemon re-reads the OS media session.
//
// Windows raises change events for this session, but consuming them means
// handing WinRT a Go callback that fires on a thread pool thread. A one second
// poll is well inside what reads as instant on a phone, costs a single cheap
// call, and keeps the delivery path the same as every other state change.
const mediaPollInterval = time.Second

// WatchMedia broadcasts whenever what the host is playing changes, so a phone
// tracks play, pause and track changes driven from the desktop rather than
// only the ones it caused itself. It blocks until the context is cancelled.
func (s *Server) WatchMedia(ctx context.Context) {
	ticker := time.NewTicker(mediaPollInterval)
	defer ticker.Stop()

	var last protocol.MediaState
	var lastErr string

	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			// Nothing is listening, so skip the OS call entirely. The next
			// client to connect is sent a fresh snapshot on connect anyway.
			if s.clientCount() == 0 {
				continue
			}
			current, err := s.control.MediaState()
			if err != nil {
				// A wedged media stack would otherwise log once a second.
				if msg := err.Error(); msg != lastErr {
					lastErr = msg
					log.Printf("media watch: %v", err)
				}
				continue
			}
			lastErr = ""
			if current != last {
				last = current
				s.Broadcast()
			}
		}
	}
}

func (s *Server) clientCount() int {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return len(s.clients)
}

// localIP finds the LAN address a phone on the same network can reach. It
// dials an off-machine address without sending anything, which makes the OS
// pick the interface it would actually route through.
func localIP() string {
	conn, err := net.Dial("udp", "192.0.2.1:9") // TEST-NET-1, never routed
	if err != nil {
		return "127.0.0.1"
	}
	defer conn.Close()
	if addr, ok := conn.LocalAddr().(*net.UDPAddr); ok {
		return addr.IP.String()
	}
	return "127.0.0.1"
}

// upgrader accepts any origin: the transport is authenticated and encrypted
// inside the socket, so browser origin has no bearing on trust here.
var upgrader = websocket.Upgrader{
	ReadBufferSize:  4096,
	WriteBufferSize: 4096,
	CheckOrigin:     func(*http.Request) bool { return true },
}

// Handler exposes the daemon's HTTP routes for tests and for embedding.
func (s *Server) Handler() http.Handler { return s.http.Handler }
