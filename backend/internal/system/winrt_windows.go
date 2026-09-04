//go:build windows

package system

import (
	"errors"
	"fmt"
	"log"
	"runtime"
	"syscall"
	"time"
	"unsafe"

	"github.com/go-ole/go-ole"
	"github.com/saltosystems/winrt-go/windows/foundation"
	"github.com/saltosystems/winrt-go/windows/storage/streams"
)

const (
	roInitMultiThreaded = 1

	// A WinRT call that has not settled within this window is abandoned. The
	// media session is owned by another process, and a wedged player must not
	// be able to stall the daemon's poller indefinitely.
	asyncTimeout  = 3 * time.Second
	asyncInterval = 2 * time.Millisecond
)

// winrtCalls serialises every WinRT call onto one OS thread whose apartment is
// initialised exactly once.
//
// COM apartments are per-thread and go-ole exposes no RoUninitialize, so
// initialising per call would either leak apartments across the runtime's
// thread pool or fail on the second call to land on the same thread. A single
// dedicated worker sidesteps both, and serialising is no loss here: the media
// session is polled, not hammered.
var winrtCalls = startWinRTThread()

func startWinRTThread() chan func() {
	calls := make(chan func())
	ready := make(chan struct{})
	go func() {
		runtime.LockOSThread()
		defer runtime.UnlockOSThread()
		if err := ole.RoInitialize(roInitMultiThreaded); err != nil {
			// Calls still run and fail individually with a real message,
			// which beats the daemon refusing to start over media.
			log.Printf("winrt: apartment unavailable: %v", err)
		}
		close(ready)
		for fn := range calls {
			fn()
		}
	}()
	<-ready
	return calls
}

// onWinRT runs fn on the WinRT thread and returns its error.
func onWinRT(fn func() error) error {
	done := make(chan error, 1)
	winrtCalls <- func() { done <- fn() }
	return <-done
}

// awaitOp blocks until an IAsyncOperation settles and returns its result. It
// takes ownership of op and releases it.
func awaitOp(op *foundation.IAsyncOperation) (unsafe.Pointer, error) {
	if op == nil {
		return nil, errors.New("winrt: nil async operation")
	}
	defer op.Release()
	return awaitAsync(&op.IInspectable, op.GetResults)
}

// awaitAsync polls an async interface to completion, then reads its result.
//
// Completion is polled through IAsyncInfo rather than registered through a
// completion handler: a handler would mean handing WinRT a Go callback across
// the cgo boundary, and every operation here settles in milliseconds.
func awaitAsync(inspectable *ole.IInspectable, results func() (unsafe.Pointer, error)) (unsafe.Pointer, error) {
	itf, err := inspectable.QueryInterface(ole.NewGUID(foundation.GUIDIAsyncInfo))
	if err != nil {
		return nil, fmt.Errorf("winrt: IAsyncInfo: %w", err)
	}
	info := (*foundation.IAsyncInfo)(unsafe.Pointer(itf))
	defer info.Release()

	deadline := time.Now().Add(asyncTimeout)
	for {
		status, err := info.GetStatus()
		if err != nil {
			return nil, fmt.Errorf("winrt: async status: %w", err)
		}
		switch status {
		case foundation.AsyncStatusCompleted:
			return results()
		case foundation.AsyncStatusError:
			hr, _ := info.GetErrorCode()
			return nil, fmt.Errorf("winrt: async failed: hresult 0x%08x", uint32(hr.Value))
		case foundation.AsyncStatusCanceled:
			return nil, errors.New("winrt: async cancelled")
		}
		if time.Now().After(deadline) {
			info.Cancel()
			return nil, errors.New("winrt: async timed out")
		}
		time.Sleep(asyncInterval)
	}
}

// ---- Hand-rolled interfaces ----
//
// winrt-go pre-generates everything this package needs except the stream read
// path, so IInputStream and the progress-carrying async operation its
// ReadAsync returns are declared here. Vtable layout is fixed by the WinRT
// ABI: the six IInspectable slots, then the interface's own methods in IDL
// order.

const guidIInputStream = "905a0fe2-bc53-11df-8c49-001e4fc686da"

type iInputStream struct{ ole.IInspectable }

type iInputStreamVtbl struct {
	ole.IInspectableVtbl

	ReadAsync uintptr
}

type iAsyncOperationWithProgress struct{ ole.IInspectable }

type iAsyncOperationWithProgressVtbl struct {
	ole.IInspectableVtbl

	SetProgress  uintptr
	GetProgress  uintptr
	SetCompleted uintptr
	GetCompleted uintptr
	GetResults   uintptr
}

// readStream drains a random-access stream reference into memory.
//
// limit caps both the allocation and the read, so a session advertising an
// implausibly large thumbnail cannot make the daemon allocate without bound.
func readStream(ref *streams.IRandomAccessStreamReference, limit uint32) ([]byte, error) {
	op, err := ref.OpenReadAsync()
	if err != nil {
		return nil, fmt.Errorf("winrt: open stream: %w", err)
	}
	ptr, err := awaitOp(op)
	if err != nil {
		return nil, err
	}
	if ptr == nil {
		return nil, errors.New("winrt: stream reference opened nothing")
	}
	stream := (*ole.IUnknown)(ptr)
	defer stream.Release()

	itf, err := stream.QueryInterface(ole.NewGUID(guidIInputStream))
	if err != nil {
		return nil, fmt.Errorf("winrt: IInputStream: %w", err)
	}
	input := (*iInputStream)(unsafe.Pointer(itf))
	defer input.Release()

	target, err := streams.BufferCreate(limit)
	if err != nil {
		return nil, fmt.Errorf("winrt: buffer: %w", err)
	}
	defer target.Release()

	bufItf := target.MustQueryInterface(ole.NewGUID(streams.GUIDIBuffer))
	defer bufItf.Release()

	filled, err := readInto(input, (*streams.IBuffer)(unsafe.Pointer(bufItf)), limit)
	if err != nil {
		return nil, err
	}
	defer filled.Release()

	length, err := filled.GetLength()
	if err != nil {
		return nil, fmt.Errorf("winrt: buffer length: %w", err)
	}
	if length == 0 {
		return nil, nil
	}

	reader, err := streams.DataReaderFromBuffer(filled)
	if err != nil {
		return nil, fmt.Errorf("winrt: data reader: %w", err)
	}
	defer reader.Release()
	return reader.ReadBytes(length)
}

// readInto issues IInputStream::ReadAsync and waits for the buffer it fills.
func readInto(input *iInputStream, target *streams.IBuffer, count uint32) (*streams.IBuffer, error) {
	var op *iAsyncOperationWithProgress
	hr, _, _ := syscall.SyscallN(
		(*iInputStreamVtbl)(unsafe.Pointer(input.RawVTable)).ReadAsync,
		uintptr(unsafe.Pointer(input)),  // this
		uintptr(unsafe.Pointer(target)), // in IBuffer
		uintptr(count),                  // in uint32
		0,                               // in InputStreamOptions.None: read to count or end of stream
		uintptr(unsafe.Pointer(&op)),    // out IAsyncOperationWithProgress
	)
	if hr != 0 {
		return nil, fmt.Errorf("winrt: ReadAsync: %w", ole.NewError(hr))
	}
	defer op.Release()

	vtbl := (*iAsyncOperationWithProgressVtbl)(unsafe.Pointer(op.RawVTable))
	ptr, err := awaitAsync(&op.IInspectable, func() (unsafe.Pointer, error) {
		var out unsafe.Pointer
		hr, _, _ := syscall.SyscallN(
			vtbl.GetResults,
			uintptr(unsafe.Pointer(op)),
			uintptr(unsafe.Pointer(&out)),
		)
		if hr != 0 {
			return nil, ole.NewError(hr)
		}
		return out, nil
	})
	if err != nil {
		return nil, err
	}
	if ptr == nil {
		return nil, errors.New("winrt: ReadAsync returned no buffer")
	}
	return (*streams.IBuffer)(ptr), nil
}
