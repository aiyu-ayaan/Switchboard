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
	handle("POST /local/mixer", s.localSetSessionVolume)
	handle("POST /local/media", s.localMedia)
	handle("POST /local/pairing/rotate", s.localRotatePairing)
	handle("POST /local/devices/revoke", s.localRevokeDevice)
	handle("POST /local/files/send", s.localSendFiles)
	handle("POST /local/files/control", s.localFileControl)
	handle("GET /local/files/history", s.localFileHistory)
	handle("GET /local/settings", s.localGetSettings)
	handle("POST /local/settings", s.localUpdateSettings)
	handle("POST /local/settings/download-dir", s.localCheckDownloadDir)
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
	Host      protocol.HostState `json:"host"`
	Pairing   PairingInfo        `json:"pairing"`
	Devices   []db.Device        `json:"devices"`
	Transfers []LocalTransfer    `json:"transfers"`
	Settings  Settings           `json:"settings"`
}

func (s *Server) localState(w http.ResponseWriter, r *http.Request) {
	devices, err := s.Devices()
	if err != nil {
		httpError(w, err, http.StatusInternalServerError)
		return
	}
	transfers, err := s.localTransferHistory()
	if err != nil {
		httpError(w, err, http.StatusInternalServerError)
		return
	}
	writeJSON(w, localStateResponse{
		Host:      s.hostState(),
		Pairing:   s.PairingInfo(),
		Devices:   devices,
		Transfers: transfers,
		Settings:  s.Settings(),
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

func (s *Server) localSetSessionVolume(w http.ResponseWriter, r *http.Request) {
	var req protocol.MixerSet
	if err := decode(r, &req); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	sessions, err := s.control.SetSessionVolume(req.SessionID, req.Level, req.Muted)
	if err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	s.Broadcast()
	writeJSON(w, sessions)
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

// localSendFiles starts a desktop -> phone transfer for each path. Each file
// gets its own transfer so one unreadable path does not sink the batch, and
// the response reports per-path outcomes rather than a single status code.
func (s *Server) localSendFiles(w http.ResponseWriter, r *http.Request) {
	var req struct {
		DeviceID string   `json:"deviceId"`
		Paths    []string `json:"paths"`
	}
	if err := decode(r, &req); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	if req.DeviceID == "" || len(req.Paths) == 0 {
		httpError(w, errors.New("deviceId and paths are required"), http.StatusBadRequest)
		return
	}

	type result struct {
		Path       string `json:"path"`
		TransferID string `json:"transferId,omitempty"`
		Error      string `json:"error,omitempty"`
	}
	results := make([]result, 0, len(req.Paths))
	for _, path := range req.Paths {
		id, err := s.transfers.Send(req.DeviceID, path)
		if err != nil {
			results = append(results, result{Path: path, Error: err.Error()})
			continue
		}
		results = append(results, result{Path: path, TransferID: id})
	}
	writeJSON(w, map[string]any{"transfers": results})
}

func (s *Server) localFileControl(w http.ResponseWriter, r *http.Request) {
	var req protocol.FileControl
	if err := decode(r, &req); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	if err := s.transfers.ControlLocal(req.TransferID, req.Action); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	writeJSON(w, map[string]string{"transferId": req.TransferID, "action": req.Action})
}

func (s *Server) localFileHistory(w http.ResponseWriter, r *http.Request) {
	history, err := s.transferHistory()
	if err != nil {
		httpError(w, err, http.StatusInternalServerError)
		return
	}
	writeJSON(w, protocol.FileHistory{Transfers: history})
}

func (s *Server) localGetSettings(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, s.Settings())
}

// localUpdateSettings takes a whole Settings object. Sending the full set
// keeps the UI from having to know which fields it is allowed to omit, and
// makes the validation below cover every write.
func (s *Server) localUpdateSettings(w http.ResponseWriter, r *http.Request) {
	var req Settings
	if err := decode(r, &req); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	settings, err := s.UpdateSettings(req)
	if err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	writeJSON(w, settings)
}

// localCheckDownloadDir vets a folder the user picked in Electron's native
// dialog before it is committed, so an unwritable choice is rejected while the
// dialog is still fresh in mind rather than at the next transfer.
func (s *Server) localCheckDownloadDir(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Path string `json:"path"`
	}
	if err := decode(r, &req); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	if err := ValidateDownloadDir(req.Path); err != nil {
		writeJSON(w, map[string]any{"ok": false, "path": req.Path, "error": err.Error()})
		return
	}
	writeJSON(w, map[string]any{"ok": true, "path": req.Path})
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
