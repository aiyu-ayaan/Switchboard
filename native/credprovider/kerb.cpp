// Packing credentials into the shape LSA expects.
//
// This is the one part of a credential provider with no room for invention:
// LogonUI hands the blob straight to LSA, which reads a
// KERB_INTERACTIVE_UNLOCK_LOGON whose UNICODE_STRING buffers are offsets from
// the start of the allocation rather than pointers. Getting that wrong fails
// with a generic logon error and no clue why.

#include "switchboard_cp.h"
#include <ntsecapi.h>

extern const CLSID CLSID_SwitchboardProvider;

static void InitUnicodeString(PCWSTR value, UNICODE_STRING* out)
{
    if (value)
    {
        size_t length = wcslen(value);
        out->Length = static_cast<USHORT>(length * sizeof(wchar_t));
        out->MaximumLength = static_cast<USHORT>((length + 1) * sizeof(wchar_t));
        out->Buffer = const_cast<PWSTR>(value);
    }
    else
    {
        ZeroMemory(out, sizeof(*out));
    }
}

static void CopyPacked(const UNICODE_STRING& from, PWSTR buffer, UNICODE_STRING* to)
{
    to->Length = from.Length;
    to->MaximumLength = from.Length;
    to->Buffer = buffer;
    if (from.Length) CopyMemory(buffer, from.Buffer, from.Length);
}

// The authentication package LSA should route this to. Negotiate rather than
// Kerberos by name, so a local account (which has no Kerberos) works the same
// as a domain one.
static HRESULT NegotiateAuthPackage(ULONG* package)
{
    HANDLE lsa = nullptr;
    NTSTATUS status = LsaConnectUntrusted(&lsa);
    if (!NT_SUCCESS(status)) return HRESULT_FROM_NT(status);

    LSA_STRING name;
    name.Buffer = const_cast<PCHAR>(NEGOSSP_NAME_A);
    name.Length = static_cast<USHORT>(strlen(NEGOSSP_NAME_A));
    name.MaximumLength = static_cast<USHORT>(name.Length + 1);

    status = LsaLookupAuthenticationPackage(lsa, &name, package);
    LsaDeregisterLogonProcess(lsa);
    return NT_SUCCESS(status) ? S_OK : HRESULT_FROM_NT(status);
}

HRESULT SerializeUnlock(const std::wstring& domain,
                        const std::wstring& user,
                        const std::wstring& password,
                        CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* pcpcs)
{
    KERB_INTERACTIVE_UNLOCK_LOGON source = {};
    source.Logon.MessageType = KerbWorkstationUnlockLogon;
    InitUnicodeString(domain.c_str(), &source.Logon.LogonDomainName);
    InitUnicodeString(user.c_str(), &source.Logon.UserName);
    InitUnicodeString(password.c_str(), &source.Logon.Password);

    const DWORD size = sizeof(KERB_INTERACTIVE_UNLOCK_LOGON) +
                       source.Logon.LogonDomainName.Length +
                       source.Logon.UserName.Length +
                       source.Logon.Password.Length;

    auto* packed = static_cast<KERB_INTERACTIVE_UNLOCK_LOGON*>(CoTaskMemAlloc(size));
    if (!packed) return E_OUTOFMEMORY;
    ZeroMemory(packed, size);

    // LogonId is zero for an unlock; LSA fills it in.
    packed->Logon.MessageType = source.Logon.MessageType;

    BYTE* cursor = reinterpret_cast<BYTE*>(packed) + sizeof(KERB_INTERACTIVE_UNLOCK_LOGON);

    CopyPacked(source.Logon.LogonDomainName, reinterpret_cast<PWSTR>(cursor), &packed->Logon.LogonDomainName);
    packed->Logon.LogonDomainName.Buffer =
        reinterpret_cast<PWSTR>(cursor - reinterpret_cast<BYTE*>(packed));
    cursor += packed->Logon.LogonDomainName.Length;

    CopyPacked(source.Logon.UserName, reinterpret_cast<PWSTR>(cursor), &packed->Logon.UserName);
    packed->Logon.UserName.Buffer =
        reinterpret_cast<PWSTR>(cursor - reinterpret_cast<BYTE*>(packed));
    cursor += packed->Logon.UserName.Length;

    CopyPacked(source.Logon.Password, reinterpret_cast<PWSTR>(cursor), &packed->Logon.Password);
    packed->Logon.Password.Buffer =
        reinterpret_cast<PWSTR>(cursor - reinterpret_cast<BYTE*>(packed));

    ULONG package = 0;
    HRESULT hr = NegotiateAuthPackage(&package);
    if (FAILED(hr))
    {
        SecureZeroMemory(packed, size);
        CoTaskMemFree(packed);
        return hr;
    }

    pcpcs->ulAuthenticationPackage = package;
    pcpcs->clsidCredentialProvider = CLSID_SwitchboardProvider;
    pcpcs->cbSerialization = size;
    pcpcs->rgbSerialization = reinterpret_cast<BYTE*>(packed);
    return S_OK;
}
