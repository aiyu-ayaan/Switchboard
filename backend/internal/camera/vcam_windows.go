//go:build windows

package camera

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/draw"
	"image/jpeg"
	"sync"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const (
	vcamMaxImageSize = 3840 * 2160 * 4 * 2 // 4K max image size matching UnityCapture
	vcamHeaderSize   = 32
	vcamTotalSize    = vcamHeaderSize + vcamMaxImageSize

	vcamMutexName    = "UnityCapture_Mutx0"
	vcamWantName     = "UnityCapture_Want0"
	vcamSentName     = "UnityCapture_Sent0"
	vcamSharedData   = "UnityCapture_Data0"

	vcamFormatUint8       = 0
	vcamResizeLinear      = 1
	vcamMirrorDisabled    = 0
	vcamDefaultTimeoutMs  = 1000
)

// VCamFeeder feeds decoded video frames into the Windows DirectShow virtual
// camera filter via shared memory-mapped buffer.
type VCamFeeder struct {
	mu           sync.Mutex
	hMutex       windows.Handle
	hWantEvent   windows.Handle
	hSentEvent   windows.Handle
	hSharedFile  windows.Handle
	sharedView   uintptr
	active       bool
	lastWidth    int
	lastHeight   int
	rgbaBuf      *image.RGBA
}

// NewVCamFeeder initializes the shared memory structures for the virtual camera.
func NewVCamFeeder() *VCamFeeder {
	feeder := &VCamFeeder{}
	if err := feeder.init(); err != nil {
		return feeder
	}
	return feeder
}

func (v *VCamFeeder) init() error {
	v.mu.Lock()
	defer v.mu.Unlock()

	if v.active {
		return nil
	}

	mutexName, _ := windows.UTF16PtrFromString(vcamMutexName)
	hMutex, err := windows.CreateMutex(nil, false, mutexName)
	if err != nil && err != windows.ERROR_ALREADY_EXISTS {
		return err
	}

	wantName, _ := windows.UTF16PtrFromString(vcamWantName)
	hWant, err := windows.CreateEvent(nil, 0, 0, wantName)
	if err != nil && err != windows.ERROR_ALREADY_EXISTS {
		windows.CloseHandle(hMutex)
		return err
	}

	sentName, _ := windows.UTF16PtrFromString(vcamSentName)
	hSent, err := windows.CreateEvent(nil, 0, 0, sentName)
	if err != nil && err != windows.ERROR_ALREADY_EXISTS {
		windows.CloseHandle(hMutex)
		windows.CloseHandle(hWant)
		return err
	}

	dataName, _ := windows.UTF16PtrFromString(vcamSharedData)
	hSharedFile, err := windows.CreateFileMapping(
		windows.InvalidHandle,
		nil,
		windows.PAGE_READWRITE,
		0,
		vcamTotalSize,
		dataName,
	)
	if err != nil && err != windows.ERROR_ALREADY_EXISTS {
		windows.CloseHandle(hMutex)
		windows.CloseHandle(hWant)
		windows.CloseHandle(hSent)
		return err
	}

	sharedView, err := windows.MapViewOfFile(hSharedFile, windows.FILE_MAP_WRITE, 0, 0, 0)
	if err != nil {
		windows.CloseHandle(hSharedFile)
		windows.CloseHandle(hMutex)
		windows.CloseHandle(hWant)
		windows.CloseHandle(hSent)
		return err
	}

	// Initialize header maxSize
	headerBytes := unsafe.Slice((*byte)(unsafe.Pointer(sharedView)), vcamHeaderSize)
	binary.LittleEndian.PutUint32(headerBytes[0:4], uint32(vcamMaxImageSize))

	v.hMutex = hMutex
	v.hWantEvent = hWant
	v.hSentEvent = hSent
	v.hSharedFile = hSharedFile
	v.sharedView = sharedView
	v.active = true
	return nil
}

// Feed decodes the JPEG and pushes pixel bytes to the virtual camera filter.
func (v *VCamFeeder) Feed(jpegBytes []byte) error {
	if len(jpegBytes) == 0 {
		return nil
	}

	if !v.active {
		if err := v.init(); err != nil {
			return err
		}
	}

	img, err := jpeg.Decode(bytes.NewReader(jpegBytes))
	if err != nil {
		return err
	}

	bounds := img.Bounds()
	width := bounds.Dx()
	height := bounds.Dy()
	if width <= 0 || height <= 0 {
		return nil
	}

	v.mu.Lock()
	defer v.mu.Unlock()

	if !v.active || v.sharedView == 0 {
		return nil
	}

	// Reallocate reusable RGBA buffer if resolution changes
	if v.rgbaBuf == nil || v.lastWidth != width || v.lastHeight != height {
		v.rgbaBuf = image.NewRGBA(image.Rect(0, 0, width, height))
		v.lastWidth = width
		v.lastHeight = height
	}

	// Draw incoming image onto RGBA buffer
	draw.Draw(v.rgbaBuf, v.rgbaBuf.Bounds(), img, bounds.Min, draw.Src)
	dataSize := width * height * 4
	if dataSize > vcamMaxImageSize {
		return nil
	}

	// Lock mutex
	res, _ := windows.WaitForSingleObject(v.hMutex, 50)
	if res != windows.WAIT_OBJECT_0 {
		return nil // skip frame if filter is currently reading
	}
	defer windows.ReleaseMutex(v.hMutex)

	headerBytes := unsafe.Slice((*byte)(unsafe.Pointer(v.sharedView)), vcamHeaderSize)
	binary.LittleEndian.PutUint32(headerBytes[0:4], uint32(vcamMaxImageSize))
	binary.LittleEndian.PutUint32(headerBytes[4:8], uint32(width))
	binary.LittleEndian.PutUint32(headerBytes[8:12], uint32(height))
	binary.LittleEndian.PutUint32(headerBytes[12:16], uint32(width)) // stride in pixels
	binary.LittleEndian.PutUint32(headerBytes[16:20], uint32(vcamFormatUint8))
	binary.LittleEndian.PutUint32(headerBytes[20:24], uint32(vcamResizeLinear))
	binary.LittleEndian.PutUint32(headerBytes[24:28], uint32(vcamMirrorDisabled))
	binary.LittleEndian.PutUint32(headerBytes[28:32], uint32(vcamDefaultTimeoutMs))

	// Copy RGBA pixels into shared buffer data segment
	dstPix := unsafe.Slice((*byte)(unsafe.Pointer(v.sharedView+vcamHeaderSize)), dataSize)
	copy(dstPix, v.rgbaBuf.Pix)

	// Notify receiver that a new frame has been sent
	_ = windows.SetEvent(v.hSentEvent)
	return nil
}

// Close releases the Windows handles and views.
func (v *VCamFeeder) Close() {
	v.mu.Lock()
	defer v.mu.Unlock()

	if !v.active {
		return
	}
	v.active = false

	if v.sharedView != 0 {
		_ = windows.UnmapViewOfFile(v.sharedView)
		v.sharedView = 0
	}
	if v.hSharedFile != 0 {
		_ = windows.CloseHandle(v.hSharedFile)
		v.hSharedFile = 0
	}
	if v.hMutex != 0 {
		_ = windows.CloseHandle(v.hMutex)
		v.hMutex = 0
	}
	if v.hWantEvent != 0 {
		_ = windows.CloseHandle(v.hWantEvent)
		v.hWantEvent = 0
	}
	if v.hSentEvent != 0 {
		_ = windows.CloseHandle(v.hSentEvent)
		v.hSentEvent = 0
	}
}

// IsVCamInstalled checks if Switchboard Camera is registered in Windows DirectShow.
func IsVCamInstalled() bool {
	// Check standard 64-bit and 32-bit registry locations for DirectShow Video Input Category
	paths := []struct {
		root registry.Key
		path string
	}{
		{
			registry.LOCAL_MACHINE,
			`SOFTWARE\Classes\CLSID\{860BB310-5D01-11D0-BD3B-00A0C911CE86}\Instance`,
		},
		{
			registry.LOCAL_MACHINE,
			`SOFTWARE\WOW6432Node\Classes\CLSID\{860BB310-5D01-11D0-BD3B-00A0C911CE86}\Instance`,
		},
		{
			registry.CLASSES_ROOT,
			`CLSID\{860BB310-5D01-11D0-BD3B-00A0C911CE86}\Instance`,
		},
	}

	for _, p := range paths {
		k, err := registry.OpenKey(p.root, p.path, registry.ENUMERATE_SUB_KEYS|registry.QUERY_VALUE)
		if err != nil {
			continue
		}
		names, err := k.ReadSubKeyNames(-1)
		k.Close()
		if err != nil {
			continue
		}

		for _, sub := range names {
			subKey, err := registry.OpenKey(p.root, p.path+`\`+sub, registry.QUERY_VALUE)
			if err != nil {
				continue
			}
			friendlyName, _, err := subKey.GetStringValue("FriendlyName")
			subKey.Close()
			if err == nil && (friendlyName == "Switchboard Camera" || friendlyName == "Unity Video Capture") {
				return true
			}
		}
	}
	return false
}
