# DDC/CI Multi-Monitor Hardware Control

This document explains how Switchboard communicates with physical computer monitors to adjust hardware backlight and contrast levels using **VESA DDC/CI** and Windows **WMI**.

---

## 💡 What is DDC/CI?

**Display Data Channel / Command Interface (DDC/CI)** is a VESA standard that allows personal computers to send bidirectional digital commands directly to a computer monitor's internal microcontroller over the display cable (DisplayPort, HDMI, or USB-C).

Unlike software "screen dimmers" that merely apply a translucent dark gray alpha layer over the operating system desktop (which crushes dynamic range and leaves the physical backlight glowing), DDC/CI commands instruct the monitor's internal hardware inverter or LED driver to reduce physical luminous output.

---

## 🖥️ External Display Control via `dxva2.dll`

On Windows, Switchboard controls external monitors using the native DirectX Video Acceleration / High-Level Monitor Configuration APIs exposed by `dxva2.dll`.

### Architecture Flow
```
EnumDisplayMonitors (user32.dll)
       │
       ▼ HMONITOR handles
GetPhysicalMonitorsFromHMONITOR (dxva2.dll)
       │
       ▼ HANDLE hPhysicalMonitor
GetMonitorCapabilities / GetMonitorBrightness / GetMonitorContrast
       │
       ▼ (Read hardware min, current, and max levels)
SetMonitorBrightness / SetMonitorContrast
       │
       ▼ I2C bus write over DP/HDMI pins
Monitor Microcontroller adjusts physical LED backlight
```

### Key Native Functions
1. **`EnumDisplayMonitors`**: Iterates through virtual desktop display coordinates and identifies attached monitors.
2. **`GetPhysicalMonitorsFromHMONITOR`**: Bridges the GDI `HMONITOR` handle to one or more physical monitor handles (`HANDLE`).
3. **`GetMonitorBrightness(hMonitor, &min, &current, &max)`**: Queries the display's supported brightness scale.
4. **`SetMonitorBrightness(hMonitor, value)`**: Writes the target brightness value directly to the display microcontroller.
5. **`GetMonitorContrast` / `SetMonitorContrast`**: Controls hardware contrast ratio if supported by the display firmware.
6. **`DestroyPhysicalMonitors`**: Safely releases monitor handles during teardown or rescan.

---

## 💻 Internal Laptop Displays via WMI

Internal displays (e.g., laptop screens or all-in-one PCs) are connected directly via internal eDP (embedded DisplayPort) or LVDS without an external I2C DDC/CI bus.

### WMI Interface
For internal displays, Switchboard detects `internal: true` and routes commands through **Windows Management Instrumentation (WMI)**:
- **Namespace**: `root\wmi`
- **Class**: `WmiMonitorBrightness` (for reading current levels)
- **Method**: `WmiMonitorBrightnessMethods.WmiSetBrightness(Timeout, Target)`

Internal displays do not expose hardware contrast controls; Switchboard automatically flags `hasContrast: false` so that the mobile and desktop user interfaces cleanly omit redundant sliders.

---

## ⏱️ Hardware Safety & Write Debouncing

Physical monitor microcontrollers operate on low-frequency I2C buses (often 100 kHz) and process commands using modest microprocessors. Rapidly firing hundreds of raw I2C packets while a user drags a touch slider can overwhelm monitor firmware, resulting in dropped frames or temporary OSD lockups.

Switchboard incorporates two defensive layers:
1. **UI Debouncing & Throttling**: The mobile touch slider updates its local visual UI at 60/120 FPS, but dispatches network updates to the daemon at a throttled rate (approx. 50ms intervals).
2. **Sequential Hardware Queue**: The Go backend serializes write requests per monitor handle, ensuring each write completes before the next is submitted.
