//go:build linux

package system

import (
	"bytes"
	"errors"
	"testing"
)

// DDC/CI framing is the one part of the Linux display backend that cannot be
// exercised on a machine without an external monitor -- and getting the
// checksum seed wrong does not fail loudly, it just makes every panel look
// like it does not speak the protocol. So the wire bytes are pinned here
// against vectors computed from the spec rather than from this code.
//
// `51 82 01 10 AC` in particular is the get-brightness request as it is
// documented outside this repository, which is what makes it worth asserting:
// if the host source address, the length flag or the 0x6E checksum seed were
// wrong, this is the test that would say so.

func TestDDCFrameMatchesSpecVectors(t *testing.T) {
	tests := []struct {
		name string
		data []byte
		want []byte
	}{
		{
			name: "get brightness",
			data: []byte{0x01, vcpBrightness},
			want: []byte{0x51, 0x82, 0x01, 0x10, 0xAC},
		},
		{
			name: "set brightness to 50",
			data: []byte{0x03, vcpBrightness, 0x00, 0x32},
			want: []byte{0x51, 0x84, 0x03, 0x10, 0x00, 0x32, 0x9A},
		},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			if got := ddcFrame(tc.data); !bytes.Equal(got, tc.want) {
				t.Errorf("ddcFrame(% X) = % X, want % X", tc.data, got, tc.want)
			}
		})
	}
}

func TestDDCPayloadReadsAReply(t *testing.T) {
	// VCP 0x10, current 50, maximum 100.
	frame := []byte{0x6E, 0x88, 0x02, 0x00, 0x10, 0x00, 0x00, 0x64, 0x00, 0x32, 0xF2}

	data, err := ddcPayload(frame)
	if err != nil {
		t.Fatalf("ddcPayload: %v", err)
	}

	current, max, err := parseVCPReply(data, vcpBrightness)
	if err != nil {
		t.Fatalf("parseVCPReply: %v", err)
	}
	if current != 50 || max != 100 {
		t.Errorf("got current=%d max=%d, want 50 and 100", current, max)
	}
}

// A panel's range is its own: assuming 0-100 is exactly the bug that makes a
// slider cover a third of the real travel on a monitor whose maximum is 255.
func TestDDCPayloadReadsATwoByteRange(t *testing.T) {
	frame := ddcTestReply(t, []byte{0x02, 0x00, 0x10, 0x00, 0x01, 0x00, 0x00, 0x80})

	data, err := ddcPayload(frame)
	if err != nil {
		t.Fatalf("ddcPayload: %v", err)
	}
	current, max, err := parseVCPReply(data, vcpBrightness)
	if err != nil {
		t.Fatalf("parseVCPReply: %v", err)
	}
	if current != 128 || max != 256 {
		t.Errorf("got current=%d max=%d, want 128 and 256", current, max)
	}
}

func TestDDCPayloadRejectsBadFrames(t *testing.T) {
	good := []byte{0x6E, 0x88, 0x02, 0x00, 0x10, 0x00, 0x00, 0x64, 0x00, 0x32, 0xF2}

	t.Run("corrupted byte fails the checksum", func(t *testing.T) {
		bad := bytes.Clone(good)
		bad[7] ^= 0xFF
		if _, err := ddcPayload(bad); err == nil {
			t.Fatal("a frame with a flipped data byte was accepted")
		}
	})

	// An i2c bus with nothing listening reads back as all-zero or all-ones
	// rather than failing, and both have to come back as "no display here"
	// instead of being parsed as a reply.
	t.Run("idle bus", func(t *testing.T) {
		for _, fill := range []byte{0x00, 0xFF} {
			quiet := bytes.Repeat([]byte{fill}, 11)
			if _, err := ddcPayload(quiet); !errors.Is(err, errNoDDC) {
				t.Errorf("fill 0x%02X: got %v, want errNoDDC", fill, err)
			}
		}
	})

	t.Run("truncated", func(t *testing.T) {
		if _, err := ddcPayload(good[:2]); err == nil {
			t.Fatal("a two-byte frame was accepted")
		}
	})

	t.Run("length longer than the frame", func(t *testing.T) {
		bad := bytes.Clone(good)
		bad[1] = 0x80 | 0x1F
		if _, err := ddcPayload(bad); err == nil {
			t.Fatal("a frame claiming more bytes than it has was accepted")
		}
	})
}

func TestParseVCPReplyRejectsMismatches(t *testing.T) {
	t.Run("unsupported code", func(t *testing.T) {
		// Result byte 0x01: the panel answered, it just has no such control.
		data := []byte{0x02, 0x01, 0x12, 0x00, 0x00, 0x00, 0x00, 0x00}
		if _, _, err := parseVCPReply(data, vcpContrast); err == nil {
			t.Fatal("a rejected VCP code was reported as a value")
		}
	})

	// Reading one code's value as another's is how a contrast reply becomes a
	// brightness reading, which would be silently wrong rather than an error.
	t.Run("reply for another code", func(t *testing.T) {
		data := []byte{0x02, 0x00, 0x12, 0x00, 0x00, 0x64, 0x00, 0x32}
		if _, _, err := parseVCPReply(data, vcpBrightness); err == nil {
			t.Fatal("a reply for VCP 0x12 was accepted as one for 0x10")
		}
	})

	t.Run("short body", func(t *testing.T) {
		if _, _, err := parseVCPReply([]byte{0x02, 0x00, 0x10}, vcpBrightness); err == nil {
			t.Fatal("a truncated reply body was accepted")
		}
	})
}

// ddcTestReply builds a display-to-host frame. It deliberately spells the
// checksum out rather than calling into the code under test, so a wrong seed
// here could not cancel out a wrong seed there.
func ddcTestReply(t *testing.T, data []byte) []byte {
	t.Helper()
	frame := append([]byte{0x6E, byte(0x80 | len(data))}, data...)
	sum := byte(0x50)
	for _, b := range frame {
		sum ^= b
	}
	return append(frame, sum)
}
