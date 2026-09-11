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

WMI is reached through its COM automation objects from inside the daemon, not by running a PowerShell command and parsing the output. Both are the same WMI call underneath, but starting a shell costs roughly half a second, and it is the whole reason the built-in slider used to feel slower than the external ones.

Internal displays do not expose hardware contrast controls; Switchboard automatically flags `hasContrast: false` so that the mobile and desktop user interfaces cleanly omit redundant sliders.

---

## ⏱️ Hardware Safety & Write Debouncing

Physical monitor microcontrollers operate on low-frequency I2C buses (often 100 kHz) and process commands using modest microprocessors. Rapidly firing hundreds of raw I2C packets while a user drags a touch slider can overwhelm monitor firmware, resulting in dropped frames or temporary OSD lockups.

Switchboard incorporates two defensive layers:
1. **UI Debouncing & Throttling**: The mobile touch slider updates its local visual UI at 60/120 FPS, but dispatches network updates to the daemon at a throttled rate (approx. 50ms intervals).
2. **Sequential Hardware Queue**: The Go backend serializes write requests per monitor handle, ensuring each write completes before the next is submitted.

---

## 🔄 Recovering from a Display Mode Change

Monitor handles are not permanent. Anything that puts the display through a mode change invalidates every `HMONITOR` and the physical monitor handles opened from it:

- A game (or any application) entering exclusive fullscreen
- A resolution or refresh-rate switch
- Toggling HDR
- A dock or KVM handing the panel to another input
- Waking from sleep, or a monitor being powered off and on

The invalidated handles remain non-zero, so nothing about them *looks* wrong — but `SetMonitorBrightness` and `SetMonitorContrast` refuse every subsequent write. Left unhandled, this means the brightness slider stops working after a gaming session and does not recover until the daemon restarts.

Switchboard treats the enumerated panel list as a **cache that heals itself**. Every brightness and contrast write goes through a single shared path that, on failure, discards the cached handles, re-enumerates the attached displays, and retries the write once against fresh handles.

Because the retry lives in that shared path rather than in any one caller, it covers every way a write can arrive — the mobile sliders, the desktop UI, and the Switchboard Deck's brightness keys — and a display that is genuinely unreachable (powered off, cable pulled) still reports a real error rather than failing silently.

---

## ⚡ Per-Display Hardware Power & Standby Control (VCP 0xD6)

Unlike global system sleep commands or OS-level `SC_MONITORPOWER` broadcasts that cut video signal to every monitor at once, Switchboard supports **per-display power management**, allowing users to sleep or wake individual panels independently.

### External Displays (DDC/CI VCP 0xD6)
For external monitors, Switchboard drives the low-level VESA MCCS Power Mode opcode (`0xD6`) via `dxva2.dll!SetVCPFeature`:

| VCP 0xD6 Value | DPMS Power Mode | Action |
| :--- | :--- | :--- |
| `0x01` | **D0 / On** | Fully operational panel and active backlight. |
| `0x04` | **D3 / Off (Standby)** | Power-down backlight and put display microcontroller into low-power sleep. |

When a specific external panel is toggled off from the mobile Displays pod or desktop Displays view, only that physical monitor enters standby (indicated by an amber/standby LED). The remaining displays on the workstation remain active and unaffected. Toggling it back on issues VCP `0x01` to instantly restore normal operation.

### Internal Laptop Panels (Backlight Toggle)
Internal eDP laptop displays lack an external DDC/CI I2C bus. For internal panels, Switchboard simulates standby by saving the user's active brightness level and dropping the panel to `0%` minimum brightness via direct WMI COM automation (`WmiSetBrightness`). Toggling the panel on seamlessly restores the previously saved brightness level.

