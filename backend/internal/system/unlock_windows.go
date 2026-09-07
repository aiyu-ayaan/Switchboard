//go:build windows

package system

// Remote unlock.
//
// Windows exposes no API that unlocks a workstation. Once LockWorkStation has
// run, the console session belongs to Winlogon's secure desktop, and nothing
// in the interactive user's session — the daemon included — is allowed to
// touch it. The only two mechanisms that work are a Credential Provider COM
// DLL loaded by LogonUI, or typing the password on the Winlogon desktop from
// a SYSTEM process. This is the first one.
//
// Typing was tried and abandoned. It only ever reached a lock screen that
// happened to be showing the password field, so a machine that signs in with
// a PIN or Hello sent the password into whatever box had focus and had it
// rejected — with the user's only recourse being to switch their sign-in
// method by hand. A credential provider does not care which tile was last
// used, because LogonUI submits ours.
//
// The daemon stays unprivileged. It verifies the phone's biometric signature
// and then sets one event:
//
//	SetEvent(Local\Switchboard-Unlock)
//	  -> LogonUI's copy of switchboard_cp.dll wakes, and offers one tile
//	  -> LogonUI auto-submits it; the DLL decrypts the password and hands
//	     LSA a KERB_INTERACTIVE_UNLOCK_LOGON
//
// The password sits in %ProgramData%\Switchboard\unlock.bin under DPAPI
// machine scope, because the DLL must decrypt it inside LogonUI, where the
// interactive user's DPAPI keys are not available. Machine scope means any
// process that can *read* the file can decrypt it, so the file's DACL is the
// real control and is set explicitly at enrolment — see writeSecret.
//
// Known ceilings, documented in docs/docs/remote-unlock.md:
//   - Anything running as the console user can set the event, so it inherits
//     that account's trust rather than adding a new boundary.
//   - The daemon must already be running, so this unlocks a locked session,
//     not a cold boot or a signed-out machine.

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	// unlockEvent must match SWITCHBOARD_UNLOCK_EVENT in the DLL.
	//
	// Local\ rather than Global\: LogonUI runs in the console session
	// alongside the daemon, so the local namespace names the same object for
	// both, and creating a global one needs a privilege the daemon does not
	// have.
	unlockEvent = `Local\Switchboard-Unlock`

	// cryptLocalMachine is CRYPTPROTECT_LOCAL_MACHINE: key the blob to the
	// machine, not to a user profile, so LogonUI can open it with no one
	// logged on.
	cryptLocalMachine = 0x4

	// The secret is readable only by SYSTEM and Administrators. PAI drops
	// inherited ACEs from ProgramData, which would otherwise grant Users.
	secretSDDL = "D:PAI(A;;FA;;;SY)(A;;FA;;;BA)"
)

// secretPath is where enrolment stores the DPAPI blob. ProgramData rather
// than the user profile: the DLL reads it as SYSTEM.
func secretPath() string {
	dir := os.Getenv("ProgramData")
	if dir == "" {
		dir = `C:\ProgramData`
	}
	return filepath.Join(dir, "Switchboard", "unlock.bin")
}

// unlockSupported reports whether a password has been enrolled. Without one
// there is nothing to submit, so the capability is withheld and the phone
// never offers unlock.
func unlockSupported() bool {
	_, err := os.Stat(secretPath())
	return err == nil
}

// unlockSystem asks the credential provider to submit the stored password.
// The event carries no authority of its own: the daemon has already checked
// the phone's signature, and the event's DACL is what limits who may ask.
func unlockSystem() error {
	name, err := windows.UTF16PtrFromString(unlockEvent)
	if err != nil {
		return err
	}
	// EVENT_MODIFY_STATE is all SetEvent needs, and all the DACL grants the
	// interactive user. Opening rather than creating is deliberate: the event
	// exists only while LogonUI has the provider loaded, so "not found" is the
	// honest answer to an unlock aimed at a machine that is not locked.
	handle, err := windows.OpenEvent(windows.EVENT_MODIFY_STATE, false, name)
	if err != nil {
		return fmt.Errorf("system: unlock provider not listening, is it registered and the desktop locked: %w", err)
	}
	defer windows.CloseHandle(handle)

	if err := windows.SetEvent(handle); err != nil {
		return fmt.Errorf("system: signalling unlock provider: %w", err)
	}
	return nil
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
