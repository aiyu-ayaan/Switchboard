#include "switchboard_cp.h"
#include <sddl.h>
#include <shlwapi.h>
#include <new>

// The tile carries one label and no input. See SWITCHBOARD_FIELD_ID.
static CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR s_fields[SFI_NUM_FIELDS] =
{
    { SFI_LABEL, CPFT_LARGE_TEXT, const_cast<PWSTR>(L"Switchboard unlock") },
};

static volatile LONG g_requested = 0;

bool UnlockRequestPending() { return InterlockedCompareExchange(&g_requested, 0, 0) != 0; }
void ConsumeUnlockRequest() { InterlockedExchange(&g_requested, 0); }

class CSwitchboardProvider : public ICredentialProvider
{
public:
    // IUnknown
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override
    {
        if (!ppv) return E_POINTER;
        if (IsEqualIID(riid, IID_ICredentialProvider) || IsEqualIID(riid, IID_IUnknown))
        {
            *ppv = static_cast<ICredentialProvider*>(this);
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

    // ICredentialProvider

    // Unlock only. Sign-in, credential prompts and password changes are left
    // to the providers that own them: a tile that auto-submits a stored
    // password has no business appearing at a UAC prompt, and this feature
    // opens a locked session rather than a cold boot.
    IFACEMETHODIMP SetUsageScenario(CREDENTIAL_PROVIDER_USAGE_SCENARIO cpus, DWORD) override
    {
        if (cpus != CPUS_UNLOCK_WORKSTATION) return E_NOTIMPL;
        return StartListening();
    }

    IFACEMETHODIMP SetSerialization(const CREDENTIAL_PROVIDER_CREDENTIAL_SERIALIZATION*) override
    {
        return E_NOTIMPL;
    }

    IFACEMETHODIMP Advise(ICredentialProviderEvents* pcpe, UINT_PTR context) override
    {
        EnterCriticalSection(&_cs);
        if (_events) _events->Release();
        _events = pcpe;
        if (_events) _events->AddRef();
        _context = context;
        LeaveCriticalSection(&_cs);
        return S_OK;
    }

    IFACEMETHODIMP UnAdvise() override
    {
        EnterCriticalSection(&_cs);
        if (_events) { _events->Release(); _events = nullptr; }
        _context = 0;
        LeaveCriticalSection(&_cs);
        return S_OK;
    }

    IFACEMETHODIMP GetFieldDescriptorCount(DWORD* pdwCount) override
    {
        if (!pdwCount) return E_POINTER;
        *pdwCount = SFI_NUM_FIELDS;
        return S_OK;
    }

    IFACEMETHODIMP GetFieldDescriptorAt(DWORD index, CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR** ppcpfd) override
    {
        if (!ppcpfd) return E_POINTER;
        *ppcpfd = nullptr;
        if (index >= SFI_NUM_FIELDS) return E_INVALIDARG;

        auto* copy = static_cast<CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR*>(
            CoTaskMemAlloc(sizeof(CREDENTIAL_PROVIDER_FIELD_DESCRIPTOR)));
        if (!copy) return E_OUTOFMEMORY;

        *copy = s_fields[index];
        // The label has to be a separate CoTaskMem allocation: LogonUI frees
        // the two independently, and handing it a pointer into our static
        // array would have it free read-only memory.
        copy->pszLabel = nullptr;
        HRESULT hr = SHStrDupW(s_fields[index].pszLabel, &copy->pszLabel);
        if (FAILED(hr)) { CoTaskMemFree(copy); return hr; }

        *ppcpfd = copy;
        return S_OK;
    }

    // No tile at all until a phone has asked for one. An idle Switchboard is
    // invisible on the lock screen, which is the behaviour a provider that
    // auto-submits a stored password ought to have.
    IFACEMETHODIMP GetCredentialCount(DWORD* pdwCount, DWORD* pdwDefault, BOOL* pbAutoLogonWithDefault) override
    {
        if (!pdwCount || !pdwDefault || !pbAutoLogonWithDefault) return E_POINTER;

        if (!UnlockRequestPending())
        {
            *pdwCount = 0;
            *pdwDefault = CREDENTIAL_PROVIDER_NO_DEFAULT;
            *pbAutoLogonWithDefault = FALSE;
            return S_OK;
        }

        *pdwCount = 1;
        *pdwDefault = 0;
        // The whole point: LogonUI submits this tile itself, so whichever tile
        // the user last signed in with -- PIN, Hello, anything -- is not the
        // one receiving the credentials.
        *pbAutoLogonWithDefault = TRUE;
        return S_OK;
    }

    IFACEMETHODIMP GetCredentialAt(DWORD index, ICredentialProviderCredential** ppcpc) override
    {
        if (!ppcpc) return E_POINTER;
        *ppcpc = nullptr;
        if (index != 0) return E_INVALIDARG;
        // The request is spent in GetSerialization, not here. LogonUI
        // re-enumerates freely, and clearing it on the way out would withdraw
        // the tile from under the submit it is in the middle of.
        return CreateSwitchboardCredential(ppcpc);
    }

    CSwitchboardProvider()
        : _refs(1), _events(nullptr), _context(0),
          _signal(nullptr), _stop(nullptr), _thread(nullptr)
    {
        InitializeCriticalSection(&_cs);
        DllAddRef();
    }

private:
    ~CSwitchboardProvider()
    {
        StopListening();
        if (_events) _events->Release();
        DeleteCriticalSection(&_cs);
        DllRelease();
    }

    HRESULT StartListening()
    {
        if (_thread) return S_OK;

        PSECURITY_DESCRIPTOR sd = nullptr;
        if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(
                SWITCHBOARD_UNLOCK_EVENT_SDDL, SDDL_REVISION_1, &sd, nullptr))
        {
            return HRESULT_FROM_WIN32(GetLastError());
        }
        SECURITY_ATTRIBUTES sa = { sizeof(sa), sd, FALSE };

        // Auto-reset: one waiter consumes one request, so a burst of taps
        // cannot queue up behind a single unlock.
        _signal = CreateEventW(&sa, FALSE, FALSE, SWITCHBOARD_UNLOCK_EVENT);
        LocalFree(sd);
        if (!_signal) return HRESULT_FROM_WIN32(GetLastError());

        // A request signalled while no lock screen was up would otherwise be
        // sitting in the event, and would unlock the machine the instant it
        // next locked. Consume anything already there before listening.
        WaitForSingleObject(_signal, 0);

        _stop = CreateEventW(nullptr, TRUE, FALSE, nullptr);
        if (!_stop) { CloseHandle(_signal); _signal = nullptr; return HRESULT_FROM_WIN32(GetLastError()); }

        _thread = CreateThread(nullptr, 0, ListenThread, this, 0, nullptr);
        if (!_thread)
        {
            CloseHandle(_stop); _stop = nullptr;
            CloseHandle(_signal); _signal = nullptr;
            return HRESULT_FROM_WIN32(GetLastError());
        }
        return S_OK;
    }

    void StopListening()
    {
        if (_stop) SetEvent(_stop);
        if (_thread)
        {
            WaitForSingleObject(_thread, 5000);
            CloseHandle(_thread);
            _thread = nullptr;
        }
        if (_stop) { CloseHandle(_stop); _stop = nullptr; }
        if (_signal) { CloseHandle(_signal); _signal = nullptr; }
    }

    static DWORD WINAPI ListenThread(LPVOID param)
    {
        auto* self = static_cast<CSwitchboardProvider*>(param);
        HANDLE waits[2] = { self->_stop, self->_signal };
        for (;;)
        {
            DWORD which = WaitForMultipleObjects(2, waits, FALSE, INFINITE);
            if (which != WAIT_OBJECT_0 + 1) return 0; // stop, or the wait broke

            InterlockedExchange(&g_requested, 1);

            // Asking LogonUI to re-enumerate is the only way to make a tile
            // appear after the fact; it comes straight back into
            // GetCredentialCount, which now answers with one.
            self->EnterAndNotify();
        }
    }

    void EnterAndNotify()
    {
        EnterCriticalSection(&_cs);
        if (_events) _events->CredentialsChanged(_context);
        LeaveCriticalSection(&_cs);
    }

    LONG _refs;
    ICredentialProviderEvents* _events;
    UINT_PTR _context;
    HANDLE _signal;
    HANDLE _stop;
    HANDLE _thread;
    CRITICAL_SECTION _cs;
};

HRESULT CreateSwitchboardProvider(REFIID riid, void** ppv)
{
    auto* provider = new (std::nothrow) CSwitchboardProvider();
    if (!provider) return E_OUTOFMEMORY;

    HRESULT hr = provider->QueryInterface(riid, ppv);
    provider->Release();
    return hr;
}
