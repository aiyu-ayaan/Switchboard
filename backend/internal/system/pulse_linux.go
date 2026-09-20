//go:build linux

package system

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// PulseAudio/PipeWire transport for the Linux audio backend.
//
// Desktop Linux has no single audio API the way Windows has WASAPI. What it
// has instead is one protocol that everything speaks: PulseAudio's. PipeWire,
// which is now the default on Fedora, Ubuntu and their derivatives, ships
// pipewire-pulse for exactly this reason, and bare PulseAudio is what the rest
// of the field runs. Talking that protocol reaches both.
//
// It is spoken here through pactl rather than through the native protocol or a
// cgo binding to libpulse. The daemon is built with CGO_ENABLED=0 so it can be
// dropped onto a machine without a toolchain, which rules out libpulse; and
// reimplementing the native protocol -- a stateful, versioned, authenticated
// wire format -- to move a volume slider is a great deal of surface for very
// little that pactl does not already do. pactl is part of pulseaudio-utils,
// which both PulseAudio and pipewire-pulse depend on, so a host that can play
// sound at all has it.
//
// The cost of that choice is a process spawn per read, and a host.state
// broadcast reads master volume, the mixer, outputs, the microphone and inputs
// all at once. `pactl --format=json list` returns every one of those in a
// single call in around 6ms, so the snapshot below is fetched whole and held
// briefly, and one broadcast costs two spawns rather than ten.

// pulseTTL is how long a snapshot is reused.
//
// It is short on purpose. Anything the user changes through Switchboard
// invalidates the cache directly, so the TTL only has to cover changes made
// somewhere else -- the desktop's own mixer, a headset being unplugged -- and
// those reach the phone on the next broadcast either way. Making it longer
// would buy a spawn or two and cost the UI its accuracy during a slider drag,
// where reads and writes interleave.
const pulseTTL = 500 * time.Millisecond

// pulseTimeout bounds a pactl call. pactl blocks until the server answers, and
// a wedged PipeWire would otherwise hang the host.state broadcast -- and with
// it every other control -- rather than just losing the audio section.
const pulseTimeout = 2 * time.Second

// pulseFullVolume is PulseAudio's PA_VOLUME_NORM: the raw value meaning 100%.
// Levels above it are legal (PulseAudio allows software boost past unity) and
// are clamped, because the phone shows a 0-100 slider.
const pulseFullVolume = 65536

// pulseChannel is one channel's volume inside a `volume` object.
type pulseChannel struct {
	Value int `json:"value"`
}

// pulseVolume is the per-channel map pactl emits, keyed by channel position.
type pulseVolume map[string]pulseChannel

// percent reduces a multi-channel volume to the single 0-100 number the
// protocol carries, taking the loudest channel.
//
// The maximum rather than the mean is deliberate: a user who has panned all
// the way to one side still has audible sound, and reporting the average would
// show that as a half-volume host and then destroy the balance on the next
// write.
func (v pulseVolume) percent() int {
	loudest := 0
	for _, ch := range v {
		if ch.Value > loudest {
			loudest = ch.Value
		}
	}
	return clamp((loudest*100+pulseFullVolume/2)/pulseFullVolume, 0, 100)
}

// pulseSink is one output endpoint -- speakers, a headset, an HDMI sink.
type pulseSink struct {
	Index       int         `json:"index"`
	Name        string      `json:"name"`
	Description string      `json:"description"`
	Mute        bool        `json:"mute"`
	Volume      pulseVolume `json:"volume"`
}

// pulseSource is one capture endpoint. Every sink also has a companion
// "monitor" source for recording what is being played; MonitorOf is non-empty
// exactly for those, which is how they are kept out of the microphone list.
type pulseSource struct {
	Index       int         `json:"index"`
	Name        string      `json:"name"`
	Description string      `json:"description"`
	Mute        bool        `json:"mute"`
	Volume      pulseVolume `json:"volume"`
	MonitorOf   string      `json:"monitor_source"`
}

// pulseSinkInput is one application's playback stream: a mixer row.
//
// Corked means the stream is connected but not pulling audio -- a paused
// player. Those keep their row, mirroring the Windows mixer, which also holds
// an inactive session rather than letting a paused player vanish mid-use.
type pulseSinkInput struct {
	Index      int               `json:"index"`
	Mute       bool              `json:"mute"`
	Corked     bool              `json:"corked"`
	Volume     pulseVolume       `json:"volume"`
	Sink       int               `json:"sink"`
	Properties map[string]string `json:"properties"`
}

// pulseList is the shape of `pactl --format=json list`.
type pulseList struct {
	Sinks      []pulseSink      `json:"sinks"`
	Sources    []pulseSource    `json:"sources"`
	SinkInputs []pulseSinkInput `json:"sink_inputs"`
}

// pulseInfo is the shape of `pactl --format=json info`. The defaults are not
// part of the object list, so they cost the second call.
type pulseInfo struct {
	DefaultSink   string `json:"default_sink_name"`
	DefaultSource string `json:"default_source_name"`
}

// pulseSnapshot is one consistent read of the whole audio graph.
type pulseSnapshot struct {
	pulseList
	pulseInfo
}

// defaultSink returns the sink carrying host master volume, or nil when the
// machine has no output at all.
func (s *pulseSnapshot) defaultSink() *pulseSink {
	for i := range s.Sinks {
		if s.Sinks[i].Name == s.DefaultSink {
			return &s.Sinks[i]
		}
	}
	// A server can report a default that is gone, or none at all, between a
	// device being unplugged and the fallback settling. Any sink is a better
	// answer than "this host has no audio".
	if len(s.Sinks) > 0 {
		return &s.Sinks[0]
	}
	return nil
}

// defaultSource returns the capture endpoint the microphone controls act on,
// skipping monitors, which are not microphones and which a host always has.
func (s *pulseSnapshot) defaultSource() *pulseSource {
	for i := range s.Sources {
		if s.Sources[i].Name == s.DefaultSource && s.Sources[i].MonitorOf == "" {
			return &s.Sources[i]
		}
	}
	for i := range s.Sources {
		if s.Sources[i].MonitorOf == "" {
			return &s.Sources[i]
		}
	}
	return nil
}

var (
	pulseMu       sync.Mutex
	pulseCached   *pulseSnapshot
	pulseCachedAt time.Time

	// pulseProbeOnce guards the one-time check for pactl itself, so a host
	// without pulseaudio-utils does not pay a failed exec lookup per read.
	pulseProbeOnce sync.Once
	pulsePath      string
)

// errNoPulse reports a host with no PulseAudio-compatible server reachable.
var errNoPulse = errors.New("system: no PulseAudio or PipeWire server available")

// pulseBinary locates pactl once per daemon.
func pulseBinary() string {
	pulseProbeOnce.Do(func() {
		if path, err := exec.LookPath("pactl"); err == nil {
			pulsePath = path
		}
	})
	return pulsePath
}

// pactl runs one pactl subcommand and returns its stdout.
func pactl(args ...string) (string, error) {
	bin := pulseBinary()
	if bin == "" {
		return "", errNoPulse
	}
	ctx, cancel := context.WithTimeout(context.Background(), pulseTimeout)
	defer cancel()

	out, err := exec.CommandContext(ctx, bin, args...).Output()
	if err != nil {
		var exit *exec.ExitError
		if errors.As(err, &exit) && len(exit.Stderr) > 0 {
			return "", fmt.Errorf("system: pactl %s: %s", args[0], strings.TrimSpace(string(exit.Stderr)))
		}
		return "", fmt.Errorf("system: pactl %s: %w", args[0], err)
	}
	return string(out), nil
}

// pactlJSON runs a pactl subcommand in JSON mode and decodes it into v.
func pactlJSON(v any, args ...string) error {
	out, err := pactl(append([]string{"--format=json"}, args...)...)
	if err != nil {
		return err
	}
	if err := json.Unmarshal([]byte(out), v); err != nil {
		return fmt.Errorf("system: pactl %s: %w", args[0], err)
	}
	return nil
}

// pulseState returns the current audio graph, reusing a recent read.
func pulseState() (*pulseSnapshot, error) {
	pulseMu.Lock()
	defer pulseMu.Unlock()
	return pulseStateLocked()
}

func pulseStateLocked() (*pulseSnapshot, error) {
	if pulseCached != nil && time.Since(pulseCachedAt) < pulseTTL {
		return pulseCached, nil
	}
	snap := &pulseSnapshot{}
	if err := pactlJSON(&snap.pulseList, "list"); err != nil {
		return nil, err
	}
	// Losing only the defaults is survivable: defaultSink and defaultSource
	// both fall back to the first candidate, which on a single-output machine
	// -- most of them -- is the right one anyway.
	_ = pactlJSON(&snap.pulseInfo, "info")

	pulseCached = snap
	pulseCachedAt = time.Now()
	return snap, nil
}

// pulseInvalidate drops the cached snapshot so the next read reflects a write
// that just happened rather than the state from before it.
func pulseInvalidate() {
	pulseMu.Lock()
	pulseCached = nil
	pulseMu.Unlock()
}

// pulseSupported reports whether the host has a reachable audio server.
//
// It is answered from a real query rather than from the presence of the
// binary: pactl is installed on plenty of machines that have no server running
// -- a headless box, a session started without one -- and claiming the
// capability there would put a dead slider on the phone.
func pulseSupported() bool {
	_, err := pulseState()
	return err == nil
}

// pulseWrite applies a change and invalidates the snapshot, whether or not the
// call succeeded: a partially applied write leaves the cache wrong too.
func pulseWrite(args ...string) error {
	_, err := pactl(args...)
	pulseInvalidate()
	return err
}
