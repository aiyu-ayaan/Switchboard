//go:build windows

package system

import (
	"fmt"
	"runtime"

	"github.com/go-ole/go-ole"
	"github.com/moutend/go-wca/pkg/wca"

	"switchboard/backend/internal/protocol"
)

// Master volume goes through the Core Audio IAudioEndpointVolume interface on
// the default render endpoint, which is the same control the Windows volume
// flyout drives.
//
// COM is apartment-threaded: every call locks the goroutine to its OS thread
// and tears the apartment down again, so the daemon's other goroutines are
// never bound to an initialised apartment they did not ask for.
func withEndpointVolume(fn func(*wca.IAudioEndpointVolume) error) error {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	if err := ole.CoInitializeEx(0, ole.COINIT_APARTMENTTHREADED); err != nil {
		return fmt.Errorf("audio: CoInitializeEx: %w", err)
	}
	defer ole.CoUninitialize()

	var enumerator *wca.IMMDeviceEnumerator
	if err := wca.CoCreateInstance(wca.CLSID_MMDeviceEnumerator, 0, wca.CLSCTX_ALL,
		wca.IID_IMMDeviceEnumerator, &enumerator); err != nil {
		return fmt.Errorf("audio: device enumerator: %w", err)
	}
	defer enumerator.Release()

	var device *wca.IMMDevice
	if err := enumerator.GetDefaultAudioEndpoint(wca.ERender, wca.EConsole, &device); err != nil {
		return fmt.Errorf("audio: no default output device: %w", err)
	}
	defer device.Release()

	var volume *wca.IAudioEndpointVolume
	if err := device.Activate(wca.IID_IAudioEndpointVolume, wca.CLSCTX_ALL, nil, &volume); err != nil {
		return fmt.Errorf("audio: activate endpoint volume: %w", err)
	}
	defer volume.Release()

	return fn(volume)
}

func getVolume() (protocol.Volume, error) {
	var v protocol.Volume
	err := withEndpointVolume(func(vol *wca.IAudioEndpointVolume) error {
		var scalar float32
		if err := vol.GetMasterVolumeLevelScalar(&scalar); err != nil {
			return err
		}
		var muted bool
		if err := vol.GetMute(&muted); err != nil {
			return err
		}
		v = protocol.Volume{Level: int(scalar*100 + 0.5), Muted: muted}
		return nil
	})
	return v, err
}

func setVolume(level int, muted bool) (protocol.Volume, error) {
	level = clamp(level, 0, 100)
	err := withEndpointVolume(func(vol *wca.IAudioEndpointVolume) error {
		if err := vol.SetMasterVolumeLevelScalar(float32(level)/100, nil); err != nil {
			return err
		}
		return vol.SetMute(muted, nil)
	})
	if err != nil {
		return protocol.Volume{}, err
	}
	return protocol.Volume{Level: level, Muted: muted}, nil
}
