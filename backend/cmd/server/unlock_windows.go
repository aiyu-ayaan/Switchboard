//go:build windows

package main

// The "unlock" subcommands are the privileged half of remote unlock. They ship
// inside server.exe rather than as a second binary so packaging, signing and
// upgrades stay a one-file affair; see internal/system/unlock_windows.go for
// how the pieces fit together.

import (
	"errors"
	"fmt"
	"os"

	"golang.org/x/sys/windows"

	"switchboard/backend/internal/system"
)

const unlockUsage = `usage: server unlock <enroll|disable|service|type>

  enroll   store this account's Windows password for remote unlock (run elevated)
  disable  delete the stored password and withdraw the capability
  service  serve the unlock pipe; run as SYSTEM from the scheduled task
  type     type the stored password on the lock screen; spawned by service
`

// runUnlock dispatches "server unlock ...". It returns false when the command
// line is not an unlock invocation, leaving the daemon to start normally.
func runUnlock(args []string) (bool, error) {
	if len(args) == 0 || args[0] != "unlock" {
		return false, nil
	}
	if len(args) < 2 {
		return true, errors.New(unlockUsage)
	}

	switch args[1] {
	case "enroll":
		password, err := readPassword()
		if err != nil {
			return true, err
		}
		defer func() {
			for i := range password {
				password[i] = 0
			}
		}()
		if err := system.EnrollUnlock(password); err != nil {
			return true, err
		}
		fmt.Println("Enrolled. Pair the phone, then enrol its fingerprint from the app.")
		return true, nil

	case "disable":
		if err := system.DisableUnlock(); err != nil {
			return true, err
		}
		fmt.Println("Remote unlock disabled.")
		return true, nil

	case "service":
		return true, system.RunUnlockService()

	case "type":
		return true, system.RunUnlockType()
	}
	return true, fmt.Errorf("unknown unlock command %q\n\n%s", args[1], unlockUsage)
}

// readPassword reads one line from the console with echo turned off, so the
// password never reaches the scrollback of the elevated prompt it is typed at.
func readPassword() ([]byte, error) {
	fmt.Print("Windows password for this account: ")
	defer fmt.Println()

	handle := windows.Handle(os.Stdin.Fd())
	var mode uint32
	if err := windows.GetConsoleMode(handle, &mode); err != nil {
		return nil, fmt.Errorf("unlock: enrolment needs an interactive console: %w", err)
	}
	if err := windows.SetConsoleMode(handle, mode&^windows.ENABLE_ECHO_INPUT); err != nil {
		return nil, err
	}
	defer windows.SetConsoleMode(handle, mode)

	// Read a byte at a time: bufio would buffer past the newline and leave the
	// rest of the password sitting in a heap buffer nothing wipes.
	var password []byte
	buf := make([]byte, 1)
	for {
		n, err := os.Stdin.Read(buf)
		if err != nil || n == 0 {
			break
		}
		if buf[0] == '\r' || buf[0] == '\n' {
			break
		}
		password = append(password, buf[0])
	}
	if len(password) == 0 {
		return nil, errors.New("unlock: no password entered")
	}
	return password, nil
}
