//go:build linux

package system

import (
	"errors"
	"fmt"
	"os"
	"time"

	"golang.org/x/sys/unix"
)

// DDC/CI over the kernel's i2c-dev character devices.
//
// This is the same conversation dxva2.dll has on Windows, one layer lower:
// there is no GetMonitorBrightness here, only the VCP codes underneath it
// (0x10 brightness, 0x12 contrast, 0xD6 power) carried over the display's I2C
// bus. Every graphics driver exposes that bus as /dev/i2c-N and points at it
// from the connector's sysfs directory, so the panel a bus belongs to is never
// guessed at -- see connectorBuses in display_linux.go.
//
// The framing is MCCS/DDC-CI:
//
//	host -> display   0x51, 0x80|len, data..., checksum
//	display -> host   0x6E, 0x80|len, data..., checksum
//
// The checksum is an XOR over the whole packet seeded with the *other* end's
// 8-bit address, which is the part that is easy to get wrong and the reason
// framing is unit-tested rather than only tried against hardware.

const (
	// From linux/i2c-dev.h. golang.org/x/sys/unix does not define these.
	i2cSlave      = 0x0703
	i2cSlaveForce = 0x0706

	// The DDC/CI port every MCCS display answers on.
	ddcAddress = 0x37

	ddcHostSource    = 0x51 // what the host writes as its source address
	ddcDisplaySource = 0x6E // what the display writes as its source address

	// Checksum seeds: each end XORs in the 8-bit address of the other.
	ddcWriteSeed = ddcAddress << 1 // 0x6E
	ddcReadSeed  = 0x50

	vcpBrightness = 0x10
	vcpContrast   = 0x12
	vcpPower      = 0xD6

	// The MCCS spec's own floors. A display is entitled to ignore anything
	// faster, and several answer with garbage rather than an error when rushed.
	ddcReplyDelay = 50 * time.Millisecond
	ddcWriteDelay = 60 * time.Millisecond

	ddcMaxData = 32
)

// errNoDDC reports a bus that is there but has no MCCS display behind it --
// an audio or tuner I2C channel, or a connector with nothing plugged in. It is
// an expected outcome of probing, not a failure worth reporting to the user.
var errNoDDC = errors.New("ddc: no display on this bus")

// ddcBus is one open /dev/i2c-N addressed at the DDC/CI port.
//
// Held open for the lifetime of the panel for the same reason the Windows
// backend holds its physical-monitor handles: a brightness drag is many writes
// a second, and re-opening per write would add a syscall pair to each one.
type ddcBus struct {
	fd   int
	path string

	// The display's own minimum gap between packets, enforced here because
	// nothing else in the stack knows about it. A slider dragged quickly would
	// otherwise overrun the panel and start collecting checksum errors.
	nextWrite time.Time
}

func openDDCBus(path string) (*ddcBus, error) {
	fd, err := unix.Open(path, unix.O_RDWR|unix.O_CLOEXEC, 0)
	if err != nil {
		return nil, fmt.Errorf("open %s: %w", path, err)
	}
	// I2C_SLAVE fails with EBUSY when a kernel driver has claimed the address.
	// Nothing in-tree claims 0x37, but an i2c-dev bus shared with a tuner or a
	// sensor can report it anyway; FORCE is what ddcutil falls back on and is
	// safe for a port no driver wants.
	if err := unix.IoctlSetInt(fd, i2cSlave, ddcAddress); err != nil {
		if err := unix.IoctlSetInt(fd, i2cSlaveForce, ddcAddress); err != nil {
			unix.Close(fd)
			return nil, fmt.Errorf("address %s at 0x%02x: %w", path, ddcAddress, err)
		}
	}
	return &ddcBus{fd: fd, path: path}, nil
}

func (b *ddcBus) Close() {
	if b.fd >= 0 {
		unix.Close(b.fd)
		b.fd = -1
	}
}

// send writes one DDC/CI packet, waiting out whatever the display is still owed
// from the previous one.
func (b *ddcBus) send(data []byte) error {
	if len(data) == 0 || len(data) > ddcMaxData {
		return fmt.Errorf("ddc: payload of %d bytes is out of range", len(data))
	}
	if wait := time.Until(b.nextWrite); wait > 0 {
		time.Sleep(wait)
	}
	frame := ddcFrame(data)
	if _, err := unix.Write(b.fd, frame); err != nil {
		b.nextWrite = time.Now().Add(ddcWriteDelay)
		return fmt.Errorf("ddc: write to %s: %w", b.path, err)
	}
	b.nextWrite = time.Now().Add(ddcWriteDelay)
	return nil
}

// get reads one VCP feature: its current value and the maximum the panel
// accepts for it. A display that does not implement the code answers with a
// non-zero result byte, which comes back as errNoDDC's sibling -- an error, but
// one callers treat as "this panel has no such control".
func (b *ddcBus) get(code byte) (current, max int, err error) {
	if err := b.send([]byte{0x01, code}); err != nil {
		return 0, 0, err
	}
	// The spec's minimum turnaround. Reading sooner returns the tail of the
	// request on several panels rather than a reply.
	time.Sleep(ddcReplyDelay)

	// 0x6E, length, 8 data bytes, checksum.
	buf := make([]byte, 11)
	n, err := unix.Read(b.fd, buf)
	if err != nil {
		return 0, 0, fmt.Errorf("ddc: read from %s: %w", b.path, err)
	}
	data, err := ddcPayload(buf[:n])
	if err != nil {
		return 0, 0, err
	}
	return parseVCPReply(data, code)
}

// set writes a VCP feature. DDC/CI has no acknowledgement for a write, so a
// caller that needs to know it landed has to read the code back.
func (b *ddcBus) set(code byte, value int) error {
	if value < 0 {
		value = 0
	}
	if value > 0xFFFF {
		value = 0xFFFF
	}
	return b.send([]byte{0x03, code, byte(value >> 8), byte(value)})
}

// ddcFrame wraps a payload in the host's source address, length and checksum.
func ddcFrame(data []byte) []byte {
	frame := make([]byte, 0, len(data)+3)
	frame = append(frame, ddcHostSource, byte(0x80|len(data)))
	frame = append(frame, data...)

	sum := byte(ddcWriteSeed)
	for _, b := range frame {
		sum ^= b
	}
	return append(frame, sum)
}

// ddcPayload validates a reply frame and returns just its data bytes.
func ddcPayload(frame []byte) ([]byte, error) {
	// A bus with nothing on it reads back as all-zero or all-ones rather than
	// failing, so the source address is the first thing that has to hold.
	if len(frame) < 3 || frame[0] != ddcDisplaySource {
		return nil, errNoDDC
	}
	length := int(frame[1] &^ 0x80)
	if frame[1]&0x80 == 0 || length == 0 || 2+length >= len(frame) {
		return nil, fmt.Errorf("ddc: reply declares %d bytes, frame is %d", length, len(frame))
	}

	sum := byte(ddcReadSeed)
	for _, b := range frame[:2+length] {
		sum ^= b
	}
	if sum != frame[2+length] {
		return nil, fmt.Errorf("ddc: checksum mismatch (got 0x%02x, want 0x%02x)", frame[2+length], sum)
	}
	return frame[2 : 2+length], nil
}

// parseVCPReply reads the eight-byte body of a "VCP feature reply".
func parseVCPReply(data []byte, code byte) (current, max int, err error) {
	if len(data) < 8 || data[0] != 0x02 {
		return 0, 0, fmt.Errorf("ddc: not a VCP reply")
	}
	// 0x01 is "unsupported VCP code" -- the panel answered, it just has no such
	// control. Every other non-zero value is a real fault on the panel's side.
	if data[1] != 0x00 {
		return 0, 0, fmt.Errorf("ddc: display rejected VCP 0x%02x (result 0x%02x)", code, data[1])
	}
	if data[2] != code {
		return 0, 0, fmt.Errorf("ddc: reply is for VCP 0x%02x, asked for 0x%02x", data[2], code)
	}
	max = int(data[4])<<8 | int(data[5])
	current = int(data[6])<<8 | int(data[7])
	return current, max, nil
}

// i2cPermissionHint turns the EACCES every unconfigured machine hits into the
// one sentence that fixes it. /dev/i2c-* is root-only out of the box on most
// distributions, which is why an external monitor can be plugged in, working
// and still invisible here.
func i2cPermissionHint(err error) bool {
	return errors.Is(err, os.ErrPermission) || errors.Is(err, unix.EACCES)
}
