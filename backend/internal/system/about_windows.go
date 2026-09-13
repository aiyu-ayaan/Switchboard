//go:build windows

package system

import (
	"fmt"
	"unsafe"

	"github.com/go-ole/go-ole"
	"golang.org/x/sys/windows/registry"
	"switchboard/backend/internal/protocol"
)

type systemPowerStatus struct {
	ACLineStatus        byte
	BatteryFlag         byte
	BatteryLifePercent  byte
	SystemStatusFlag    byte
	BatteryLifeTime     uint32
	BatteryFullLifeTime uint32
}

var (
	procGetSystemPowerStatus = modKernel32.NewProc("GetSystemPowerStatus")
	procGetTickCount64       = modKernel32.NewProc("GetTickCount64")
)

func getBatteryInfo() protocol.BatteryInfo {
	var sps systemPowerStatus
	r, _, _ := procGetSystemPowerStatus.Call(uintptr(unsafe.Pointer(&sps)))
	if r == 0 || sps.BatteryFlag == 128 { // 128 = No system battery
		return protocol.BatteryInfo{
			Present: false,
		}
	}

	info := protocol.BatteryInfo{
		Present:  true,
		Charging: sps.ACLineStatus == 1,
		Percent:  int(sps.BatteryLifePercent),
	}
	if info.Percent > 100 {
		info.Percent = 100
	}

	// Try reading detailed battery health from root\wmi
	_ = withWMI(`root\wmi`, func(service *ole.IDispatch) error {
		_ = wmiQuery(service, "SELECT DesignedCapacity FROM BatteryStaticData", func(instance *ole.IDispatch) error {
			if cap, ok := wmiInt(instance, "DesignedCapacity"); ok && cap > 0 {
				info.DesignCapacityMwh = uint64(cap)
			}
			return nil
		})
		_ = wmiQuery(service, "SELECT FullChargedCapacity FROM BatteryFullChargedCapacity", func(instance *ole.IDispatch) error {
			if cap, ok := wmiInt(instance, "FullChargedCapacity"); ok && cap > 0 {
				info.FullCapacityMwh = uint64(cap)
			}
			return nil
		})
		_ = wmiQuery(service, "SELECT CycleCount FROM BatteryCycleCount", func(instance *ole.IDispatch) error {
			if cycles, ok := wmiInt(instance, "CycleCount"); ok && cycles >= 0 {
				info.CycleCount = cycles
			}
			return nil
		})
		return nil
	})

	if info.DesignCapacityMwh > 0 && info.FullCapacityMwh > 0 {
		if info.DesignCapacityMwh > info.FullCapacityMwh {
			diff := float64(info.DesignCapacityMwh - info.FullCapacityMwh)
			info.WearPercent = (diff / float64(info.DesignCapacityMwh)) * 100.0
		} else {
			info.WearPercent = 0.0
		}
	}

	return info
}

func getOsInfo() protocol.OsInfo {
	name := "Windows"
	build := ""

	k, err := registry.OpenKey(registry.LOCAL_MACHINE, `SOFTWARE\Microsoft\Windows NT\CurrentVersion`, registry.QUERY_VALUE)
	if err == nil {
		defer k.Close()
		if prodName, _, err := k.GetStringValue("ProductName"); err == nil && prodName != "" {
			name = prodName
		}
		if dispVer, _, err := k.GetStringValue("DisplayVersion"); err == nil && dispVer != "" {
			name += " " + dispVer
		}
		if cb, _, err := k.GetStringValue("CurrentBuild"); err == nil {
			build = cb
		}
		if ubr, _, err := k.GetIntegerValue("UBR"); err == nil && build != "" {
			build = fmt.Sprintf("%s.%d", build, ubr)
		}
	}

	var uptimeSec uint64
	if r, _, _ := procGetTickCount64.Call(); r != 0 {
		uptimeSec = uint64(r) / 1000
	}

	return protocol.OsInfo{
		Name:          name,
		Build:         build,
		UptimeSeconds: uptimeSec,
	}
}

func getCpuInfo() protocol.CpuInfo {
	info := protocol.CpuInfo{
		Model: "Unknown Processor",
		Cores: 4,
	}

	k, err := registry.OpenKey(registry.LOCAL_MACHINE, `HARDWARE\DESCRIPTION\System\CentralProcessor\0`, registry.QUERY_VALUE)
	if err == nil {
		defer k.Close()
		if procName, _, err := k.GetStringValue("ProcessorNameString"); err == nil && procName != "" {
			info.Model = procName
		}
		if mhz, _, err := k.GetIntegerValue("~MHz"); err == nil && mhz > 0 {
			info.BaseClockMhz = int(mhz)
		}
	}

	// Query WMI for accurate core and logical processor count
	_ = withWMI(`root\cimv2`, func(service *ole.IDispatch) error {
		return wmiQuery(service, "SELECT NumberOfCores, NumberOfLogicalProcessors FROM Win32_Processor", func(instance *ole.IDispatch) error {
			if cores, ok := wmiInt(instance, "NumberOfCores"); ok && cores > 0 {
				info.Cores = cores
			}
			if threads, ok := wmiInt(instance, "NumberOfLogicalProcessors"); ok && threads > 0 {
				info.Threads = threads
			}
			return nil
		})
	})

	if info.Threads == 0 {
		info.Threads = info.Cores
	}

	return info
}

func getGpuInfo() []protocol.GpuInfo {
	var gpus []protocol.GpuInfo
	_ = withWMI(`root\cimv2`, func(service *ole.IDispatch) error {
		return wmiQuery(service, "SELECT Name, DriverVersion, AdapterRAM FROM Win32_VideoController", func(instance *ole.IDispatch) error {
			name := wmiString(instance, "Name")
			driver := wmiString(instance, "DriverVersion")
			vram, _ := wmiInt(instance, "AdapterRAM")
			if name != "" {
				gpus = append(gpus, protocol.GpuInfo{
					Name:      name,
					Driver:    driver,
					VRAMBytes: uint64(vram),
				})
			}
			return nil
		})
	})
	return gpus
}

func aboutSystem() (protocol.AboutSystemResponse, error) {
	_, totalRam := sampleMemory()
	drives := sampleDrives()
	battery := getBatteryInfo()
	osInfo := getOsInfo()
	cpuInfo := getCpuInfo()
	gpuInfo := getGpuInfo()

	return protocol.AboutSystemResponse{
		OS:      osInfo,
		CPU:     cpuInfo,
		Memory: protocol.MemoryInfo{
			TotalBytes: totalRam,
			Type:       "System RAM",
			Slots:      2,
		},
		GPUs:    gpuInfo,
		Drives:  drives,
		Battery: battery,
	}, nil
}
