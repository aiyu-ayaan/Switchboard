//go:build windows

package camera

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/draw"
	"image/jpeg"
	"runtime"
	"sync"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

const (
	vcamMaxImageSize = 3840 * 2160 * 4 * 2 // 4K max image size matching UnityCapture
	vcamHeaderSize   = 32
	vcamTotalSize    = vcamHeaderSize + vcamMaxImageSize

	vcamMutexName  = "UnityCapture_Mutx"
	vcamWantName   = "UnityCapture_Want"
	vcamSentName   = "UnityCapture_Sent"
	vcamSharedData = "UnityCapture_Data"

	vcamFormatUint8      = 0
	vcamResizeLinear     = 1
	vcamMirrorDisabled   = 0
	vcamDefaultTimeoutMs = 1000
)

// VCamFeeder feeds decoded video frames into the Windows DirectShow virtual
// camera filter via shared memory-mapped buffer.
type VCamFeeder struct {
	mu          sync.Mutex
	hMutex      windows.Handle
	hWantEvent  windows.Handle
	hSentEvent  windows.Handle
	hSharedFile windows.Handle
	// Held as an unsafe.Pointer rather than the uintptr MapViewOfFile
	// returns. A uintptr is just a number the collector does not treat as a
	// reference, so converting one back to a pointer at each use is the
	// pattern go vet flags — correctly, even though this particular mapping is
	// OS-owned and never moves. Converting once, here, is both sound and
	// quiet.
	sharedView    unsafe.Pointer
	active        bool
	lastWidth     int
	lastHeight    int
	rgbaBuf       *image.RGBA
	standbyImage  *image.RGBA
	lastLiveAt    time.Time
	lastStandbyAt time.Time
	frameChan     chan []byte
	closeChan     chan struct{}
	workerDone    chan struct{}
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

	v.hMutex = hMutex
	v.hWantEvent = hWant
	v.hSentEvent = hSent
	v.hSharedFile = hSharedFile
	v.sharedView = *(*unsafe.Pointer)(unsafe.Pointer(&sharedView))
	v.standbyImage = GenerateStandbyImage(1280, 720)
	v.frameChan = make(chan []byte, 1)
	v.closeChan = make(chan struct{})
	v.workerDone = make(chan struct{})
	v.active = true

	// Write initial standby frame immediately so memory is never blank or uninitialized
	v.writeFrameLocked(v.standbyImage.Pix, 1280, 720)

	go v.worker()
	return nil
}

func (v *VCamFeeder) writeFrameLocked(pix []byte, width, height int) {
	if v.sharedView == nil || width <= 0 || height <= 0 {
		return
	}
	dataSize := width * height * 4
	if dataSize > vcamMaxImageSize {
		return
	}

	res, _ := windows.WaitForSingleObject(v.hMutex, 50)
	if res != windows.WAIT_OBJECT_0 && res != windows.WAIT_ABANDONED {
		return
	}
	defer windows.ReleaseMutex(v.hMutex)

	headerBytes := unsafe.Slice((*byte)(v.sharedView), vcamHeaderSize)
	binary.LittleEndian.PutUint32(headerBytes[0:4], uint32(vcamMaxImageSize))
	binary.LittleEndian.PutUint32(headerBytes[4:8], uint32(width))
	binary.LittleEndian.PutUint32(headerBytes[8:12], uint32(height))
	binary.LittleEndian.PutUint32(headerBytes[12:16], uint32(width)) // stride in pixels
	binary.LittleEndian.PutUint32(headerBytes[16:20], uint32(vcamFormatUint8))
	binary.LittleEndian.PutUint32(headerBytes[20:24], uint32(vcamResizeLinear))
	binary.LittleEndian.PutUint32(headerBytes[24:28], uint32(vcamMirrorDisabled))
	binary.LittleEndian.PutUint32(headerBytes[28:32], uint32(vcamDefaultTimeoutMs))

	dstPix := unsafe.Slice((*byte)(unsafe.Add(v.sharedView, vcamHeaderSize)), dataSize)
	if len(pix) >= dataSize {
		// Windows DirectShow RGB32 buffers expect bottom-up DIB ordering (line 0 is bottom).
		// Invert row order so images appear upright in external capture applications.
		rowBytes := width * 4
		for y := 0; y < height; y++ {
			srcOffset := (height - 1 - y) * rowBytes
			dstOffset := y * rowBytes
			copy(dstPix[dstOffset:dstOffset+rowBytes], pix[srcOffset:srcOffset+rowBytes])
		}
	}

	_ = windows.SetEvent(v.hSentEvent)
}

func (v *VCamFeeder) feedStandby() {
	v.mu.Lock()
	img := v.standbyImage
	v.mu.Unlock()

	if img == nil {
		return
	}
	v.writeFrameLocked(img.Pix, img.Rect.Dx(), img.Rect.Dy())
	v.lastStandbyAt = time.Now()
}

func (v *VCamFeeder) processLiveFrame(jpegBytes []byte) {
	img, err := jpeg.Decode(bytes.NewReader(jpegBytes))
	if err != nil {
		return
	}

	bounds := img.Bounds()
	width := bounds.Dx()
	height := bounds.Dy()
	if width <= 0 || height <= 0 {
		return
	}

	// Reallocate reusable RGBA buffer if resolution changes
	if v.rgbaBuf == nil || v.lastWidth != width || v.lastHeight != height {
		v.rgbaBuf = image.NewRGBA(image.Rect(0, 0, width, height))
		v.lastWidth = width
		v.lastHeight = height
	}

	draw.Draw(v.rgbaBuf, v.rgbaBuf.Bounds(), img, bounds.Min, draw.Src)
	v.writeFrameLocked(v.rgbaBuf.Pix, width, height)

	v.mu.Lock()
	v.lastLiveAt = time.Now()
	v.mu.Unlock()
}

func (v *VCamFeeder) worker() {
	// Pin worker goroutine to OS thread for reliable Win32 Mutex ownership
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()
	defer close(v.workerDone)

	// Write initial standby frame immediately
	v.feedStandby()

	closeChan := v.closeChan
	ticker := time.NewTicker(40 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-closeChan:
			return

		case jpegBytes := <-v.frameChan:
			v.processLiveFrame(jpegBytes)

		case <-ticker.C:
			res, _ := windows.WaitForSingleObject(v.hWantEvent, 0)

			v.mu.Lock()
			isLive := time.Since(v.lastLiveAt) <= 1200*time.Millisecond
			v.mu.Unlock()

			if !isLive {
				if res == windows.WAIT_OBJECT_0 || time.Since(v.lastStandbyAt) >= 300*time.Millisecond {
					v.feedStandby()
				}
			}
		}
	}
}

// Feed queues the latest JPEG frame to the dedicated feeder thread.
// It is strictly non-blocking and drops stale frames if the feeder is busy.
func (v *VCamFeeder) Feed(jpegBytes []byte) error {
	if len(jpegBytes) == 0 {
		return nil
	}

	v.mu.Lock()
	if !v.active {
		if err := v.init(); err != nil {
			v.mu.Unlock()
			return err
		}
	}
	ch := v.frameChan
	v.mu.Unlock()

	if ch != nil {
		select {
		case ch <- jpegBytes:
		default:
			// Feeder is processing a frame; drop this one to avoid latency/backlog
		}
	}
	return nil
}

// NotifyStopped switches the virtual camera back to the standby card immediately.
func (v *VCamFeeder) NotifyStopped() {
	v.mu.Lock()
	v.lastLiveAt = time.Time{}
	ch := v.frameChan
	v.mu.Unlock()

	if ch != nil {
		select {
		case <-ch:
		default:
		}
	}
	v.feedStandby()
}

// Close releases the Windows handles and views.
func (v *VCamFeeder) Close() {
	v.mu.Lock()
	if !v.active {
		v.mu.Unlock()
		return
	}
	v.active = false

	if v.closeChan != nil {
		close(v.closeChan)
	}
	workerDone := v.workerDone
	v.mu.Unlock()

	// Wait for worker to exit BEFORE unmapping and closing handles
	if workerDone != nil {
		<-workerDone
	}

	v.mu.Lock()
	defer v.mu.Unlock()

	if v.sharedView != nil {
		_ = windows.UnmapViewOfFile(uintptr(v.sharedView))
		v.sharedView = nil
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
