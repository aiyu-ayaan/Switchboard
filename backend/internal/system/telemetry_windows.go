//go:build windows

package system

import (
	"context"
	"os/exec"
	"sort"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
	"unsafe"

	"github.com/go-ole/go-ole"
	"switchboard/backend/internal/protocol"
)

var (
	modKernel32 = syscall.NewLazyDLL("kernel32.dll")
	modIphlpapi = syscall.NewLazyDLL("iphlpapi.dll")
	modPsapi    = syscall.NewLazyDLL("psapi.dll")

	procGetSystemTimes         = modKernel32.NewProc("GetSystemTimes")
	procGlobalMemoryStatusEx   = modKernel32.NewProc("GlobalMemoryStatusEx")
	procGetLogicalDriveStrings = modKernel32.NewProc("GetLogicalDriveStringsW")
	procGetDiskFreeSpaceEx     = modKernel32.NewProc("GetDiskFreeSpaceExW")
	procGetVolumeInformation   = modKernel32.NewProc("GetVolumeInformationW")
	procCreateToolhelp32       = modKernel32.NewProc("CreateToolhelp32Snapshot")
	procProcess32First         = modKernel32.NewProc("Process32FirstW")
	procProcess32Next          = modKernel32.NewProc("Process32NextW")
	procOpenProcess            = modKernel32.NewProc("OpenProcess")
	procCloseHandle            = modKernel32.NewProc("CloseHandle")

	procGetIfTable2  = modIphlpapi.NewProc("GetIfTable2")
	procFreeMibTable = modIphlpapi.NewProc("FreeMibTable")

	procGetProcessMemoryInfo = modPsapi.NewProc("GetProcessMemoryInfo")
	procGetProcessIoCounters = modKernel32.NewProc("GetProcessIoCounters")
)

type ioCounters struct {
	ReadOperationCount  uint64
	WriteOperationCount uint64
	OtherOperationCount uint64
	ReadTransferCount   uint64
	WriteTransferCount  uint64
	OtherTransferCount  uint64
}

type memoryStatusEx struct {
	cbSize                  uint32
	dwMemoryLoad            uint32
	ullTotalPhys            uint64
	ullAvailPhys            uint64
	ullTotalPageFile        uint64
	ullAvailPageFile        uint64
	ullTotalVirtual         uint64
	ullAvailVirtual         uint64
	ullAvailExtendedVirtual uint64
}

type processMemoryCounters struct {
	cb                         uint32
	pageFaultCount             uint32
	peakWorkingSetSize         uintptr
	workingSetSize             uintptr
	quotaPeakPagedPoolUsage    uintptr
	quotaPagedPoolUsage        uintptr
	quotaPeakNonPagedPoolUsage uintptr
	quotaNonPagedPoolUsage     uintptr
	pagefileUsage              uintptr
	peakPagefileUsage          uintptr
}

type processEntry32W struct {
	dwSize              uint32
	cntUsage            uint32
	th32ProcessID       uint32
	th32DefaultHeapID   uintptr
	th32ModuleID        uint32
	cntThreads          uint32
	th32ParentProcessID uint32
	pcPriClassBase      int32
	dwFlags             uint32
	szExeFile           [260]uint16
}

var (
	telemetryMu sync.Mutex

	prevSampleTime time.Time
	prevIdleTime   uint64
	prevKernelTime uint64
	prevUserTime   uint64

	prevNetRx uint64
	prevNetTx uint64
)

func fileTimeToUint64(ft *syscall.Filetime) uint64 {
	return (uint64(ft.HighDateTime) << 32) | uint64(ft.LowDateTime)
}

func sampleCPU() float64 {
	var idle, kernel, user syscall.Filetime
	r, _, _ := procGetSystemTimes.Call(
		uintptr(unsafe.Pointer(&idle)),
		uintptr(unsafe.Pointer(&kernel)),
		uintptr(unsafe.Pointer(&user)),
	)
	if r == 0 {
		return 0.0
	}

	idleVal := fileTimeToUint64(&idle)
	kernelVal := fileTimeToUint64(&kernel)
	userVal := fileTimeToUint64(&user)

	if prevSampleTime.IsZero() {
		prevIdleTime = idleVal
		prevKernelTime = kernelVal
		prevUserTime = userVal
		prevSampleTime = time.Now()
		return 0.0
	}

	idleDelta := idleVal - prevIdleTime
	kernelDelta := kernelVal - prevKernelTime
	userDelta := userVal - prevUserTime

	prevIdleTime = idleVal
	prevKernelTime = kernelVal
	prevUserTime = userVal
	prevSampleTime = time.Now()

	totalSys := kernelDelta + userDelta
	if totalSys == 0 {
		return 0.0
	}

	cpuPercent := 100.0 * (1.0 - float64(idleDelta)/float64(totalSys))
	if cpuPercent < 0 {
		cpuPercent = 0
	}
	if cpuPercent > 100 {
		cpuPercent = 100
	}
	return cpuPercent
}

func sampleMemory() (used uint64, total uint64) {
	var ms memoryStatusEx
	ms.cbSize = uint32(unsafe.Sizeof(ms))
	r, _, _ := procGlobalMemoryStatusEx.Call(uintptr(unsafe.Pointer(&ms)))
	if r == 0 {
		return 0, 0
	}
	return ms.ullTotalPhys - ms.ullAvailPhys, ms.ullTotalPhys
}

func sampleDrives() []protocol.DriveItem {
	var buf [512]uint16
	r, _, _ := procGetLogicalDriveStrings.Call(uintptr(len(buf)), uintptr(unsafe.Pointer(&buf[0])))
	if r == 0 {
		return nil
	}

	var drives []protocol.DriveItem
	idx := 0
	for idx < int(r) {
		if buf[idx] == 0 {
			break
		}
		end := idx
		for end < int(r) && buf[end] != 0 {
			end++
		}
		path := syscall.UTF16ToString(buf[idx:end])
		idx = end + 1

		if len(path) == 0 {
			continue
		}

		pathPtr, err := syscall.UTF16PtrFromString(path)
		if err != nil {
			continue
		}

		var freeBytes, totalBytes, totalFreeBytes uint64
		r2, _, _ := procGetDiskFreeSpaceEx.Call(
			uintptr(unsafe.Pointer(pathPtr)),
			uintptr(unsafe.Pointer(&freeBytes)),
			uintptr(unsafe.Pointer(&totalBytes)),
			uintptr(unsafe.Pointer(&totalFreeBytes)),
		)
		if r2 == 0 {
			continue
		}

		var volumeName [256]uint16
		var fsName [256]uint16
		procGetVolumeInformation.Call(
			uintptr(unsafe.Pointer(pathPtr)),
			uintptr(unsafe.Pointer(&volumeName[0])),
			uintptr(len(volumeName)),
			0, 0, 0,
			uintptr(unsafe.Pointer(&fsName[0])),
			uintptr(len(fsName)),
		)

		label := syscall.UTF16ToString(volumeName[:])
		device := path
		if len(device) >= 2 && device[1] == ':' {
			device = device[:2]
		}

		drives = append(drives, protocol.DriveItem{
			Device:     device,
			Label:      label,
			TotalBytes: totalBytes,
			FreeBytes:  freeBytes,
		})
	}
	return drives
}

func sampleNetwork(intervalSec float64) (rxBps uint64, txBps uint64, totalRx uint64, totalTx uint64) {
	if procGetIfTable2.Find() != nil {
		return 0, 0, 0, 0
	}

	var pTable uintptr
	r, _, _ := procGetIfTable2.Call(uintptr(unsafe.Pointer(&pTable)))
	if r != 0 || pTable == 0 {
		return 0, 0, 0, 0
	}
	defer procFreeMibTable.Call(pTable)

	numEntries := *(*uint32)(unsafe.Pointer(pTable))
	if numEntries == 0 {
		return 0, 0, 0, 0
	}

	// MIB_IF_ROW2 structure size on 64-bit windows is 1352 bytes.
	// We read OperStatus (offset 1156), IfType (offset 1128), Flags (offset 1152),
	// InOctets (offset 1208), and OutOctets (offset 1280).
	const rowSize = 1352
	entriesPtr := pTable + 8

	for i := uint32(0); i < numEntries; i++ {
		rowPtr := entriesPtr + uintptr(i*rowSize)
		operStatus := *(*uint32)(unsafe.Pointer(rowPtr + 1156))
		ifType := *(*uint32)(unsafe.Pointer(rowPtr + 1128))
		flags := *(*byte)(unsafe.Pointer(rowPtr + 1152))

		// Only include UP interfaces, non-loopback (24), and exclude NDIS Lightweight Filters (flags&2 == 0)
		if operStatus == 1 && ifType != 24 && (flags&2 == 0) {
			inOctets := *(*uint64)(unsafe.Pointer(rowPtr + 1208))
			outOctets := *(*uint64)(unsafe.Pointer(rowPtr + 1280))
			totalRx += inOctets
			totalTx += outOctets
		}
	}

	if prevNetRx == 0 && prevNetTx == 0 {
		prevNetRx = totalRx
		prevNetTx = totalTx
		return 0, 0, totalRx, totalTx
	}

	deltaRx := uint64(0)
	if totalRx >= prevNetRx {
		deltaRx = totalRx - prevNetRx
	}
	deltaTx := uint64(0)
	if totalTx >= prevNetTx {
		deltaTx = totalTx - prevNetTx
	}

	prevNetRx = totalRx
	prevNetTx = totalTx

	if intervalSec > 0 {
		rxBps = uint64(float64(deltaRx) / intervalSec)
		txBps = uint64(float64(deltaTx) / intervalSec)
	}
	return rxBps, txBps, totalRx, totalTx
}

func sampleProcesses() ([]protocol.ProcessItem, []protocol.DataUsageItem) {
	const TH32CS_SNAPPROCESS = 0x00000002
	hSnap, _, _ := procCreateToolhelp32.Call(TH32CS_SNAPPROCESS, 0)
	if hSnap == 0 || hSnap == uintptr(syscall.InvalidHandle) {
		return nil, nil
	}
	defer procCloseHandle.Call(hSnap)

	var entry processEntry32W
	entry.dwSize = uint32(unsafe.Sizeof(entry))

	r, _, _ := procProcess32First.Call(hSnap, uintptr(unsafe.Pointer(&entry)))
	if r == 0 {
		return nil, nil
	}

	const PROCESS_QUERY_LIMITED_INFORMATION = 0x1000
	const PROCESS_VM_READ = 0x0010

	var procs []protocol.ProcessItem
	var dataItems []protocol.DataUsageItem

	for {
		pid := int(entry.th32ProcessID)
		name := syscall.UTF16ToString(entry.szExeFile[:])

		var ramBytes uint64
		var rxBytes, txBytes uint64

		if pid > 4 {
			hProc, _, _ := procOpenProcess.Call(
				PROCESS_QUERY_LIMITED_INFORMATION|PROCESS_VM_READ,
				0,
				uintptr(pid),
			)
			if hProc != 0 {
				var pmc processMemoryCounters
				pmc.cb = uint32(unsafe.Sizeof(pmc))
				rMem, _, _ := procGetProcessMemoryInfo.Call(
					hProc,
					uintptr(unsafe.Pointer(&pmc)),
					uintptr(pmc.cb),
				)
				if rMem != 0 {
					ramBytes = uint64(pmc.workingSetSize)
				}

				var io ioCounters
				rIo, _, _ := procGetProcessIoCounters.Call(
					hProc,
					uintptr(unsafe.Pointer(&io)),
				)
				if rIo != 0 {
					rxBytes = io.ReadTransferCount
					txBytes = io.WriteTransferCount
				}

				procCloseHandle.Call(hProc)
			}
		}

		if ramBytes > 0 {
			procs = append(procs, protocol.ProcessItem{
				Name:     name,
				PID:      pid,
				RAMBytes: ramBytes,
			})
		}

		totalBytes := rxBytes + txBytes
		if totalBytes > 0 {
			dataItems = append(dataItems, protocol.DataUsageItem{
				Name:       name,
				PID:        pid,
				RxBytes:    rxBytes,
				TxBytes:    txBytes,
				TotalBytes: totalBytes,
			})
		}

		rNext, _, _ := procProcess32Next.Call(hSnap, uintptr(unsafe.Pointer(&entry)))
		if rNext == 0 {
			break
		}
	}

	sort.Slice(procs, func(i, j int) bool {
		return procs[i].RAMBytes > procs[j].RAMBytes
	})
	if len(procs) > 5 {
		procs = procs[:5]
	}

	sort.Slice(dataItems, func(i, j int) bool {
		return dataItems[i].TotalBytes > dataItems[j].TotalBytes
	})
	if len(dataItems) > 8 {
		dataItems = dataItems[:8]
	}

	return procs, dataItems
}

func sampleCPUThermal() (cpuTemp *float64) {
	// 1. Try Win32_PerfFormattedData_Counters_ThermalZoneInformation in root\cimv2 (Standard, non-admin)
	_ = withWMI(`root\cimv2`, func(service *ole.IDispatch) error {
		return wmiQuery(service, "SELECT HighPrecisionTemperature, Temperature FROM Win32_PerfFormattedData_Counters_ThermalZoneInformation", func(instance *ole.IDispatch) error {
			if hpt, ok := wmiInt(instance, "HighPrecisionTemperature"); ok && hpt > 2732 {
				c := (float64(hpt) - 2732.0) / 10.0
				cpuTemp = &c
				return nil
			}
			if tKelvin, ok := wmiInt(instance, "Temperature"); ok && tKelvin > 273 {
				c := float64(tKelvin - 273)
				cpuTemp = &c
				return nil
			}
			return nil
		})
	})
	if cpuTemp != nil {
		return cpuTemp
	}

	// 2. Fallback to root\wmi MSAcpi_ThermalZoneTemperature (if elevated)
	_ = withWMI(`root\wmi`, func(service *ole.IDispatch) error {
		return wmiQuery(service, "SELECT CurrentTemperature FROM MSAcpi_ThermalZoneTemperature", func(instance *ole.IDispatch) error {
			if tempKelvinTenths, ok := wmiInt(instance, "CurrentTemperature"); ok && tempKelvinTenths > 2732 {
				c := (float64(tempKelvinTenths) - 2732.0) / 10.0
				cpuTemp = &c
			}
			return nil
		})
	})
	return cpuTemp
}

func sampleNvidiaGPU() (gpuUtil float64, gpuTemp *float64, memUsed uint64, memTotal uint64, ok bool) {
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	cmd := exec.CommandContext(ctx, "nvidia-smi", "--query-gpu=utilization.gpu,temperature.gpu,memory.used,memory.total", "--format=csv,noheader,nounits")
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	out, err := cmd.Output()
	if err != nil {
		return 0, nil, 0, 0, false
	}

	parts := strings.Split(strings.TrimSpace(string(out)), ",")
	if len(parts) < 4 {
		return 0, nil, 0, 0, false
	}

	util, _ := strconv.ParseFloat(strings.TrimSpace(parts[0]), 64)
	temp, errTemp := strconv.ParseFloat(strings.TrimSpace(parts[1]), 64)
	mUsedMiB, _ := strconv.ParseUint(strings.TrimSpace(parts[2]), 10, 64)
	mTotalMiB, _ := strconv.ParseUint(strings.TrimSpace(parts[3]), 10, 64)

	var tPtr *float64
	if errTemp == nil && temp > 0 {
		tPtr = &temp
	}

	return util, tPtr, mUsedMiB * 1024 * 1024, mTotalMiB * 1024 * 1024, true
}

func sampleMetrics() (protocol.MetricPoint, []protocol.ProcessItem, []protocol.DriveItem, []protocol.DataUsageItem, error) {
	telemetryMu.Lock()
	defer telemetryMu.Unlock()

	cpu := sampleCPU()
	ramUsed, ramTotal := sampleMemory()
	drives := sampleDrives()
	rxBps, txBps, totalRx, totalTx := sampleNetwork(2.0)
	procs, dataUsage := sampleProcesses()
	cpuTemp := sampleCPUThermal()

	gpuUtil, gpuTemp, gpuMemUsed, gpuMemTotal, _ := sampleNvidiaGPU()

	pt := protocol.MetricPoint{
		Timestamp:   time.Now().Unix(),
		CPU:         cpu,
		RAMUsed:     ramUsed,
		RAMTotal:    ramTotal,
		GPU:         gpuUtil,
		GPUMemUsed:  gpuMemUsed,
		GPUMemTotal: gpuMemTotal,
		NetRx:       rxBps,
		NetTx:       txBps,
		NetTotalRx:  totalRx,
		NetTotalTx:  totalTx,
		CPUTemp:     cpuTemp,
		GPUTemp:     gpuTemp,
	}

	return pt, procs, drives, dataUsage, nil
}
