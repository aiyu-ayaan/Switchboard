package server

import (
	"encoding/json"
	"errors"
	"net"
	"net/http"

	"switchboard/backend/internal/db"
	"switchboard/backend/internal/protocol"
)

// The local API is what the Electron renderer talks to. It carries no
// pairing credentials, so it is bound to the loopback interface: a request
// arriving from the LAN is refused even though the daemon listens on all
// interfaces for mobile clients.
func (s *Server) registerLocalAPI(mux *http.ServeMux) {
	handle := func(pattern string, fn http.HandlerFunc) {
		mux.Handle(pattern, loopbackOnly(fn))
	}

	handle("GET /local/state", s.localState)
	handle("POST /local/displays/refresh", s.localRefreshDisplays)
	handle("POST /local/display/brightness", s.localSetBrightness)
	handle("POST /local/display/contrast", s.localSetContrast)
	handle("POST /local/volume", s.localSetVolume)
	handle("POST /local/media", s.localMedia)
	handle("POST /local/pairing/rotate", s.localRotatePairing)
	handle("POST /local/devices/revoke", s.localRevokeDevice)
}

// loopbackOnly rejects any request that did not originate on this machine.
func loopbackOnly(next http.HandlerFunc) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		host, _, err := net.SplitHostPort(r.RemoteAddr)
		if err != nil {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		if ip := net.ParseIP(host); ip == nil || !ip.IsLoopback() {
			http.Error(w, "local API is loopback only", http.StatusForbidden)
			return
		}
		next(w, r)
	})
}

// localStateResponse is everything the desktop UI renders in one poll.
type localStateResponse struct {
	Host    protocol.HostState `json:"host"`
	Pairing PairingInfo        `json:"pairing"`
	Devices []db.Device        `json:"devices"`
}

func (s *Server) localState(w http.ResponseWriter, r *http.Request) {
	devices, err := s.Devices()
	if err != nil {
		httpError(w, err, http.StatusInternalServerError)
		return
	}
	writeJSON(w, localStateResponse{
		Host:    s.control.State(s.daemonID),
		Pairing: s.PairingInfo(),
		Devices: devices,
	})
}

func (s *Server) localRefreshDisplays(w http.ResponseWriter, r *http.Request) {
	if err := s.control.RefreshDisplays(); err != nil {
		httpError(w, err, http.StatusInternalServerError)
		return
	}
	displays, err := s.control.Displays()
	if err != nil {
		httpError(w, err, http.StatusInternalServerError)
		return
	}
	s.Broadcast()
	writeJSON(w, displays)
}

func (s *Server) localSetBrightness(w http.ResponseWriter, r *http.Request) {
	s.localDisplaySet(w, r, s.control.SetBrightness)
}

func (s *Server) localSetContrast(w http.ResponseWriter, r *http.Request) {
	s.localDisplaySet(w, r, s.control.SetContrast)
}

func (s *Server) localDisplaySet(w http.ResponseWriter, r *http.Request,
	set func(string, int) (protocol.Display, error)) {
	var req protocol.DisplaySet
	if err := decode(r, &req); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	display, err := set(req.DisplayID, req.Value)
	if err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	s.Broadcast()
	writeJSON(w, display)
}

func (s *Server) localSetVolume(w http.ResponseWriter, r *http.Request) {
	var req protocol.Volume
	if err := decode(r, &req); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	volume, err := s.control.SetVolume(req.Level, req.Muted)
	if err != nil {
		httpError(w, err, http.StatusInternalServerError)
		return
	}
	s.Broadcast()
	writeJSON(w, volume)
}

func (s *Server) localMedia(w http.ResponseWriter, r *http.Request) {
	var req protocol.MediaCommand
	if err := decode(r, &req); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	if err := s.control.Media(req.Action); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	writeJSON(w, map[string]string{"action": req.Action})
}

func (s *Server) localRotatePairing(w http.ResponseWriter, r *http.Request) {
	info, err := s.RotatePairing()
	if err != nil {
		httpError(w, err, http.StatusInternalServerError)
		return
	}
	writeJSON(w, info)
}

func (s *Server) localRevokeDevice(w http.ResponseWriter, r *http.Request) {
	var req struct {
		DeviceID string `json:"deviceId"`
	}
	if err := decode(r, &req); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	switch err := s.RevokeDevice(req.DeviceID); {
	case errors.Is(err, db.ErrNotFound):
		httpError(w, err, http.StatusNotFound)
	case err != nil:
		httpError(w, err, http.StatusInternalServerError)
	default:
		writeJSON(w, map[string]bool{"revoked": true})
	}
}

func decode(r *http.Request, v any) error {
	defer r.Body.Close()
	return json.NewDecoder(http.MaxBytesReader(nil, r.Body, 1<<16)).Decode(v)
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}

func httpError(w http.ResponseWriter, err error, status int) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
}
