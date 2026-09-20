//go:build linux

package system

import (
	"fmt"
	"sort"
	"strconv"

	"switchboard/backend/internal/protocol"
)

// Linux audio control, the counterpart to audio_windows.go, mixer_windows.go,
// endpoints_windows.go and mic_windows.go. The transport is in pulse_linux.go.
//
// The shape of the control surface is the same as on Windows because the two
// audio servers model the same things: a master level on the endpoint that is
// currently the default, a level per playing application, a list of endpoints
// to route to, and the same again for capture.
//
// Two details differ, both from PulseAudio rather than from choice:
//
//   - Endpoint IDs are sink names ("alsa_output.pci-0000_00_1f.3.analog-stereo")
//     rather than indices. Names survive a reboot and a re-plug; indices are
//     handed out fresh every time the server sees the device, so a phone that
//     remembered one would silently address a different speaker after a
//     suspend.
//   - Mixer rows are keyed by sink-input index, because that is the only thing
//     PulseAudio lets you address a stream by. Those are not stable, which is
//     why a write re-reads the mixer and returns the whole list instead of
//     trusting the caller's copy.

// levelArg formats a 0-100 level as the percentage pactl expects.
func levelArg(level int) string {
	return strconv.Itoa(clamp(level, 0, 100)) + "%"
}

// muteArg formats a mute state as the 0/1 pactl expects. "toggle" is also
// legal there and is never used: the phone sends the state it wants, so a
// dropped frame cannot leave the two ends disagreeing about which way a
// toggle went.
func muteArg(muted bool) string {
	if muted {
		return "1"
	}
	return "0"
}

func getVolume() (protocol.Volume, error) {
	snap, err := pulseState()
	if err != nil {
		return protocol.Volume{}, err
	}
	sink := snap.defaultSink()
	if sink == nil {
		return protocol.Volume{}, ErrUnsupported
	}
	return protocol.Volume{Level: sink.Volume.percent(), Muted: sink.Mute}, nil
}

// setVolume writes the master level and mute together.
//
// Both are always written, even when only one moved, because the caller sends
// the state it wants rather than a delta and the two calls are one spawn each
// against a server that answers in single-digit milliseconds. The alternative
// -- diffing against the cached snapshot -- would skip a write whenever the
// cache was stale, which is exactly when it matters.
func setVolume(level int, muted bool) (protocol.Volume, error) {
	if _, err := pulseState(); err != nil {
		return protocol.Volume{}, err
	}
	if err := pulseWrite("set-sink-volume", "@DEFAULT_SINK@", levelArg(level)); err != nil {
		return protocol.Volume{}, err
	}
	if err := pulseWrite("set-sink-mute", "@DEFAULT_SINK@", muteArg(muted)); err != nil {
		return protocol.Volume{}, err
	}
	return getVolume()
}

func mixerSupported() bool { return pulseSupported() }

// sessionName picks the label for a mixer row.
//
// PulseAudio clients are inconsistent about which property they set, so this
// walks them in order of how much the user would recognise the answer:
// application.name is what a well-behaved client sets ("Firefox"), media.name
// is usually the track or stream, and the binary name is the last resort that
// at least never comes back empty for a real process.
func sessionName(props map[string]string) string {
	for _, key := range []string{
		"application.name",
		"media.name",
		"application.process.binary",
		"node.name",
	} {
		if v := props[key]; v != "" {
			return v
		}
	}
	return "Unknown application"
}

func mixerSessions() ([]protocol.AudioSession, error) {
	snap, err := pulseState()
	if err != nil {
		return nil, err
	}

	// Every stream is listed, not only the ones on the default sink. Routing a
	// single application to a different endpoint is ordinary on Linux -- it is
	// a right-click in every desktop's mixer -- and hiding those rows would
	// make the one application a user deliberately moved the one they can no
	// longer turn down.
	sessions := make([]protocol.AudioSession, 0, len(snap.SinkInputs))
	for _, in := range snap.SinkInputs {
		pid, _ := strconv.Atoi(in.Properties["application.process.id"])
		sessions = append(sessions, protocol.AudioSession{
			ID:     strconv.Itoa(in.Index),
			Name:   sessionName(in.Properties),
			PID:    pid,
			Level:  in.Volume.percent(),
			Muted:  in.Mute,
			Active: !in.Corked,
		})
	}

	// Playing applications first, then by name, so the list does not reshuffle
	// under the user's finger every time the server hands out a new index.
	sort.SliceStable(sessions, func(i, j int) bool {
		if sessions[i].Active != sessions[j].Active {
			return sessions[i].Active
		}
		return sessions[i].Name < sessions[j].Name
	})
	return sessions, nil
}

func setSessionVolume(id string, level int, muted bool) ([]protocol.AudioSession, error) {
	if _, err := strconv.Atoi(id); err != nil {
		return nil, fmt.Errorf("system: invalid mixer session %q", id)
	}
	if err := pulseWrite("set-sink-input-volume", id, levelArg(level)); err != nil {
		return nil, err
	}
	if err := pulseWrite("set-sink-input-mute", id, muteArg(muted)); err != nil {
		return nil, err
	}
	return mixerSessions()
}

func outputsSupported() bool { return pulseSupported() }

func audioOutputs() ([]protocol.AudioDevice, error) {
	snap, err := pulseState()
	if err != nil {
		return nil, err
	}
	def := snap.defaultSink()
	devices := make([]protocol.AudioDevice, 0, len(snap.Sinks))
	for i := range snap.Sinks {
		sink := &snap.Sinks[i]
		name := sink.Description
		if name == "" {
			name = sink.Name
		}
		devices = append(devices, protocol.AudioDevice{
			ID:      sink.Name,
			Name:    name,
			Default: def != nil && def.Name == sink.Name,
		})
	}
	return devices, nil
}

// setAudioOutput moves the host to one endpoint.
//
// Setting the default only decides where streams that start *later* go;
// everything already playing stays where it is. That is PulseAudio working as
// designed and completely wrong as a remote control -- the user taps
// "Headphones", the server agrees, and the music keeps coming out of the
// speakers. Existing streams are moved as well so the change is audible, which
// is what the Windows endpoint switch does implicitly.
func setAudioOutput(id string) ([]protocol.AudioDevice, error) {
	snap, err := pulseState()
	if err != nil {
		return nil, err
	}
	found := false
	for i := range snap.Sinks {
		if snap.Sinks[i].Name == id {
			found = true
			break
		}
	}
	if !found {
		return nil, fmt.Errorf("system: unknown audio output %q", id)
	}

	if err := pulseWrite("set-default-sink", id); err != nil {
		return nil, err
	}
	// A stream can refuse to move -- one pinned to a device by a desktop rule,
	// or one that ended between the snapshot and now. That is not a reason to
	// report the endpoint switch as failed, since the default did change.
	for _, in := range snap.SinkInputs {
		_ = pulseWrite("move-sink-input", strconv.Itoa(in.Index), id)
	}
	return audioOutputs()
}

func micSupported() bool { return pulseSupported() }

func getMicVolume() (protocol.Volume, error) {
	snap, err := pulseState()
	if err != nil {
		return protocol.Volume{}, err
	}
	source := snap.defaultSource()
	if source == nil {
		return protocol.Volume{}, ErrUnsupported
	}
	return protocol.Volume{Level: source.Volume.percent(), Muted: source.Mute}, nil
}

func setMicVolume(level int, muted bool) (protocol.Volume, error) {
	if _, err := pulseState(); err != nil {
		return protocol.Volume{}, err
	}
	if err := pulseWrite("set-source-volume", "@DEFAULT_SOURCE@", levelArg(level)); err != nil {
		return protocol.Volume{}, err
	}
	if err := pulseWrite("set-source-mute", "@DEFAULT_SOURCE@", muteArg(muted)); err != nil {
		return protocol.Volume{}, err
	}
	return getMicVolume()
}

func inputsSupported() bool { return pulseSupported() }

// audioInputs lists capture endpoints, excluding monitors.
//
// Every sink has a companion monitor source for recording what is being
// played. They are sources as far as the server is concerned, but listing them
// would put "Monitor of Built-in Audio" next to the actual microphone on a
// phone screen, and selecting one would set the host's default recording
// device to its own loopback.
func audioInputs() ([]protocol.AudioDevice, error) {
	snap, err := pulseState()
	if err != nil {
		return nil, err
	}
	def := snap.defaultSource()
	devices := make([]protocol.AudioDevice, 0, len(snap.Sources))
	for i := range snap.Sources {
		source := &snap.Sources[i]
		if source.MonitorOf != "" {
			continue
		}
		name := source.Description
		if name == "" {
			name = source.Name
		}
		devices = append(devices, protocol.AudioDevice{
			ID:      source.Name,
			Name:    name,
			Default: def != nil && def.Name == source.Name,
		})
	}
	return devices, nil
}

func setAudioInput(id string) ([]protocol.AudioDevice, error) {
	snap, err := pulseState()
	if err != nil {
		return nil, err
	}
	found := false
	for i := range snap.Sources {
		if snap.Sources[i].Name == id && snap.Sources[i].MonitorOf == "" {
			found = true
			break
		}
	}
	if !found {
		return nil, fmt.Errorf("system: unknown audio input %q", id)
	}
	if err := pulseWrite("set-default-source", id); err != nil {
		return nil, err
	}
	// As with outputs: recording applications already running keep the source
	// they opened unless they are moved too.
	var sourceOutputs []struct {
		Index int `json:"index"`
	}
	if err := pactlJSON(&sourceOutputs, "list", "source-outputs"); err == nil {
		for _, out := range sourceOutputs {
			_ = pulseWrite("move-source-output", strconv.Itoa(out.Index), id)
		}
	}
	return audioInputs()
}
