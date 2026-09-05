//go:build windows

package system

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"
	"unicode"

	"github.com/go-ole/go-ole"
	"github.com/moutend/go-wca/pkg/wca"
	"golang.org/x/sys/windows"

	"switchboard/backend/internal/protocol"
)

// mixerTTL bounds how stale a served session list may be. A full COM walk
// activates a session manager and queries two interfaces per running program,
// and the host snapshot is rebuilt at least once a second.
const mixerTTL = time.Second

var sessionCache ttlCache[[]protocol.AudioSession]

func mixerSupported() bool { return true }

func mixerSessions() ([]protocol.AudioSession, error) {
	return sessionCache.get(mixerTTL, func() ([]protocol.AudioSession, error) {
		return walkSessions("", 0, false)
	})
}

func setSessionVolume(id string, level int, muted bool) ([]protocol.AudioSession, error) {
	if id == "" {
		return nil, fmt.Errorf("mixer: session id is required")
	}
	sessions, err := walkSessions(id, clamp(level, 0, 100), muted)
	if err != nil {
		return nil, err
	}
	// The walk already reflects the write, so seed the cache with it instead
	// of forcing the next snapshot to enumerate COM again.
	sessionCache.store(sessions)
	for _, s := range sessions {
		if s.ID == id {
			return sessions, nil
		}
	}
	return nil, fmt.Errorf("mixer: audio session %q not found", id)
}

// walkSessions enumerates the mixer once, applying level/muted to every
// session sharing target along the way. Read and write share one pass because
// a browser spans several sessions under a single identifier: they must all
// move together, and the caller needs the resulting state anyway.
func walkSessions(target string, level int, muted bool) ([]protocol.AudioSession, error) {
	sessions := []protocol.AudioSession{}
	// Several processes report the same identifier; the first one seen owns
	// the row, and later ones only promote it to active.
	seen := map[string]int{}
	self := os.Getpid()

	err := withSessionEnumerator(func(list *wca.IAudioSessionEnumerator) error {
		var count int
		if err := list.GetCount(&count); err != nil {
			return fmt.Errorf("mixer: session count: %w", err)
		}
		for i := 0; i < count; i++ {
			// Each session is read in its own call so its interfaces are
			// Released as the walk moves on, rather than every session's
			// interfaces staying alive until the whole walk finishes.
			s, ok := readSession(list, i, target, level, muted, self)
			if !ok {
				continue
			}
			if at, dup := seen[s.ID]; dup {
				sessions[at].Active = sessions[at].Active || s.Active
				continue
			}
			seen[s.ID] = len(sessions)
			sessions = append(sessions, s)
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return sessions, nil
}

func readSession(list *wca.IAudioSessionEnumerator, index int,
	target string, level int, muted bool, self int) (session protocol.AudioSession, ok bool) {

	var base *wca.IAudioSessionControl
	if err := list.GetSession(index, &base); err != nil {
		return session, false
	}
	defer base.Release()

	var ctl *wca.IAudioSessionControl2
	if err := base.PutQueryInterface(wca.IID_IAudioSessionControl2, &ctl); err != nil {
		return session, false
	}
	defer ctl.Release()

	var volume *wca.ISimpleAudioVolume
	if err := base.PutQueryInterface(wca.IID_ISimpleAudioVolume, &volume); err != nil {
		return session, false
	}
	defer volume.Release()

	var id string
	if err := ctl.GetSessionIdentifier(&id); err != nil || id == "" {
		return session, false
	}
	var pid uint32
	ctl.GetProcessId(&pid)
	// The daemon's own stream would be a mixer row the user can mute to no
	// visible effect, so it never appears.
	if pid != 0 && int(pid) == self {
		return session, false
	}

	if id == target {
		volume.SetMasterVolume(float32(level)/100, nil)
		volume.SetMute(muted, nil)
	}

	var scalar float32
	if err := volume.GetMasterVolume(&scalar); err != nil {
		return session, false
	}
	var isMuted bool
	volume.GetMute(&isMuted)
	var state uint32
	ctl.GetState(&state)

	return protocol.AudioSession{
		ID:     id,
		Name:   sessionName(ctl, pid),
		PID:    int(pid),
		Level:  int(scalar*100 + 0.5),
		Muted:  isMuted,
		Active: state == wca.AudioSessionStateActive,
	}, true
}

// sessionName resolves the label the mixer row carries. GetDisplayName is
// authoritative but is empty for most programs — only apps that deliberately
// set it (and the shell's own sessions) populate it — so the executable behind
// the PID is the real source of names, and every remaining case still gets a
// label rather than a blank row.
func sessionName(ctl *wca.IAudioSessionControl2, pid uint32) string {
	var display string
	if err := ctl.GetDisplayName(&display); err == nil {
		if name := strings.TrimSpace(display); name != "" && !strings.HasPrefix(name, "@") {
			// A leading "@" marks an unexpanded resource reference such as
			// "@%SystemRoot%\System32\...,-800", which is not a name.
			return name
		}
	}
	// IsSystemSoundsSession signals membership through S_OK vs S_FALSE, which
	// go-wca surfaces as nil vs a non-nil error.
	if ctl.IsSystemSoundsSession() == nil {
		return "System sounds"
	}
	if name := processDisplayName(pid); name != "" {
		return name
	}
	return "Unknown application"
}

func processDisplayName(pid uint32) string {
	if pid == 0 {
		return ""
	}
	// QUERY_LIMITED_INFORMATION is the least privilege that still yields an
	// image path, and unlike QUERY_INFORMATION it opens processes running at a
	// higher integrity level than the daemon.
	h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return ""
	}
	defer windows.CloseHandle(h)

	buf := make([]uint16, windows.MAX_PATH)
	size := uint32(len(buf))
	if err := windows.QueryFullProcessImageName(h, 0, &buf[0], &size); err != nil {
		return ""
	}
	return tidyExeName(filepath.Base(windows.UTF16ToString(buf[:size])))
}

// tidyExeName turns "chrome.exe" into "Chrome" so the mixer reads like the
// Windows volume mixer rather than like a process list.
func tidyExeName(exe string) string {
	name := exe
	if strings.EqualFold(filepath.Ext(name), ".exe") {
		name = name[:len(name)-len(".exe")]
	}
	if name == "" {
		return ""
	}
	r := []rune(name)
	r[0] = unicode.ToUpper(r[0])
	return string(r)
}

// withSessionEnumerator mirrors withEndpointVolume: the same apartment
// discipline, on the same default render endpoint, but reaching the
// per-application session manager instead of the endpoint's master volume.
func withSessionEnumerator(fn func(*wca.IAudioSessionEnumerator) error) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	if err := ole.CoInitializeEx(0, ole.COINIT_APARTMENTTHREADED); err != nil {
		return fmt.Errorf("mixer: CoInitializeEx: %w", err)
	}
	defer ole.CoUninitialize()

	var enumerator *wca.IMMDeviceEnumerator
	if err := wca.CoCreateInstance(wca.CLSID_MMDeviceEnumerator, 0, wca.CLSCTX_ALL,
		wca.IID_IMMDeviceEnumerator, &enumerator); err != nil {
		return fmt.Errorf("mixer: device enumerator: %w", err)
	}
	defer enumerator.Release()

	var device *wca.IMMDevice
	if err := enumerator.GetDefaultAudioEndpoint(wca.ERender, wca.EConsole, &device); err != nil {
		return fmt.Errorf("mixer: no default output device: %w", err)
	}
	defer device.Release()

	var manager *wca.IAudioSessionManager2
	if err := device.Activate(wca.IID_IAudioSessionManager2, wca.CLSCTX_ALL, nil, &manager); err != nil {
		return fmt.Errorf("mixer: activate session manager: %w", err)
	}
	defer manager.Release()

	var list *wca.IAudioSessionEnumerator
	if err := manager.GetSessionEnumerator(&list); err != nil {
		return fmt.Errorf("mixer: session enumerator: %w", err)
	}
	defer list.Release()

	return fn(list)
}
