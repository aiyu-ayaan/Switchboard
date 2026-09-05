//go:build windows

package system

import (
	"fmt"
	"runtime"
	"syscall"
	"time"
	"unsafe"

	"github.com/go-ole/go-ole"
	"github.com/moutend/go-wca/pkg/wca"
	"golang.org/x/sys/windows"

	"switchboard/backend/internal/protocol"
)

// Output routing: which endpoint the host plays through.
//
// Enumeration is plain Core Audio — IMMDeviceEnumerator over the active render
// endpoints. Changing the default is not: Windows exposes no public API for
// it, so the shell's own Sound page drives the undocumented IPolicyConfig,
// and every tool that moves the default endpoint (nircmd, EarTrumpet,
// SoundSwitch) calls the same interface. There is no supported alternative
// short of telling the user to open the Sound settings themselves.

// outputTTL bounds how stale a served endpoint list may be. Endpoints change
// only when hardware is plugged or unplugged, so this is far longer than the
// mixer's: the snapshot is reassembled every second and each rebuild would
// otherwise walk COM and open a property store per device.
const outputTTL = 5 * time.Second

var outputCache ttlCache[[]protocol.AudioDevice]

func outputsSupported() bool { return true }

func audioOutputs() ([]protocol.AudioDevice, error) {
	return outputCache.get(outputTTL, listRenderEndpoints)
}

// setAudioOutput moves the default render endpoint. All three roles move
// together: a user who picks "Headphones" in the app means their sound, not
// "their sound except in calls", and leaving communications behind is exactly
// the split that makes Windows' own Sound page confusing.
func setAudioOutput(deviceID string) ([]protocol.AudioDevice, error) {
	if deviceID == "" {
		return nil, fmt.Errorf("audio: output device id is required")
	}
	if err := withCOM(func() error { return setDefaultEndpoint(deviceID) }); err != nil {
		return nil, err
	}
	// The endpoint moved, so every cached read of it is stale: the session
	// list belongs to the old device and the cached endpoint list still marks
	// the old default.
	outputCache.invalidate()
	sessionCache.invalidate()
	return audioOutputs()
}

func listRenderEndpoints() ([]protocol.AudioDevice, error) {
	devices := []protocol.AudioDevice{}
	err := withDeviceEnumerator(func(enumerator *wca.IMMDeviceEnumerator) error {
		defaultID := defaultEndpointID(enumerator)

		var collection *wca.IMMDeviceCollection
		if err := enumerator.EnumAudioEndpoints(wca.ERender, wca.DEVICE_STATE_ACTIVE, &collection); err != nil {
			return fmt.Errorf("audio: enumerate endpoints: %w", err)
		}
		defer collection.Release()

		var count uint32
		if err := collection.GetCount(&count); err != nil {
			return fmt.Errorf("audio: endpoint count: %w", err)
		}
		for i := uint32(0); i < count; i++ {
			// Read one endpoint per call so its interfaces are released as the
			// walk moves on, as the mixer walk does.
			if device, ok := readEndpoint(collection, i, defaultID); ok {
				devices = append(devices, device)
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}
	return devices, nil
}

// defaultEndpointID is best-effort: a host with every endpoint disabled has no
// default, which is a list with nothing marked rather than an error.
func defaultEndpointID(enumerator *wca.IMMDeviceEnumerator) string {
	var device *wca.IMMDevice
	if err := enumerator.GetDefaultAudioEndpoint(wca.ERender, wca.EConsole, &device); err != nil {
		return ""
	}
	defer device.Release()
	var id string
	if err := device.GetId(&id); err != nil {
		return ""
	}
	return id
}

func readEndpoint(collection *wca.IMMDeviceCollection, index uint32, defaultID string) (protocol.AudioDevice, bool) {
	var device *wca.IMMDevice
	if err := collection.Item(index, &device); err != nil {
		return protocol.AudioDevice{}, false
	}
	defer device.Release()

	var id string
	if err := device.GetId(&id); err != nil || id == "" {
		return protocol.AudioDevice{}, false
	}

	name := endpointName(device)
	if name == "" {
		// A nameless row is a row the user cannot choose between, so the
		// endpoint is dropped rather than listed blank.
		return protocol.AudioDevice{}, false
	}

	return protocol.AudioDevice{ID: id, Name: name, Default: id == defaultID}, true
}

// endpointName reads the friendly name, which is the "Speakers (Realtek
// Audio)" form the Windows volume flyout shows — device description plus
// adapter, which is what distinguishes two identical-sounding sinks.
func endpointName(device *wca.IMMDevice) string {
	var props *wca.IPropertyStore
	if err := device.OpenPropertyStore(wca.STGM_READ, &props); err != nil {
		return ""
	}
	defer props.Release()

	var value wca.PROPVARIANT
	if err := props.GetValue(&wca.PKEY_Device_FriendlyName, &value); err != nil {
		return ""
	}
	return value.String()
}

// ---- IPolicyConfig ----

var (
	// CPolicyConfigClient, the shell's own implementation.
	clsidPolicyConfigClient = ole.NewGUID("{870AF99C-171D-4F9E-AF0D-E63DF40C2BC9}")
	// IPolicyConfig as shipped since Windows 7; still present on Windows 11.
	iidPolicyConfig = ole.NewGUID("{F8679F50-850A-41CF-9C72-430F290290C8}")
)

// setDefaultEndpointSlot is SetDefaultEndpoint's index in the IPolicyConfig
// vtable: three IUnknown entries, then ten format/period/share/property
// methods. The interface is undocumented, so the layout is pinned here rather
// than discovered.
//
// Verified against Windows 7, 8.1, 10 (1507-22H2) and 11 (21H2-24H2), where
// every shipped IPolicyConfig has held this layout. Nothing guarantees the
// next build will: TestSetDefaultEndpointSlotPinned fails if this constant is
// edited without also revisiting the comment, and setDefaultEndpoint checks
// the slot is populated before calling through it, so a Windows release that
// shortens the vtable returns an error instead of jumping into whatever
// follows the object in memory.
const setDefaultEndpointSlot = 13

// setDefaultEndpoint must run on a thread with an initialised apartment; every
// caller reaches it through withCOM.
func setDefaultEndpoint(deviceID string) error {
	unknown, err := ole.CreateInstance(clsidPolicyConfigClient, iidPolicyConfig)
	if err != nil {
		return fmt.Errorf("audio: policy config unavailable: %w", err)
	}
	defer unknown.Release()

	id, err := windows.UTF16PtrFromString(deviceID)
	if err != nil {
		return fmt.Errorf("audio: bad device id: %w", err)
	}

	// CreateInstance already did the QueryInterface for iidPolicyConfig, so a
	// build that dropped the interface fails above rather than here. What is
	// left to check is that the vtable actually reaches the pinned slot: a
	// nil table or an empty entry means the layout moved, and calling through
	// it would be an access violation rather than a failed HRESULT.
	vtable := *(**[setDefaultEndpointSlot + 1]uintptr)(unsafe.Pointer(unknown))
	if vtable == nil || vtable[setDefaultEndpointSlot] == 0 {
		return fmt.Errorf("audio: IPolicyConfig vtable slot %d is empty; "+
			"the undocumented interface layout moved on this Windows build",
			setDefaultEndpointSlot)
	}
	// eConsole covers general playback, eMultimedia music and video, and
	// eCommunications voice chat. Windows treats them independently.
	for _, role := range []uintptr{wca.EConsole, wca.EMultimedia, wca.ECommunications} {
		hr, _, _ := syscall.SyscallN(vtable[setDefaultEndpointSlot],
			uintptr(unsafe.Pointer(unknown)), uintptr(unsafe.Pointer(id)), role)
		if hr != 0 {
			return fmt.Errorf("audio: set default endpoint: %w", ole.NewError(hr))
		}
	}
	return nil
}

// ---- COM plumbing ----

// withCOM runs fn inside a single-threaded apartment on a locked OS thread,
// the discipline every Core Audio call in this package follows.
func withCOM(fn func() error) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	if err := ole.CoInitializeEx(0, ole.COINIT_APARTMENTTHREADED); err != nil {
		return fmt.Errorf("audio: CoInitializeEx: %w", err)
	}
	defer ole.CoUninitialize()

	return fn()
}

func withDeviceEnumerator(fn func(*wca.IMMDeviceEnumerator) error) error {
	return withCOM(func() error {
		var enumerator *wca.IMMDeviceEnumerator
		if err := wca.CoCreateInstance(wca.CLSID_MMDeviceEnumerator, 0, wca.CLSCTX_ALL,
			wca.IID_IMMDeviceEnumerator, &enumerator); err != nil {
			return fmt.Errorf("audio: device enumerator: %w", err)
		}
		defer enumerator.Release()
		return fn(enumerator)
	})
}
