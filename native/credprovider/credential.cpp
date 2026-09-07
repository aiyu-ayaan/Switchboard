#include "switchboard_cp.h"
#include <shlwapi.h>
#include <new>

// The tile LogonUI submits. It has no interactive state -- everything it needs
// is on disk and in the session -- so most of ICredentialProviderCredential is
// answered with "not mine".
class CSwitchboardCredential : public ICredentialProviderCredential
{
public:
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override
    {
        if (!ppv) return E_POINTER;
        if (IsEqualIID(riid, IID_ICredentialProviderCredential) || IsEqualIID(riid, IID_IUnknown))
        {
            *ppv = static_cast<ICredentialProviderCredential*>(this);
            AddRef();
            return S_OK;
        }
        *ppv = nullptr;
        return E_NOINTERFACE;
    }

    IFACEMETHODIMP_(ULONG) AddRef() override { return InterlockedIncrement(&_refs); }

    IFACEMETHODIMP_(ULONG) Release() override
    {
        LONG refs = InterlockedDecrement(&_refs);
        if (refs == 0) delete this;
        return refs;
    }

    IFACEMETHODIMP Advise(ICredentialProviderCredentialEvents*) override { return E_NOTIMPL; }
    IFACEMETHODIMP UnAdvise() override { return E_NOTIMPL; }

    // Selected means submitted: the provider only ever hands this tile out
    // once a fingerprint has already been proved to the daemon.
    IFACEMETHODIMP SetSelected(BOOL* pbAutoLogon) override
    {
        if (!pbAutoLogon) return E_POINTER;
        *pbAutoLogon = TRUE;
        return S_OK;
    }

    IFACEMETHODIMP SetDeselected() override { return S_OK; }

    IFACEMETHODIMP GetFieldState(DWORD dwFieldID,
                                 CREDENTIAL_PROVIDER_FIELD_STATE* pcpfs,
                                 CREDENTIAL_PROVIDER_FIELD_INTERACTIVE_STATE* pcpfis) override
    {
        if (!pcpfs || !pcpfis) return E_POINTER;
        if (dwFieldID >= SFI_NUM_FIELDS) return E_INVALIDARG;
        *pcpfs = CPFS_DISPLAY_IN_SELECTED_TILE;
        *pcpfis = CPFIS_NONE;
        return S_OK;
    }

    IFACEMETHODIMP GetStringValue(DWORD dwFieldID, PWSTR* ppwsz) override
    {
        if (!ppwsz) return E_POINTER;
        *ppwsz = nullptr;
        if (dwFieldID != SFI_LABEL) return E_INVALIDARG;
        return SHStrDupW(L"Unlocking from your phone", ppwsz);
    }

    IFACEMETHODIMP GetBitmapValue(DWORD, HBITMAP*) override { return E_NOTIMPL; }
    IFACEMETHODIMP GetCheckboxValue(DWORD, BOOL*, PWSTR*) override { return E_NOTIMPL; }
    IFACEMETHODIMP GetSubmitButtonValue(DWORD, DWORD*) override { return E_NOTIMPL; }
    IFACEMETHODIMP GetComboBoxValueCount(DWORD, DWORD*, DWORD*) override { return E_NOTIMPL; }
    IFACEMETHODIMP GetComboBoxValueAt(DWORD, DWORD, PWSTR*) override { return E_NOTIMPL; }
    IFACEMETHODIMP SetStringValue(DWORD, PCWSTR) override { return E_NOTIMPL; }
    IFACEMETHODIMP SetCheckboxValue(DWORD, BOOL) override { return E_NOTIMPL; }
    IFACEMETHODIMP SetComboBoxSelectedValue(DWORD, DWORD) override { return E_NOTIMPL; }
    IFACEMETHODIMP CommandLinkClicked(DWORD) override { return E_NOTIMPL; }

    // Where the password leaves the disk and enters LogonUI. It is read,
    // packed and wiped inside this call; nothing holds it between unlocks.
    IFACEMETHODIMP GetSerialization(CREDENTIAL_PROVIDER_GET_SERIALIZATION_RESPONSE* pcpgsr,
                                    CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION* pcpcs,
                                    PWSTR* ppwszOptionalStatusText,
                                    CREDENTIAL_PROVIDER_STATUS_ICON* pcpsiOptionalStatusIcon) override
    {
        if (!pcpgsr || !pcpcs) return E_POINTER;
        if (ppwszOptionalStatusText) *ppwszOptionalStatusText = nullptr;
        if (pcpsiOptionalStatusIcon) *pcpsiOptionalStatusIcon = CPSI_NONE;
        *pcpgsr = CPGSR_NO_CREDENTIAL_NOT_FINISHED;

        // Spend it here, whatever happens next. A stored password that LSA
        // rejects must not re-arm the tile, or LogonUI would auto-submit the
        // same wrong credentials in a loop.
        ConsumeUnlockRequest();

        std::wstring domain, user;
        HRESULT hr = CurrentSessionUser(domain, user);
        if (FAILED(hr)) return Fail(hr, L"Switchboard could not identify the locked account.",
                                    ppwszOptionalStatusText, pcpsiOptionalStatusIcon);

        std::wstring password;
        hr = LoadEnrolledPassword(password);
        if (FAILED(hr)) return Fail(hr, L"Switchboard has no enrolled password on this PC.",
                                    ppwszOptionalStatusText, pcpsiOptionalStatusIcon);

        hr = SerializeUnlock(domain, user, password, pcpcs);
        SecureZeroMemory(&password[0], password.size() * sizeof(wchar_t));
        if (FAILED(hr)) return Fail(hr, L"Switchboard could not submit the unlock.",
                                    ppwszOptionalStatusText, pcpsiOptionalStatusIcon);

        *pcpgsr = CPGSR_RETURN_CREDENTIAL_FINISHED;
        return S_OK;
    }

    // A wrong password lands here rather than at GetSerialization, because
    // LogonUI is the one that tried it.
    IFACEMETHODIMP ReportResult(NTSTATUS ntsStatus, NTSTATUS,
                                PWSTR* ppwszOptionalStatusText,
                                CREDENTIAL_PROVIDER_STATUS_ICON* pcpsiOptionalStatusIcon) override
    {
        if (ppwszOptionalStatusText) *ppwszOptionalStatusText = nullptr;
        if (pcpsiOptionalStatusIcon) *pcpsiOptionalStatusIcon = CPSI_NONE;

        if (!NT_SUCCESS(ntsStatus) && ppwszOptionalStatusText)
        {
            // Worth saying out loud: the usual cause is the account password
            // having changed since it was enrolled, and nothing on the phone
            // can tell the user that.
            SHStrDupW(L"Switchboard's stored password was rejected. Re-run \"server unlock enroll\".",
                      ppwszOptionalStatusText);
            if (pcpsiOptionalStatusIcon) *pcpsiOptionalStatusIcon = CPSI_ERROR;
        }
        return S_OK;
    }

    CSwitchboardCredential() : _refs(1) { DllAddRef(); }

private:
    ~CSwitchboardCredential() { DllRelease(); }

    static HRESULT Fail(HRESULT hr, PCWSTR message, PWSTR* ppwszStatus,
                        CREDENTIAL_PROVIDER_STATUS_ICON* pcpsi)
    {
        if (ppwszStatus) SHStrDupW(message, ppwszStatus);
        if (pcpsi) *pcpsi = CPSI_ERROR;
        // S_OK with CPGSR_NO_CREDENTIAL_NOT_FINISHED leaves the lock screen
        // usable and shows the message. Returning the failure would make
        // LogonUI treat the provider itself as broken.
        (void)hr;
        return S_OK;
    }

    LONG _refs;
};

HRESULT CreateSwitchboardCredential(ICredentialProviderCredential** ppc)
{
    auto* credential = new (std::nothrow) CSwitchboardCredential();
    if (!credential) return E_OUTOFMEMORY;

    HRESULT hr = credential->QueryInterface(IID_ICredentialProviderCredential, reinterpret_cast<void**>(ppc));
    credential->Release();
    return hr;
}
