//go:build windows

package system

import (
	"fmt"
	"strings"
	"sync"
	"unsafe"

	"github.com/go-ole/go-ole"
	"github.com/go-ole/go-ole/oleutil"
	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"

	"switchboard/backend/internal/protocol"
)

// Windows display control has two distinct backends, exactly as PowerToys'
// Display module does:
//
//   - External monitors speak DDC/CI over the I2C bus. dxva2.dll exposes the
//     VCP brightness (0x10) and contrast (0x12) codes, including each panel's
//     own minimum and maximum, which are frequently not 0-100.
//   - Internal laptop panels have no DDC/CI bus. They are driven through the
//     WmiMonitorBrightnessMethods WMI class and expose brightness only, which
//     is why the built-in display in the reference UI has a single slider.
var (
	user32 = windows.NewLazySystemDLL("user32.dll")
	dxva2  = windows.NewLazySystemDLL("dxva2.dll")

	procEnumDisplayMonitors    = user32.NewProc("EnumDisplayMonitors")
	procGetMonitorInfoW        = user32.NewProc("GetMonitorInfoW")
	procEnumDisplayDevicesW    = user32.NewProc("EnumDisplayDevicesW")
	procGetNumPhysicalMonitors = dxva2.NewProc("GetNumberOfPhysicalMonitorsFromHMONITOR")
	procGetPhysicalMonitors    = dxva2.NewProc("GetPhysicalMonitorsFromHMONITOR")
	procDestroyPhysicalMonitor = dxva2.NewProc("DestroyPhysicalMonitor")
	procGetMonitorBrightness   = dxva2.NewProc("GetMonitorBrightness")
	procSetMonitorBrightness   = dxva2.NewProc("SetMonitorBrightness")
	procGetMonitorContrast     = dxva2.NewProc("GetMonitorContrast")
	procSetMonitorContrast     = dxva2.NewProc("SetMonitorContrast")
)

const (
	physicalMonitorDescriptionSize = 128
	eddGetDeviceInterfaceName      = 0x00000001
	displayDeviceActive            = 0x00000001
)

type physicalMonitor struct {
	handle      windows.Handle
	description [physicalMonitorDescriptionSize]uint16
}

type monitorInfoEx struct {
	cbSize    uint32
	rcMonitor windows.Rect
	rcWork    windows.Rect
	dwFlags   uint32
	szDevice  [32]uint16
}

type displayDevice struct {
	cb           uint32
	deviceName   [32]uint16
	deviceString [128]uint16
	stateFlags   uint32
	deviceID     [128]uint16
	deviceKey    [128]uint16
}

// panel is one controllable display plus whatever handle drives it.
type panel struct {
	info protocol.Display

	// external panels only
	handle windows.Handle
	// internal panels only: the WMI instance name to address.
	wmiInstance string
}

// displayController caches the enumerated panels. Opening DDC/CI handles costs
// tens of milliseconds each, and a brightness drag issues many writes per
// second, so handles stay open until the display topology changes.
type displayController struct {
	mu     sync.Mutex
	panels []*panel
	loaded bool
}

func newDisplayController() *displayController { return &displayController{} }

// List returns the current panels, enumerating on first use.
func (c *displayController) List() ([]protocol.Display, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if !c.loaded {
		if err := c.reload(); err != nil {
			return nil, err
		}
	}
	out := make([]protocol.Display, 0, len(c.panels))
	for _, p := range c.panels {
		out = append(out, p.info)
	}
	return out, nil
}

// Refresh drops cached handles and re-enumerates. Call after a display
// hotplug; stale HMONITOR handles otherwise fail every write.
func (c *displayController) Refresh() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.reload()
}

func (c *displayController) reload() error {
	c.release()
	panels, err := enumeratePanels()
	if err != nil {
		return err
	}
	c.panels = panels
	c.loaded = true
	return nil
}

func (c *displayController) release() {
	for _, p := range c.panels {
		if p.handle != 0 {
			procDestroyPhysicalMonitor.Call(uintptr(p.handle))
		}
	}
	c.panels = nil
}

// Close releases every DDC/CI handle.
func (c *displayController) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.release()
	c.loaded = false
}

func (c *displayController) find(id string) (*panel, error) {
	if !c.loaded {
		if err := c.reload(); err != nil {
			return nil, err
		}
	}
	for _, p := range c.panels {
		if p.info.ID == id {
			return p, nil
		}
	}
	return nil, fmt.Errorf("display %q not found", id)
}

// SetBrightness clamps to the panel's reported capability range and writes it.
func (c *displayController) SetBrightness(id string, value int) (protocol.Display, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	p, err := c.find(id)
	if err != nil {
		return protocol.Display{}, err
	}
	v := clamp(value, p.info.MinBright, p.info.MaxBright)

	if p.info.Internal {
		if err := setInternalBrightness(p.wmiInstance, v); err != nil {
			return protocol.Display{}, err
		}
	} else {
		ok, _, callErr := procSetMonitorBrightness.Call(uintptr(p.handle), uintptr(uint32(v)))
		if ok == 0 {
			return protocol.Display{}, fmt.Errorf("set brightness on %s: %w", p.info.Name, callErr)
		}
	}
	p.info.Brightness = v
	return p.info, nil
}

// SetContrast writes VCP 0x12. Internal panels have no contrast control.
func (c *displayController) SetContrast(id string, value int) (protocol.Display, error) {
	c.mu.Lock()
	defer c.mu.Unlock()

	p, err := c.find(id)
	if err != nil {
		return protocol.Display{}, err
	}
	if !p.info.HasContrast {
		return protocol.Display{}, fmt.Errorf("display %q has no contrast control", p.info.Name)
	}
	v := clamp(value, p.info.MinContrast, p.info.MaxContrast)
	ok, _, callErr := procSetMonitorContrast.Call(uintptr(p.handle), uintptr(uint32(v)))
	if ok == 0 {
		return protocol.Display{}, fmt.Errorf("set contrast on %s: %w", p.info.Name, callErr)
	}
	p.info.Contrast = v
	return p.info, nil
}

// enumeratePanels walks every HMONITOR and classifies it as DDC/CI-capable or
// an internal panel driven through WMI.
func enumeratePanels() ([]*panel, error) {
	var (
		handles []windows.Handle
		devices []string
	)
	callback := windows.NewCallback(func(hMonitor windows.Handle, _ windows.Handle, _ uintptr, _ uintptr) uintptr {
		info := monitorInfoEx{cbSize: uint32(unsafe.Sizeof(monitorInfoEx{}))}
		if ok, _, _ := procGetMonitorInfoW.Call(uintptr(hMonitor), uintptr(unsafe.Pointer(&info))); ok != 0 {
			handles = append(handles, hMonitor)
			devices = append(devices, windows.UTF16ToString(info.szDevice[:]))
		}
		return 1 // continue enumeration
	})
	if ok, _, err := procEnumDisplayMonitors.Call(0, 0, callback, 0); ok == 0 {
		return nil, fmt.Errorf("EnumDisplayMonitors: %w", err)
	}

	internal := internalPanels()
	usedInternal := 0

	panels := make([]*panel, 0, len(handles))
	for i, hMonitor := range handles {
		friendly := friendlyName(devices[i])

		if p := externalPanel(hMonitor, devices[i], friendly); p != nil {
			panels = append(panels, p)
			continue
		}
		// No DDC/CI: treat as an internal panel if WMI reported one we have
		// not already claimed.
		if usedInternal < len(internal) {
			p := internal[usedInternal]
			usedInternal++
			p.info.ID = devices[i]
			if friendly != "" {
				p.info.Name = friendly
			}
			panels = append(panels, p)
		}
	}
	return panels, nil
}

// externalPanel opens DDC/CI for one HMONITOR, returning nil when the panel
// does not support it (internal laptop screens, some docks and KVMs).
func externalPanel(hMonitor windows.Handle, device, friendly string) *panel {
	var count uint32
	if ok, _, _ := procGetNumPhysicalMonitors.Call(uintptr(hMonitor), uintptr(unsafe.Pointer(&count))); ok == 0 || count == 0 {
		return nil
	}
	mons := make([]physicalMonitor, count)
	if ok, _, _ := procGetPhysicalMonitors.Call(uintptr(hMonitor), uintptr(count), uintptr(unsafe.Pointer(&mons[0]))); ok == 0 {
		return nil
	}
	// Only the first physical monitor behind an HMONITOR is addressable as a
	// distinct panel here; release any others immediately.
	for i := 1; i < len(mons); i++ {
		procDestroyPhysicalMonitor.Call(uintptr(mons[i].handle))
	}
	h := mons[0].handle

	var minB, curB, maxB uint32
	ok, _, _ := procGetMonitorBrightness.Call(uintptr(h),
		uintptr(unsafe.Pointer(&minB)), uintptr(unsafe.Pointer(&curB)), uintptr(unsafe.Pointer(&maxB)))
	if ok == 0 {
		procDestroyPhysicalMonitor.Call(uintptr(h))
		return nil
	}

	name := friendly
	if name == "" {
		name = windows.UTF16ToString(mons[0].description[:])
	}

	p := &panel{
		handle: h,
		info: protocol.Display{
			ID:         device,
			Name:       name,
			Brightness: int(curB),
			MinBright:  int(minB),
			MaxBright:  int(maxB),
		},
	}

	var minC, curC, maxC uint32
	okC, _, _ := procGetMonitorContrast.Call(uintptr(h),
		uintptr(unsafe.Pointer(&minC)), uintptr(unsafe.Pointer(&curC)), uintptr(unsafe.Pointer(&maxC)))
	if okC != 0 {
		p.info.HasContrast = true
		p.info.Contrast = int(curC)
		p.info.MinContrast = int(minC)
		p.info.MaxContrast = int(maxC)
	}
	return p
}

// friendlyName resolves "\\.\DISPLAY1" to the model name burned into the
// panel's EDID, so the UI shows "EK240Y P6" rather than "Generic PnP Monitor".
// Returns "" when the EDID cannot be read, and callers fall back.
func friendlyName(deviceName string) string {
	dev := displayDevice{}
	dev.cb = uint32(unsafe.Sizeof(dev))
	namePtr, err := windows.UTF16PtrFromString(deviceName)
	if err != nil {
		return ""
	}
	ok, _, _ := procEnumDisplayDevicesW.Call(
		uintptr(unsafe.Pointer(namePtr)), 0, uintptr(unsafe.Pointer(&dev)), eddGetDeviceInterfaceName)
	if ok == 0 {
		return ""
	}

	// DeviceID looks like \\?\DISPLAY#ACR0761#5&1234abcd&0&UID256#{guid}
	id := windows.UTF16ToString(dev.deviceID[:])
	parts := strings.Split(id, "#")
	if len(parts) < 3 {
		return ""
	}
	key := fmt.Sprintf(`SYSTEM\CurrentControlSet\Enum\DISPLAY\%s\%s\Device Parameters`, parts[1], parts[2])
	k, err := registry.OpenKey(registry.LOCAL_MACHINE, key, registry.QUERY_VALUE)
	if err != nil {
		return ""
	}
	defer k.Close()

	edid, _, err := k.GetBinaryValue("EDID")
	if err != nil {
		return ""
	}
	return edidMonitorName(edid)
}

// edidMonitorName extracts the descriptor tagged 0xFC (monitor name) from a
// 128-byte EDID block. The four 18-byte descriptors start at offset 54.
func edidMonitorName(edid []byte) string {
	const (
		firstDescriptor = 54
		descriptorSize  = 18
		tagMonitorName  = 0xFC
	)
	for i := 0; i < 4; i++ {
		off := firstDescriptor + i*descriptorSize
		if off+descriptorSize > len(edid) {
			return ""
		}
		d := edid[off : off+descriptorSize]
		// A display descriptor has a zero pixel clock in the first two bytes.
		if d[0] != 0 || d[1] != 0 || d[3] != tagMonitorName {
			continue
		}
		name := strings.TrimSpace(strings.SplitN(string(d[5:]), "\n", 2)[0])
		return strings.TrimRight(name, "\x00 ")
	}
	return ""
}

// internalPanels queries WMI for laptop panels. Values come back on a 0-100
// scale defined by the driver's supported-levels table.
//
// ponytail: shells out to PowerShell rather than binding WMI through COM.
// Internal panels are read on enumeration and written when the built-in
// display slider moves. Both go through WMI's COM automation objects directly;
// the brightness range stays 0-100 rather than the panel's own Level[] array,
// which is what the WMI class reports and what the sliders have always shown.
func internalPanels() []*panel {
	var panels []*panel
	err := withWMI(`root\wmi`, func(service *ole.IDispatch) error {
		return wmiQuery(service,
			"SELECT InstanceName, CurrentBrightness FROM WmiMonitorBrightness",
			func(instance *ole.IDispatch) error {
				name := wmiString(instance, "InstanceName")
				level, ok := wmiInt(instance, "CurrentBrightness")
				if name == "" || !ok {
					return nil
				}
				panels = append(panels, &panel{
					wmiInstance: name,
					info: protocol.Display{
						Name:       "Built-in display",
						Internal:   true,
						Brightness: level,
						MinBright:  0,
						MaxBright:  100,
					},
				})
				return nil
			})
	})
	if err != nil {
		return nil
	}
	return panels
}

func setInternalBrightness(instance string, value int) error {
	return withWMI(`root\wmi`, func(service *ole.IDispatch) error {
		found := false
		// The instance is matched here rather than in a WHERE clause: WQL
		// treats backslash as an escape character and an InstanceName is full
		// of them, so the filter would have to be escaped correctly to be
		// safe. A machine has one or two internal panels, so the scan is free.
		err := wmiQuery(service, "SELECT * FROM WmiMonitorBrightnessMethods",
			func(obj *ole.IDispatch) error {
				if wmiString(obj, "InstanceName") != instance {
					return nil
				}
				found = true
				// WmiSetBrightness(uint64 Timeout, uint8 Brightness); a
				// timeout of 0 applies immediately. Both arguments are passed
				// as Go ints so go-ole marshals them VT_I4 and automation
				// coerces to the declared widths — go-ole v1.2.6 marshals a
				// Go uint8 as the signed VT_I1, which is not what this method
				// declares.
				raw, err := oleutil.CallMethod(obj, "WmiSetBrightness", 0, value)
				if err != nil {
					return fmt.Errorf("wmi: WmiSetBrightness(%d): %w", value, err)
				}
				raw.Clear()
				return nil
			})
		if err != nil {
			return err
		}
		if !found {
			return fmt.Errorf("wmi: no brightness method for instance %q", instance)
		}
		return nil
	})
}
