package protocol

import (
	"bytes"
	"encoding/binary"
	"testing"
)

func TestFrameRoundTripsAnEnvelopeAlone(t *testing.T) {
	env, err := New(TypeCommand, ActionPing, map[string]string{"hello": "world"})
	if err != nil {
		t.Fatal(err)
	}
	frame, err := EncodeFrame(env, nil)
	if err != nil {
		t.Fatal(err)
	}

	got, blob, err := DecodeFrame(frame)
	if err != nil {
		t.Fatal(err)
	}
	if got.ID != env.ID || got.Action != env.Action {
		t.Fatalf("envelope changed in transit: %+v", got)
	}
	if blob != nil {
		t.Fatalf("a control frame carried %d bytes of blob", len(blob))
	}
}

func TestFrameRoundTripsBinaryPayload(t *testing.T) {
	// Every byte value, so a codec that mangles high bytes or treats the
	// payload as text is caught rather than passing on ASCII alone.
	payload := make([]byte, 256)
	for i := range payload {
		payload[i] = byte(i)
	}

	env, err := New(TypeCommand, ActionFileChunk, FileChunk{TransferID: "t1", Offset: 4096})
	if err != nil {
		t.Fatal(err)
	}
	frame, err := EncodeFrame(env, payload)
	if err != nil {
		t.Fatal(err)
	}

	got, blob, err := DecodeFrame(frame)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(blob, payload) {
		t.Fatal("the blob did not survive the round trip")
	}

	var chunk FileChunk
	if err := got.Decode(&chunk); err != nil {
		t.Fatal(err)
	}
	if chunk.TransferID != "t1" || chunk.Offset != 4096 {
		t.Fatalf("chunk metadata changed in transit: %+v", chunk)
	}
}

// The whole point of moving bytes out of the JSON: base64 inside the envelope
// costs a third of the wire for nothing, since the frame is binary anyway.
func TestBinaryPayloadCostsNoExpansion(t *testing.T) {
	payload := make([]byte, 64*1024)
	env, _ := New(TypeCommand, ActionFileChunk, FileChunk{TransferID: "t1"})

	frame, err := EncodeFrame(env, payload)
	if err != nil {
		t.Fatal(err)
	}
	overhead := len(frame) - len(payload)
	if overhead > 256 {
		t.Fatalf("a 64 KiB chunk carried %d bytes of overhead", overhead)
	}
}

// A length field read from a peer is the one input that turns a slice into a
// panic, so it is checked against the frame rather than against a constant.
func TestTruncatedAndHostileFramesAreRejected(t *testing.T) {
	cases := map[string][]byte{
		"empty":            {},
		"blob header only": {FrameBlob, 0, 0},
		"unknown kind":     {0x7f, 'x'},
	}
	for name, frame := range cases {
		if _, _, err := DecodeFrame(frame); err == nil {
			t.Fatalf("%s was accepted", name)
		}
	}

	// A metadata length that runs off the end of the buffer.
	hostile := []byte{FrameBlob}
	hostile = binary.BigEndian.AppendUint32(hostile, 0xFFFFFF)
	hostile = append(hostile, []byte(`{"id":"x"}`)...)
	if _, _, err := DecodeFrame(hostile); err == nil {
		t.Fatal("an overrunning metadata length was accepted")
	}
}
