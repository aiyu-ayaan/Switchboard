// Switchboard unlock credential provider.
//
// LogonUI loads this DLL on the secure desktop and asks every registered
// provider what tiles it wants to show. Switchboard shows none, until the
// daemon signals that a phone has proved a fingerprint -- at which point the
// provider produces one tile, declares it the default, and asks LogonUI to
// submit it without user interaction.
//
// This replaces typing the password with SendInput. Typing only ever reached a
// lock screen that happened to be showing the password field, so a machine
// signing in with a PIN silently sent the password into the wrong box.
// Serializing credentials to LogonUI does not care which tile the user last
// used, because our tile is the one being submitted.

#pragma once

#define WIN32_LEAN_AND_MEAN
#define SECURITY_WIN32

#include <windows.h>
#include <credentialprovider.h>
#include <ntsecapi.h>
#include <sspi.h>
#include <string>

// {599BA444-2560-4520-AB9E-A68E52826FEE}
extern const CLSID CLSID_SwitchboardProvider;

// The provider offers exactly one field. The tile is only ever on screen for
// the moment LogonUI takes to submit it, so the label is all it needs; a
// password box would be a control nobody gets the chance to type into.
enum SWITCHBOARD_FIELD_ID
{
    SFI_LABEL = 0,
    SFI_NUM_FIELDS = 1,
};

// The daemon sets this to ask for an unlock.
//
// Local\ rather than Global\: LogonUI and the daemon both live in the console
// session, and creating an object in the global namespace needs
// SeCreateGlobalPrivilege, which the daemon -- running as you -- does not
// hold. Local\ needs no privilege and resolves to the same object for both.
#define SWITCHBOARD_UNLOCK_EVENT L"Local\\Switchboard-Unlock"

// The event is created by LogonUI, which runs as SYSTEM, so its default DACL
// would shut the daemon out. Interactive users get exactly SetEvent and
// nothing else: they must be able to ask, and must not be able to listen in on
// the signal or destroy it.
#define SWITCHBOARD_UNLOCK_EVENT_SDDL L"D:P(A;;0x1F0003;;;SY)(A;;0x1F0003;;;BA)(A;;0x100002;;;IU)"

// Module lifetime, so LogonUI does not unload us while a call is in flight.
void DllAddRef();
void DllRelease();

// provider.cpp
HRESULT CreateSwitchboardProvider(REFIID riid, void** ppv);

// The pending-unlock flag, shared between the provider (which decides whether
// to show a tile) and the credential (which spends the request once it has
// actually produced credentials). File scope rather than a member with a
// back-pointer: there is one lock screen, one provider and one tile at a time,
// and threading an owner reference through the credential to say one bit would
// be more lifetime management than the bit is worth.
bool UnlockRequestPending();
void ConsumeUnlockRequest();

// credential.cpp -- pcpus is the scenario the provider was put into, and is
// carried through to the serialization.
HRESULT CreateSwitchboardCredential(ICredentialProviderCredential** ppc);

// secret.cpp
//
// Both run as SYSTEM inside LogonUI, which is what makes them possible at all:
// the password blob is readable only by SYSTEM and Administrators, and the
// locked session's owner is a question only something inside that session can
// answer.
HRESULT LoadEnrolledPassword(std::wstring& password);
HRESULT CurrentSessionUser(std::wstring& domain, std::wstring& user);

// kerb.cpp
HRESULT SerializeUnlock(const std::wstring& domain,
                        const std::wstring& user,
                        const std::wstring& password,
                        CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* pcpcs);
