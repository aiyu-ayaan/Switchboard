//go:build windows

package system

import (
	"fmt"
	"runtime"
	"strings"
	"time"

	"github.com/go-ole/go-ole"
	"github.com/moutend/go-wca/pkg/wca"

	"switchboard/backend/internal/protocol"
)

const inputTTL = 5 * time.Second

var inputCache ttlCache[[]protocol.AudioDevice]

func micSupported() bool    { return true }
func inputsSupported() bool { return true }

func withMicEndpointVolume(fn func(*wca.IAudioEndpointVolume) error) error {
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
	if err := enumerator.GetDefaultAudioEndpoint(wca.ECapture, wca.ECommunications, &device); err != nil {
		if errConsole := enumerator.GetDefaultAudioEndpoint(wca.ECapture, wca.EConsole, &device); errConsole != nil {
			return fmt.Errorf("audio: no default capture device: %w", err)
		}
	}
	defer device.Release()

	var volume *wca.IAudioEndpointVolume
	if err := device.Activate(wca.IID_IAudioEndpointVolume, wca.CLSCTX_ALL, nil, &volume); err != nil {
		return fmt.Errorf("audio: activate capture endpoint volume: %w", err)
	}
	defer volume.Release()

	return fn(volume)
}

func getMicVolume() (protocol.Volume, error) {
	var v protocol.Volume
	err := withMicEndpointVolume(func(vol *wca.IAudioEndpointVolume) error {
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

func setMicVolume(level int, muted bool) (protocol.Volume, error) {
	level = clamp(level, 0, 100)
	isMuted := muted
	err := withMicEndpointVolume(func(vol *wca.IAudioEndpointVolume) error {
		if err := vol.SetMasterVolumeLevelScalar(float32(level)/100, nil); err != nil {
			return fmt.Errorf("scalar: %w", err)
		}
		if err := vol.SetMute(muted, nil); err != nil {
			// Some capture endpoints do not support hardware/software mute control.
			// In that case, ignore ERROR_INVALID_FUNCTION (0x80070001 / "Incorrect function")
			// and preserve actual mute state.
			if strings.Contains(err.Error(), "Incorrect function") {
				var actualMuted bool
				if getErr := vol.GetMute(&actualMuted); getErr == nil {
					isMuted = actualMuted
				}
				return nil
			}
			return fmt.Errorf("mute: %w", err)
		}
		return nil
	})
	if err != nil {
		return protocol.Volume{}, err
	}
	return protocol.Volume{Level: level, Muted: isMuted}, nil
}

func audioInputs() ([]protocol.AudioDevice, error) {
	return inputCache.get(inputTTL, listCaptureEndpoints)
}

func setAudioInput(deviceID string) ([]protocol.AudioDevice, error) {
	if deviceID == "" {
		return nil, fmt.Errorf("audio: input device id is required")
	}
	if err := withCOM(func() error { return setDefaultEndpoint(deviceID) }); err != nil {
		return nil, err
	}
	inputCache.invalidate()
	return audioInputs()
}

func listCaptureEndpoints() ([]protocol.AudioDevice, error) {
	devices := []protocol.AudioDevice{}
	err := withDeviceEnumerator(func(enumerator *wca.IMMDeviceEnumerator) error {
		defaultID := defaultCaptureEndpointID(enumerator)

		var collection *wca.IMMDeviceCollection
		if err := enumerator.EnumAudioEndpoints(wca.ECapture, wca.DEVICE_STATE_ACTIVE, &collection); err != nil {
			return fmt.Errorf("audio: enumerate capture endpoints: %w", err)
		}
		defer collection.Release()

		var count uint32
		if err := collection.GetCount(&count); err != nil {
			return fmt.Errorf("audio: capture endpoint count: %w", err)
		}
		for i := uint32(0); i < count; i++ {
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

func defaultCaptureEndpointID(enumerator *wca.IMMDeviceEnumerator) string {
	var device *wca.IMMDevice
	if err := enumerator.GetDefaultAudioEndpoint(wca.ECapture, wca.ECommunications, &device); err != nil {
		if err := enumerator.GetDefaultAudioEndpoint(wca.ECapture, wca.EConsole, &device); err != nil {
			return ""
		}
	}
	defer device.Release()
	var id string
	if err := device.GetId(&id); err != nil {
		return ""
	}
	return id
}
