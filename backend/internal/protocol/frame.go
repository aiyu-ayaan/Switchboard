package protocol

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
)

// Frame kinds, carried as the first byte of every sealed plaintext.
const (
	// FrameJSON is an envelope on its own — every control message.
	FrameJSON = 0x00
	// FrameBlob is an envelope followed by raw bytes: a file chunk, a camera
	// frame, anything whose payload is bulk data rather than structure.
	FrameBlob = 0x01
)

// MaxFrameSize bounds one sealed frame. It has to clear the largest blob
// (ChunkSize) plus its envelope with room to spare, and it is the only thing
// standing between a hostile peer and an allocation the size of its
// imagination.
const MaxFrameSize = 4 << 20

var errShortFrame = errors.New("protocol: truncated frame")

// EncodeFrame renders an envelope, optionally carrying raw bytes alongside it.
//
// Bulk payloads travel *beside* the JSON rather than inside it because the
// transport is already binary: the sealed frame is written as a WebSocket
// binary message either way. Base64 inside the envelope would inflate every
// byte by a third and cost an encode on one CPU and a decode on the other, to
// end up in the same place.
func EncodeFrame(env *Envelope, blob []byte) ([]byte, error) {
	meta, err := json.Marshal(env)
	if err != nil {
		return nil, err
	}
	if len(blob) == 0 {
		return append([]byte{FrameJSON}, meta...), nil
	}

	frame := make([]byte, 0, 5+len(meta)+len(blob))
	frame = append(frame, FrameBlob)
	frame = binary.BigEndian.AppendUint32(frame, uint32(len(meta)))
	frame = append(frame, meta...)
	frame = append(frame, blob...)
	return frame, nil
}

// DecodeFrame splits a plaintext frame back into its envelope and its bytes.
// The returned blob aliases the input, which is fine for every current caller
// — the chunk is written to disk before the buffer is reused — and is what
// keeps a large transfer from copying every byte twice.
func DecodeFrame(frame []byte) (*Envelope, []byte, error) {
	if len(frame) == 0 {
		return nil, nil, errShortFrame
	}

	switch frame[0] {
	case FrameJSON:
		env := &Envelope{}
		if err := json.Unmarshal(frame[1:], env); err != nil {
			return nil, nil, err
		}
		return env, nil, nil

	case FrameBlob:
		if len(frame) < 5 {
			return nil, nil, errShortFrame
		}
		metaLen := int(binary.BigEndian.Uint32(frame[1:5]))
		// Checked against the frame rather than a constant: a length that
		// overruns the buffer is the one input that turns a slice into a panic.
		if metaLen < 0 || 5+metaLen > len(frame) {
			return nil, nil, errShortFrame
		}
		env := &Envelope{}
		if err := json.Unmarshal(frame[5:5+metaLen], env); err != nil {
			return nil, nil, err
		}
		return env, frame[5+metaLen:], nil
	}

	return nil, nil, fmt.Errorf("protocol: unknown frame kind 0x%02x", frame[0])
}
