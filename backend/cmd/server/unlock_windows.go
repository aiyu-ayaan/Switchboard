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
	"os/exec"

	"golang.org/x/sys/windows"

	"switchboard/backend/internal/system"
)

const unlockUsage = `usage: server unlock <setup|teardown|enroll|disable|service|type>

  setup     enroll the password and register the boot task (run elevated)
  teardown  undo setup: delete the password and the task
  enroll    store this account's Windows password only
  disable   delete the stored password and withdraw the capability
  service   serve the unlock pipe; run as SYSTEM from the scheduled task
  type      type the stored password on the lock screen; spawned by service
`

// unlockTask is the scheduled task that keeps the SYSTEM helper alive. A task
// rather than a service: it needs no SCM plumbing and one schtasks line
// registers it, which is the whole difference in setup cost.
const unlockTask = "Switchboard-Unlock"

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
	case "setup":
		// Both halves in one command, because either alone is a half-working
		// install: a password with no helper cannot be typed, and a helper
		// with no password has nothing to type.
		if err := enroll(); err != nil {
			return true, err
		}
		if err := registerTask(); err != nil {
			return true, err
		}
		fmt.Println("\nRemote unlock is ready.")
		fmt.Println("Open Switchboard on your phone and enrol its fingerprint from Settings.")
		waitForEnter()
		return true, nil

	case "teardown":
		if err := system.DisableUnlock(); err != nil {
			return true, err
		}
		// /f so removing a task that was never created is not an error; the
		// point of teardown is to arrive at "gone" from any starting state.
		_ = exec.Command("schtasks", "/delete", "/tn", unlockTask, "/f").Run()
		fmt.Println("Remote unlock disabled.")
		waitForEnter()
		return true, nil

	case "enroll":
		if err := enroll(); err != nil {
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

// enroll prompts for the Windows password and stores it. The plaintext is
// wiped as soon as it has been sealed, so it lives only for the length of this
// call.
func enroll() error {
	password, err := readPassword()
	if err != nil {
		return err
	}
	defer func() {
		for i := range password {
			password[i] = 0
		}
	}()
	return system.EnrollUnlock(password)
}

// registerTask installs the SYSTEM helper as a boot task and starts it now, so
// setup does not need a reboot to become true.
func registerTask() error {
	exe, err := os.Executable()
	if err != nil {
		return err
	}
	create := exec.Command("schtasks", "/create", "/tn", unlockTask,
		"/ru", "SYSTEM", "/rl", "highest", "/sc", "onstart",
		"/tr", `"`+exe+`" unlock service`, "/f")
	if out, err := create.CombinedOutput(); err != nil {
		return fmt.Errorf("registering %s: %w: %s", unlockTask, err, out)
	}
	if out, err := exec.Command("schtasks", "/run", "/tn", unlockTask).CombinedOutput(); err != nil {
		return fmt.Errorf("starting %s: %w: %s", unlockTask, err, out)
	}
	return nil
}

// waitForEnter keeps the console up after a run launched from the desktop UI,
// which opens its own window; without this the result would flash past.
func waitForEnter() {
	fmt.Print("\nPress Enter to close...")
	fmt.Fscanln(os.Stdin)
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
