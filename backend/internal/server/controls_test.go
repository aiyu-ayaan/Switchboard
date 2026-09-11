package server

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"switchboard/backend/internal/crypto"
	"switchboard/backend/internal/protocol"
)

func TestNewControlsOverTheWire(t *testing.T) {
	srv, ts := newHarness(t)

	identity, err := crypto.NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	client, err := dial(t, ts, identity, modePair, []byte(srv.PairingInfo().Code))
	if err != nil {
		t.Fatalf("pairing rejected: %v", err)
	}

	// 1. MicGet
	reply := client.call(t, protocol.ActionMicGet, nil)
	if reply.Type != protocol.TypeResponse && reply.Type != protocol.TypeError {
		t.Fatalf("audio.mic.get returned invalid type: %s", reply.Type)
	}

	// 2. InputList
	reply = client.call(t, protocol.ActionInputList, nil)
	if reply.Type != protocol.TypeResponse && reply.Type != protocol.TypeError {
		t.Fatalf("audio.input.list returned invalid type: %s", reply.Type)
	}

	// 3. InputText
	reply = client.call(t, protocol.ActionInputText, protocol.InputText{Text: ""})
	if reply.Type != protocol.TypeResponse {
		t.Fatalf("input.text returned %s: %s", reply.Type, reply.Payload)
	}

	// 4. ClipboardSet
	reply = client.call(t, protocol.ActionClipboardSet, protocol.ClipboardSet{Text: "switchboard test clipboard"})
	if reply.Type != protocol.TypeResponse {
		t.Fatalf("clipboard.set returned %s: %s", reply.Type, reply.Payload)
	}

	// 5. SystemPower invalid action should fail gracefully
	reply = client.call(t, protocol.ActionSystemPower, protocol.PowerCommand{Action: "invalid_action"})
	if reply.Type != protocol.TypeError {
		t.Fatalf("system.power with invalid action should return error, got %s: %s", reply.Type, reply.Payload)
	}
}

func TestNewControlsLocalAPI(t *testing.T) {
	srv, _ := newHarness(t)
	mux := http.NewServeMux()
	srv.registerLocalAPI(mux)

	postJSON := func(path string, payload any) *httptest.ResponseRecorder {
		body, _ := json.Marshal(payload)
		req := httptest.NewRequest("POST", path, bytes.NewReader(body))
		req.RemoteAddr = "127.0.0.1:54321"
		req.Header.Set("Content-Type", "application/json")
		rr := httptest.NewRecorder()
		mux.ServeHTTP(rr, req)
		return rr
	}

	// 1. POST /local/system/power with invalid action should return 400
	rr := postJSON("/local/system/power", protocol.PowerCommand{Action: "invalid_action"})
	if rr.Code != http.StatusBadRequest {
		t.Errorf("POST /local/system/power code = %d, want %d", rr.Code, http.StatusBadRequest)
	}

	// 2. POST /local/audio/mic
	rr = postJSON("/local/audio/mic", protocol.Volume{Level: 50, Muted: false})
	// May succeed or return 500 depending on microphone availability on machine
	if rr.Code != http.StatusOK && rr.Code != http.StatusInternalServerError {
		t.Errorf("POST /local/audio/mic code = %d", rr.Code)
	}

	// 3. POST /local/audio/input with empty id should return 400
	rr = postJSON("/local/audio/input", protocol.OutputSet{DeviceID: ""})
	if rr.Code != http.StatusBadRequest {
		t.Errorf("POST /local/audio/input code = %d, want %d", rr.Code, http.StatusBadRequest)
	}

	// 4. POST /local/input/text with empty string
	rr = postJSON("/local/input/text", protocol.InputText{Text: ""})
	if rr.Code != http.StatusOK {
		t.Errorf("POST /local/input/text code = %d, want %d", rr.Code, http.StatusOK)
	}

	// 5. POST /local/clipboard
	rr = postJSON("/local/clipboard", protocol.ClipboardSet{Text: "localapi test clipboard"})
	if rr.Code != http.StatusOK {
		t.Errorf("POST /local/clipboard code = %d, want %d", rr.Code, http.StatusOK)
	}
}
