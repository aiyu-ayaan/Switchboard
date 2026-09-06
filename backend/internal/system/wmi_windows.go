//go:build windows

package system

import (
	"fmt"

	"github.com/go-ole/go-ole"
	"github.com/go-ole/go-ole/oleutil"
)

// WMI reached through its COM automation objects (SWbemLocator / SWbemServices
// / SWbemObject) — the same object model PowerShell's Get-CimInstance and
// Invoke-CimMethod drive, minus the ~500ms cost of spawning a shell to reach
// it, and minus the need to build a script by string interpolation.
//
// Property reads and method calls go through IDispatch. That is deliberate:
// SWbemObject resolves CIM property and method names dynamically, so a WMI
// method is invoked as an ordinary automation call rather than through
// GetMethod/SpawnInstance/ExecMethod on the raw IWbemServices vtable.

// withWMI connects to a namespace (for example `root\wmi`) and hands fn the
// SWbemServices object, releasing everything on the way out.
func withWMI(namespace string, fn func(service *ole.IDispatch) error) error {
	return withCOM(func() error {
		unknown, err := oleutil.CreateObject("WbemScripting.SWbemLocator")
		if err != nil {
			return fmt.Errorf("wmi: create locator: %w", err)
		}
		defer unknown.Release()

		locator, err := unknown.QueryInterface(ole.IID_IDispatch)
		if err != nil {
			return fmt.Errorf("wmi: locator dispatch: %w", err)
		}
		defer locator.Release()

		// "." is this machine. Passing the server explicitly rather than
		// omitting it keeps every argument a type go-ole marshals.
		raw, err := oleutil.CallMethod(locator, "ConnectServer", ".", namespace)
		if err != nil {
			return fmt.Errorf("wmi: connect %s: %w", namespace, err)
		}
		defer raw.Clear()

		service := raw.ToIDispatch()
		if service == nil {
			return fmt.Errorf("wmi: connect %s returned no service", namespace)
		}
		return fn(service)
	})
}

// wmiQuery runs a WQL query and calls fn once per returned instance. fn must
// not retain the object: it is released as soon as fn returns.
func wmiQuery(service *ole.IDispatch, query string, fn func(instance *ole.IDispatch) error) error {
	raw, err := oleutil.CallMethod(service, "ExecQuery", query)
	if err != nil {
		return fmt.Errorf("wmi: %s: %w", query, err)
	}
	defer raw.Clear()

	set := raw.ToIDispatch()
	if set == nil {
		return fmt.Errorf("wmi: %s returned no result set", query)
	}

	enumRaw, err := set.GetProperty("_NewEnum")
	if err != nil {
		return fmt.Errorf("wmi: enumerate: %w", err)
	}
	defer enumRaw.Clear()

	enum, err := enumRaw.ToIUnknown().IEnumVARIANT(ole.IID_IEnumVariant)
	if err != nil {
		return fmt.Errorf("wmi: enumerator: %w", err)
	}
	defer enum.Release()

	for {
		// Exhaustion is S_FALSE, which is a non-zero HRESULT and so arrives
		// here as an error. Length is therefore the termination signal and
		// has to be read before err, or every complete enumeration fails.
		item, length, err := enum.Next(1)
		if length == 0 {
			return nil
		}
		if err != nil {
			return fmt.Errorf("wmi: next instance: %w", err)
		}
		instance := item.ToIDispatch()
		var ferr error
		if instance != nil {
			ferr = fn(instance)
		}
		// Without this the daemon leaks one COM object per instance per
		// enumeration, and displays are re-enumerated on every hotplug.
		item.Clear()
		if ferr != nil {
			return ferr
		}
	}
}

// wmiString reads a string property, returning "" when it is absent or not a
// string.
func wmiString(instance *ole.IDispatch, name string) string {
	raw, err := oleutil.GetProperty(instance, name)
	if err != nil {
		return ""
	}
	defer raw.Clear()
	if raw.VT != ole.VT_BSTR {
		return ""
	}
	return raw.ToString()
}

// wmiInt reads a numeric property. The width is not assumed: the same CIM
// value reaches automation as VT_UI1, VT_I4 or VT_I8 depending on the provider
// and the OS version, and all of them carry the integer in the same union
// field.
func wmiInt(instance *ole.IDispatch, name string) (int, bool) {
	raw, err := oleutil.GetProperty(instance, name)
	if err != nil {
		return 0, false
	}
	defer raw.Clear()

	switch raw.VT {
	case ole.VT_I1, ole.VT_UI1, ole.VT_I2, ole.VT_UI2,
		ole.VT_I4, ole.VT_UI4, ole.VT_I8, ole.VT_UI8,
		ole.VT_INT, ole.VT_UINT:
		return int(raw.Val), true
	}
	return 0, false
}
