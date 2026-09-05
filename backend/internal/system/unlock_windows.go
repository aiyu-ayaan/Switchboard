//go:build windows

package system

// Remote unlock.
//
// Windows exposes no API that unlocks a workstation. Once LockWorkStation has
// run, the console session belongs to Winlogon's secure desktop, and nothing
// in the interactive user's session — the daemon included — is allowed to
// touch it. The only two mechanisms that work are a Credential Provider COM
// DLL loaded by LogonUI, or typing the password on the Winlogon desktop from
// a SYSTEM process. This is the second one.
//
// The daemon stays unprivileged. It verifies the phone's biometric signature
// and then writes a single byte to a named pipe. A SYSTEM-side helper —
// "server.exe unlock service", started at boot by a scheduled task — owns
// everything that needs privilege:
//
//	pipe byte -> spawn "unlock type" as SYSTEM on WinSta0\Winlogon
//	          -> decrypt the enrolled password, SendInput it, press Enter
//
// The password sits in %ProgramData%\Switchboard\unlock.bin under DPAPI
// machine scope, because the helper must decrypt it while no user is logged
// in interactively. Machine scope means any process that can *read* the file
// can decrypt it, so the file's DACL is the real control and is set
// explicitly at enrolment — see writeSecret.
//
// Known ceilings, documented in docs/docs/remote-unlock.md:
//   - Anything running as the console user can write the pipe byte, so it
//     inherits that account's trust rather than adding a new boundary.
//   - Typing only reaches a lock screen that is showing the password field.
//     A PIN-first sign-in has to be switched once, by hand.
//   - The daemon must already be running, so this unlocks a locked session,
//     not a cold boot or a signed-out machine.

import (
	"errors"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"time"
	"unicode/utf16"
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	unlockPipe = `\\.\pipe\switchboard-unlock`

	// cryptLocalMachine is CRYPTPROTECT_LOCAL_MACHINE: key the blob to the
	// machine, not to a user profile, so SYSTEM can open it with no one
	// logged on.
	cryptLocalMachine = 0x4

	// The pipe is created by SYSTEM, whose default DACL would exclude the
	// daemon. Interactive users get read/write so the daemon can knock;
	// nothing weaker would let it, and nothing stronger is possible while the
	// daemon runs unprivileged.
	pipeSDDL = "D:PAI(A;;FA;;;SY)(A;;FA;;;BA)(A;;GRGW;;;IU)"

	// The secret is readable only by SYSTEM and Administrators. PAI drops
	// inherited ACEs from ProgramData, which would otherwise grant Users.
	secretSDDL = "D:PAI(A;;FA;;;SY)(A;;FA;;;BA)"

	// unlockCooldown floors the interval between typing attempts. The pipe is
	// reachable by any code running as the console user, and a tight loop
	// would otherwise hammer keystrokes at the lock screen.
	unlockCooldown = 3 * time.Second

	// lockScreenSettle waits out the dismiss animation. The first keystroke
	// only moves the lock screen aside; the credential field is not focused
	// until it lands.
	lockScreenSettle = 600 * time.Millisecond
)

const (
	vkEscape = 0x1B
	vkReturn = 0x0D

	keyEventUnicode = 0x0004
)

// secretPath is where enrolment stores the DPAPI blob. ProgramData rather
// than the user profile: the helper reads it as SYSTEM.
func secretPath() string {
	dir := os.Getenv("ProgramData")
	if dir == "" {
		dir = `C:\ProgramData`
	}
	return filepath.Join(dir, "Switchboard", "unlock.bin")
}

// unlockSupported reports whether a password has been enrolled. Without one
// there is nothing to type, so the capability is withheld and the phone never
// offers unlock.
func unlockSupported() bool {
	_, err := os.Stat(secretPath())
	return err == nil
}

// unlockSystem asks the SYSTEM helper to unlock the console session. The byte
// carries no authority of its own: the daemon has already checked the phone's
// signature, and the pipe's DACL is what limits who may ask.
func unlockSystem() error {
	pipe, err := os.OpenFile(unlockPipe, os.O_WRONLY, 0)
	if err != nil {
		return fmt.Errorf("system: unlock helper unreachable, is the Switchboard-Unlock task running: %w", err)
	}
	defer pipe.Close()
	if _, err := pipe.Write([]byte{1}); err != nil {
		return fmt.Errorf("system: signalling unlock helper: %w", err)
	}
	return nil
}

// ---- SYSTEM side ----

// RunUnlockService serves the pipe forever. It runs as LocalSystem in session
// 0, where it can do nothing to the desktop itself; every unlock is performed
// by a short-lived child in the console session.
func RunUnlockService() error {
	sd, err := windows.SecurityDescriptorFromString(pipeSDDL)
	if err != nil {
		return fmt.Errorf("unlock: pipe security descriptor: %w", err)
	}
	sa := windows.SecurityAttributes{SecurityDescriptor: sd}
	sa.Length = uint32(unsafe.Sizeof(sa))

	name, err := windows.UTF16PtrFromString(unlockPipe)
	if err != nil {
		return err
	}

	log.Printf("unlock helper listening on %s", unlockPipe)
	var last time.Time
	for {
		// One instance at a time. Unlocking is rare and strictly serial, so
		// concurrency here would only add ways to interleave keystrokes.
		pipe, err := windows.CreateNamedPipe(name,
			windows.PIPE_ACCESS_INBOUND,
			windows.PIPE_TYPE_BYTE|windows.PIPE_WAIT,
			windows.PIPE_UNLIMITED_INSTANCES,
			0, 16, 0, &sa)
		if err != nil {
			return fmt.Errorf("unlock: create pipe: %w", err)
		}

		err = windows.ConnectNamedPipe(pipe, nil)
		// ERROR_PIPE_CONNECTED means the client won the race between create
		// and connect. That is a connection, not a failure.
		if err == nil || errors.Is(err, windows.ERROR_PIPE_CONNECTED) {
			if time.Since(last) < unlockCooldown {
				log.Printf("unlock: request ignored, within cooldown")
			} else {
				last = time.Now()
				if err := spawnTyper(); err != nil {
					log.Printf("unlock: %v", err)
				}
			}
		}
		// Between closing this instance and creating the next there is a
		// moment with no pipe, in which a client gets "file not found". The
		// daemon says so plainly and the user taps again; unlock is a rare,
		// deliberate act with a cooldown in front of it, so keeping a spare
		// instance warm would buy nothing worth the extra state.
		windows.DisconnectNamedPipe(pipe)
		windows.CloseHandle(pipe)
	}
}

// spawnTyper launches the typing pass as SYSTEM inside the console session,
// started directly on the Winlogon desktop.
//
// Session 0 isolation is why this hop exists: a service cannot open a desktop
// belonging to session 1. Duplicating our own SYSTEM token and re-stamping
// its session ID (which needs SeTcbPrivilege, held by SYSTEM) produces a token
// for the same account in the right session, and lpDesktop puts the child on
// the secure desktop from its first instruction.
func spawnTyper() error {
	session := windows.WTSGetActiveConsoleSessionId()
	if session == 0xFFFFFFFF {
		return errors.New("unlock: no console session attached")
	}

	exe, err := os.Executable()
	if err != nil {
		return fmt.Errorf("unlock: locating self: %w", err)
	}

	var self windows.Token
	if err := windows.OpenProcessToken(windows.CurrentProcess(),
		windows.TOKEN_DUPLICATE|windows.TOKEN_QUERY, &self); err != nil {
		return fmt.Errorf("unlock: open own token: %w", err)
	}
	defer self.Close()

	var dup windows.Token
	if err := windows.DuplicateTokenEx(self, windows.MAXIMUM_ALLOWED, nil,
		windows.SecurityIdentification, windows.TokenPrimary, &dup); err != nil {
		return fmt.Errorf("unlock: duplicate token: %w", err)
	}
	defer dup.Close()

	if err := windows.SetTokenInformation(dup, uint32(windows.TokenSessionId),
		(*byte)(unsafe.Pointer(&session)), uint32(unsafe.Sizeof(session))); err != nil {
		return fmt.Errorf("unlock: retarget token to session %d: %w", session, err)
	}

	desktop, err := windows.UTF16PtrFromString(`WinSta0\Winlogon`)
	if err != nil {
		return err
	}
	cmd, err := windows.UTF16PtrFromString(`"` + exe + `" unlock type`)
	if err != nil {
		return err
	}

	si := windows.StartupInfo{Desktop: desktop}
	si.Cb = uint32(unsafe.Sizeof(si))
	var pi windows.ProcessInformation
	if err := windows.CreateProcessAsUser(dup, nil, cmd, nil, nil, false,
		windows.CREATE_NO_WINDOW, nil, nil, &si, &pi); err != nil {
		return fmt.Errorf("unlock: spawn on Winlogon desktop: %w", err)
	}
	windows.CloseHandle(pi.Thread)
	windows.CloseHandle(pi.Process)
	return nil
}

// RunUnlockType types the enrolled password into the lock screen. It runs as
// SYSTEM on the Winlogon desktop, launched by spawnTyper, and exits
// immediately afterwards so the plaintext lives for as short a time as
// possible.
func RunUnlockType() error {
	secret, err := loadSecret()
	if err != nil {
		return err
	}
	defer func() {
		for i := range secret {
			secret[i] = 0
		}
	}()

	// The lock screen opens on the clock, not the credential field. A single
	// dismiss keystroke brings the field up; Escape is used because it types
	// nothing if the field happens to be focused already.
	if err := tap(vkEscape); err != nil {
		return err
	}
	time.Sleep(lockScreenSettle)

	// KEYEVENTF_UNICODE carries UTF-16 code units, so encode rather than
	// ranging over runes: a password outside the BMP needs both surrogates.
	for _, unit := range utf16.Encode([]rune(string(secret))) {
		if err := sendInputs(newUnicodeInput(unit, false), newUnicodeInput(unit, true)); err != nil {
			return fmt.Errorf("unlock: typing: %w", err)
		}
	}
	return tap(vkReturn)
}

func tap(vk uint16) error {
	return sendInputs(newKeyInput(vk, false), newKeyInput(vk, true))
}

// newUnicodeInput builds a synthetic keystroke for one UTF-16 code unit,
// bypassing the keyboard layout entirely. A password containing characters
// the active layout cannot produce would otherwise be untypable.
func newUnicodeInput(unit uint16, up bool) rawInput {
	in := rawInput{kind: inputKeyboard}
	k := (*keyboardInput)(unsafe.Pointer(&in.data[0]))
	k.scan = unit
	k.flags = keyEventUnicode
	if up {
		k.flags |= keyEventKeyUp
	}
	return in
}

// ---- Enrolment ----

// EnrollUnlock stores password under machine-scope DPAPI. It is called by
// "server.exe unlock enroll" from an elevated prompt, which is the one moment
// the password is handled at all.
func EnrollUnlock(password []byte) error {
	if len(password) == 0 {
		return errors.New("unlock: empty password")
	}
	in := windows.DataBlob{Size: uint32(len(password)), Data: &password[0]}
	var out windows.DataBlob
	if err := windows.CryptProtectData(&in, nil, nil, 0, nil, cryptLocalMachine, &out); err != nil {
		return fmt.Errorf("unlock: CryptProtectData: %w", err)
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))

	blob := make([]byte, out.Size)
	copy(blob, unsafe.Slice(out.Data, out.Size))
	return writeSecret(blob)
}

// DisableUnlock removes the enrolled password, which drops the "unlock"
// capability and is the host-side half of forgetting a device.
func DisableUnlock() error {
	err := os.Remove(secretPath())
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	return err
}

func writeSecret(blob []byte) error {
	path := secretPath()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	if err := os.WriteFile(path, blob, 0o600); err != nil {
		return err
	}

	// Go's permission bits do not become a Windows DACL, and ProgramData
	// grants Users write by inheritance. A machine-scope blob is decryptable
	// by anyone who can read it, so the ACL below is the control that keeps a
	// standard account from lifting the password — without it the DPAPI layer
	// buys nothing.
	sd, err := windows.SecurityDescriptorFromString(secretSDDL)
	if err != nil {
		return fmt.Errorf("unlock: secret security descriptor: %w", err)
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		return fmt.Errorf("unlock: secret DACL: %w", err)
	}
	if err := windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT,
		windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
		nil, nil, dacl, nil); err != nil {
		// Leaving a world-readable password blob behind would be worse than
		// failing enrolment.
		os.Remove(path)
		return fmt.Errorf("unlock: restricting secret to SYSTEM: %w", err)
	}
	return nil
}

func loadSecret() ([]byte, error) {
	blob, err := os.ReadFile(secretPath())
	if err != nil {
		return nil, fmt.Errorf("unlock: no enrolled password: %w", err)
	}
	if len(blob) == 0 {
		return nil, errors.New("unlock: enrolled password is empty")
	}
	in := windows.DataBlob{Size: uint32(len(blob)), Data: &blob[0]}
	var out windows.DataBlob
	if err := windows.CryptUnprotectData(&in, nil, nil, 0, nil, cryptLocalMachine, &out); err != nil {
		return nil, fmt.Errorf("unlock: CryptUnprotectData: %w", err)
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(out.Data)))

	secret := make([]byte, out.Size)
	copy(secret, unsafe.Slice(out.Data, out.Size))
	return secret, nil
}
