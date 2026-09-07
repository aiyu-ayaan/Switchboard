//go:build windows

package system

// Registration of the unlock credential provider.
//
// The DLL is a COM in-proc server, so LogonUI finds it the way COM finds
// anything: a CLSID under HKLM\SOFTWARE\Classes plus an entry in the
// credential-provider list. Both are HKLM, which is why setup has to run
// elevated. There is no DllRegisterServer to call — three registry values are
// the whole of it, and writing them here keeps the DLL free of registration
// code that would only ever run under regsvr32.

import (
	"fmt"
	"os"
	"path/filepath"

	"golang.org/x/sys/windows/registry"
)

const (
	// Must match CLSID_SwitchboardProvider in native/credprovider/dllmain.cpp.
	credProviderCLSID = "{599BA444-2560-4520-AB9E-A68E52826FEE}"

	credProviderDLL = "switchboard_cp.dll"

	clsidKey    = `SOFTWARE\Classes\CLSID\` + credProviderCLSID
	inprocKey   = clsidKey + `\InprocServer32`
	providerKey = `SOFTWARE\Microsoft\Windows\CurrentVersion\Authentication\Credential Providers\` + credProviderCLSID
)

// credProviderPath is the DLL beside server.exe. Keeping them together means
// an install that moved is an install that re-registers, rather than one that
// silently points LogonUI at a stale copy.
func credProviderPath() (string, error) {
	exe, err := os.Executable()
	if err != nil {
		return "", err
	}
	return filepath.Join(filepath.Dir(exe), credProviderDLL), nil
}

// CredProviderInstalled reports whether the DLL is present to register.
func CredProviderInstalled() bool {
	path, err := credProviderPath()
	if err != nil {
		return false
	}
	_, err = os.Stat(path)
	return err == nil
}

// RegisterCredProvider points LogonUI at the DLL. It is called by
// "server.exe unlock setup" from an elevated console.
func RegisterCredProvider() error {
	path, err := credProviderPath()
	if err != nil {
		return err
	}
	if _, err := os.Stat(path); err != nil {
		return fmt.Errorf("unlock: %s is not next to server.exe: %w", credProviderDLL, err)
	}

	if err := setDefault(clsidKey, "Switchboard Unlock Credential Provider"); err != nil {
		return err
	}
	inproc, _, err := registry.CreateKey(registry.LOCAL_MACHINE, inprocKey, registry.SET_VALUE)
	if err != nil {
		return fmt.Errorf("unlock: create %s: %w", inprocKey, err)
	}
	defer inproc.Close()
	if err := inproc.SetStringValue("", path); err != nil {
		return err
	}
	// Apartment, because LogonUI hosts providers on an STA and the DLL relies
	// on that for its own serialization.
	if err := inproc.SetStringValue("ThreadingModel", "Apartment"); err != nil {
		return err
	}

	// This is the entry that makes LogonUI load us at all. Written last, so a
	// half-registered provider is never one LogonUI will try to use.
	return setDefault(providerKey, "Switchboard Unlock")
}

// UnregisterCredProvider removes the provider. The credential-provider entry
// goes first for the same reason it was written last.
func UnregisterCredProvider() error {
	if err := deleteKey(providerKey); err != nil {
		return err
	}
	if err := deleteKey(inprocKey); err != nil {
		return err
	}
	return deleteKey(clsidKey)
}

func setDefault(path, value string) error {
	key, _, err := registry.CreateKey(registry.LOCAL_MACHINE, path, registry.SET_VALUE)
	if err != nil {
		return fmt.Errorf("unlock: create %s: %w", path, err)
	}
	defer key.Close()
	return key.SetStringValue("", value)
}

// deleteKey treats an absent key as success: teardown has to arrive at "gone"
// from any starting state, including a half-finished setup.
func deleteKey(path string) error {
	err := registry.DeleteKey(registry.LOCAL_MACHINE, path)
	if err == nil || err == registry.ErrNotExist {
		return nil
	}
	return fmt.Errorf("unlock: delete %s: %w", path, err)
}
