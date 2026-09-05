package server

import (
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"strconv"
	"time"

	"switchboard/backend/internal/camera"
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
	handle("POST /local/audio/output", s.localSetOutput)
	handle("POST /local/media", s.localMedia)
	handle("GET /local/media/artwork", s.localMediaArtwork)
	handle("POST /local/pairing/rotate", s.localRotatePairing)
	handle("POST /local/devices/revoke", s.localRevokeDevice)
	handle("POST /local/files/send", s.localSendFiles)
	handle("POST /local/files/control", s.localFileControl)
	handle("GET /local/files/history", s.localFileHistory)
	handle("GET /local/settings", s.localGetSettings)
	handle("POST /local/settings", s.localUpdateSettings)
	handle("POST /local/settings/download-dir", s.localCheckDownloadDir)
	handle("POST /local/system/lock", s.localLock)
	handle("GET /local/unlock/status", s.localUnlockStatus)
	handle("POST /local/unlock/setup", s.localUnlockSetup)
	handle("POST /local/unlock/disable", s.localUnlockDisable)

	handle("GET /local/camera/state", s.localCameraState)
	handle("GET /local/camera/vcam/status", s.localCameraVCamStatus)
	handle("POST /local/camera/start", s.localCameraStart)
	handle("POST /local/camera/stop", s.localCameraStop)
	handle("POST /local/camera/control", s.localCameraControl)
	handle("GET /local/camera/frame", s.localCameraFrame)
	handle("GET /local/camera/stream", s.localCameraStream)
	handle("GET /local/camera/video", s.localCameraVideo)
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

func (s *Server) localSetOutput(w http.ResponseWriter, r *http.Request) {
	var req protocol.OutputSet
	if err := decode(r, &req); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	outputs, err := s.control.SetOutput(req.DeviceID)
	if err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	s.Broadcast()
	writeJSON(w, outputs)
}

func (s *Server) localLock(w http.ResponseWriter, r *http.Request) {
	if err := s.control.Lock(); err != nil {
		httpError(w, err, http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]string{"status": "locked"})
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

func (s *Server) localMediaArtwork(w http.ResponseWriter, r *http.Request) {
	artwork, err := s.control.MediaArtwork()
	if err != nil {
		httpError(w, err, http.StatusInternalServerError)
		return
	}
	writeJSON(w, artwork)
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

// localFileHistory is the transfer list on its own, so the desktop can poll it
// several times a second while a file is moving without dragging the whole
// /state payload — displays, audio sessions and a DDC/CI probe — along with it.
func (s *Server) localFileHistory(w http.ResponseWriter, r *http.Request) {
	history, err := s.localTransferHistory()
	if err != nil {
		httpError(w, err, http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]any{"transfers": history})
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

// ---- Wi-Fi camera ----

func (s *Server) localCameraState(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, s.camera.State())
}

func (s *Server) localCameraVCamStatus(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, map[string]any{
		"installed":  s.camera.VCamInstalled(),
		"deviceName": "Switchboard Camera",
	})
}

func (s *Server) localCameraStart(w http.ResponseWriter, r *http.Request) {
	var req struct {
		DeviceID string                   `json:"deviceId"`
		Settings *protocol.CameraSettings `json:"settings"`
	}
	if err := decode(r, &req); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	// Starting without a settings block means "as it comes": full quality,
	// everything automatic, which is what the feature specifies as default.
	settings := protocol.DefaultCameraSettings()
	if req.Settings != nil {
		settings = *req.Settings
	}
	if err := s.camera.Start(req.DeviceID, settings); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	writeJSON(w, s.camera.State())
}

func (s *Server) localCameraStop(w http.ResponseWriter, r *http.Request) {
	if err := s.camera.Stop(); err != nil {
		httpError(w, err, http.StatusInternalServerError)
		return
	}
	writeJSON(w, s.camera.State())
}

func (s *Server) localCameraControl(w http.ResponseWriter, r *http.Request) {
	var settings protocol.CameraSettings
	if err := decode(r, &settings); err != nil {
		httpError(w, err, http.StatusBadRequest)
		return
	}
	if err := s.camera.Control(settings); err != nil {
		httpError(w, err, http.StatusConflict)
		return
	}
	writeJSON(w, s.camera.State())
}

// localCameraFrame hands over the next frame after the sequence the caller
// already has, blocking until one exists.
//
// The desktop renderer holds a strict content policy and makes no network
// requests of its own, so frames reach it through the Electron main process.
// Long-polling rather than a timer means the UI is never a frame behind and
// never asks for one that has not changed.
func (s *Server) localCameraFrame(w http.ResponseWriter, r *http.Request) {
	after, _ := strconv.ParseInt(r.URL.Query().Get("after"), 10, 64)

	frame, seq, err := s.camera.Await(after, 10*time.Second)
	switch {
	case errors.Is(err, camera.ErrNotStreaming):
		httpError(w, err, http.StatusConflict)
		return
	case errors.Is(err, camera.ErrNoNewFrame):
		// Not a failure: the camera is live but pointed at something still.
		// 204 lets the caller loop without treating it as an error.
		w.Header().Set("X-Sequence", strconv.FormatInt(seq, 10))
		w.WriteHeader(http.StatusNoContent)
		return
	case err != nil:
		httpError(w, err, http.StatusInternalServerError)
		return
	}

	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Sequence", strconv.FormatInt(seq, 10))
	w.Write(frame)
}

// localCameraStream is the MJPEG endpoint OBS, VLC and any browser can open
// directly. It is what makes the phone usable in applications Switchboard
// knows nothing about.
func (s *Server) localCameraStream(w http.ResponseWriter, r *http.Request) {
	s.camera.ServeMJPEG(w, r)
}

// localCameraVideo is the H.264 track, and the one the desktop's own live view
// reads. Its frames come off the phone's hardware encoder rather than a
// software JPEG per picture, which is what lets the preview run at the rate
// the user asked for instead of whatever a CPU could compress.
func (s *Server) localCameraVideo(w http.ResponseWriter, r *http.Request) {
	s.camera.ServeVideo(w, r)
}
