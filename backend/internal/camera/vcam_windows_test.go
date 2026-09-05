//go:build windows

package camera

import (
	"encoding/binary"
	"testing"
	"unsafe"

	"golang.org/x/sys/windows"
)

func TestDirectShowSharedMemoryProtocol(t *testing.T) {
	feeder := NewVCamFeeder()
	if feeder == nil {
		t.Fatal("NewVCamFeeder returned nil")
	}
	defer feeder.Close()

	// 1. Filter opens shared file mapping via OpenFileMapping
	kernel32 := windows.NewLazySystemDLL("kernel32.dll")
	procOpenFileMappingW := kernel32.NewProc("OpenFileMappingW")
	dataName, _ := windows.UTF16PtrFromString(vcamSharedData)
	r1, _, err := procOpenFileMappingW.Call(
		windows.FILE_MAP_READ,
		0,
		uintptr(unsafe.Pointer(dataName)),
	)
	if r1 == 0 {
		t.Fatalf("filter failed to OpenFileMapping: %v", err)
	}
	hMap := windows.Handle(r1)
	defer windows.CloseHandle(hMap)

	view, err := windows.MapViewOfFile(hMap, windows.FILE_MAP_READ, 0, 0, 0)
	if err != nil {
		t.Fatalf("filter failed to MapViewOfFile: %v", err)
	}
	defer windows.UnmapViewOfFile(view)

	// 2. Read 32-byte header written by feeder
	headerBytes := unsafe.Slice((*byte)(unsafe.Pointer(view)), vcamHeaderSize)
	maxSize := binary.LittleEndian.Uint32(headerBytes[0:4])
	width := binary.LittleEndian.Uint32(headerBytes[4:8])
	height := binary.LittleEndian.Uint32(headerBytes[8:12])
	stride := binary.LittleEndian.Uint32(headerBytes[12:16])
	format := binary.LittleEndian.Uint32(headerBytes[16:20])

	if maxSize != vcamMaxImageSize {
		t.Fatalf("header maxSize = %d, want %d", maxSize, vcamMaxImageSize)
	}
	if width != 1280 || height != 720 {
		t.Fatalf("standby frame dimensions = %dx%d, want 1280x720", width, height)
	}
	if stride != 1280 {
		t.Fatalf("stride = %d, want 1280", stride)
	}
	if format != vcamFormatUint8 {
		t.Fatalf("format = %d, want %d", format, vcamFormatUint8)
	}

	// 3. Open sync events like UnityCaptureFilter
	wantName, _ := windows.UTF16PtrFromString(vcamWantName)
	hWant, err := windows.OpenEvent(windows.EVENT_MODIFY_STATE, false, wantName)
	if err != nil {
		t.Fatalf("filter failed to OpenEvent for Want: %v", err)
	}
	defer windows.CloseHandle(hWant)

	sentName, _ := windows.UTF16PtrFromString(vcamSentName)
	hSent, err := windows.OpenEvent(windows.SYNCHRONIZE, false, sentName)
	if err != nil {
		t.Fatalf("filter failed to OpenEvent for Sent: %v", err)
	}
	defer windows.CloseHandle(hSent)

	// DirectShow filter signals WantEvent to request next frame
	if err := windows.SetEvent(hWant); err != nil {
		t.Fatalf("failed to signal WantEvent: %v", err)
	}

	// DirectShow filter waits for SentEvent (timeout 500ms)
	waitRes, err := windows.WaitForSingleObject(hSent, 500)
	if waitRes != windows.WAIT_OBJECT_0 {
		t.Fatalf("filter timed out waiting for SentEvent: res=%d err=%v", waitRes, err)
	}

	// 4. DirectShow filter locks Mutex, reads buffer, and releases Mutex
	mutxName, _ := windows.UTF16PtrFromString(vcamMutexName)
	hMutx, err := windows.OpenMutex(windows.SYNCHRONIZE, false, mutxName)
	if err != nil {
		t.Fatalf("filter failed to OpenMutex: %v", err)
	}
	defer windows.CloseHandle(hMutx)

	mRes, err := windows.WaitForSingleObject(hMutx, 50)
	if mRes != windows.WAIT_OBJECT_0 {
		t.Fatalf("filter failed to acquire Mutex (deadlock check): res=%d err=%v", mRes, err)
	}
	_ = windows.ReleaseMutex(hMutx)
}
