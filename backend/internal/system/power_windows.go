//go:build windows

package system

import (
	"fmt"
	"os/exec"
	"strconv"
	"syscall"

	"golang.org/x/sys/windows"

	"switchboard/backend/internal/protocol"
)

var (
	procSendMessageW = user32.NewProc("SendMessageW")

	powrprof            = windows.NewLazySystemDLL("powrprof.dll")
	procSetSuspendState = powrprof.NewProc("SetSuspendState")
)

const (
	hwndBroadcast   = 0xffff
	wmSysCommand    = 0x0112
	scMonitorPower  = 0xF170
	monitorPowerOff = 2
)

func powerSupported() bool {
	return true
}

func powerAction(action string, seconds int) error {
	switch action {
	case protocol.PowerDisplayOff:
		procSendMessageW.Call(
			uintptr(hwndBroadcast),
			uintptr(wmSysCommand),
			uintptr(scMonitorPower),
			uintptr(monitorPowerOff),
		)
		return nil

	case protocol.PowerSleep:
		r1, _, err := procSetSuspendState.Call(0, 0, 0)
		if r1 == 0 {
			return fmt.Errorf("system: SetSuspendState failed: %w", err)
		}
		return nil

	case protocol.PowerShutdown:
		if seconds < 0 {
			seconds = 0
		}
		cmd := exec.Command("shutdown.exe", "/s", "/f", "/t", strconv.Itoa(seconds))
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
		if err := cmd.Run(); err != nil {
			return fmt.Errorf("system: shutdown failed: %w", err)
		}
		return nil

	case protocol.PowerAbortShutdown:
		cmd := exec.Command("shutdown.exe", "/a")
		cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
		if err := cmd.Run(); err != nil {
			return fmt.Errorf("system: abort shutdown failed: %w", err)
		}
		return nil

	default:
		return fmt.Errorf("system: unknown power action %q", action)
	}
}
