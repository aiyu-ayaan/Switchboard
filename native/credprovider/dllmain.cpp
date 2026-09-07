// COM plumbing. Nothing Switchboard-specific lives here.

#include "switchboard_cp.h"
#include <new>

// {599BA444-2560-4520-AB9E-A68E52826FEE}
const CLSID CLSID_SwitchboardProvider =
    { 0x599ba444, 0x2560, 0x4520, { 0xab, 0x9e, 0xa6, 0x8e, 0x52, 0x82, 0x6f, 0xee } };

static LONG g_moduleRefs = 0;

void DllAddRef() { InterlockedIncrement(&g_moduleRefs); }
void DllRelease() { InterlockedDecrement(&g_moduleRefs); }

class CClassFactory : public IClassFactory
{
public:
    IFACEMETHODIMP QueryInterface(REFIID riid, void** ppv) override
    {
        if (!ppv) return E_POINTER;
        if (IsEqualIID(riid, IID_IClassFactory) || IsEqualIID(riid, IID_IUnknown))
        {
            *ppv = static_cast<IClassFactory*>(this);
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

    IFACEMETHODIMP CreateInstance(IUnknown* pUnkOuter, REFIID riid, void** ppv) override
    {
        if (!ppv) return E_POINTER;
        *ppv = nullptr;
        if (pUnkOuter) return CLASS_E_NOAGGREGATION;
        return CreateSwitchboardProvider(riid, ppv);
    }

    IFACEMETHODIMP LockServer(BOOL lock) override
    {
        if (lock) DllAddRef(); else DllRelease();
        return S_OK;
    }

    CClassFactory() : _refs(1) { DllAddRef(); }

private:
    ~CClassFactory() { DllRelease(); }
    LONG _refs;
};

STDAPI DllGetClassObject(REFCLSID rclsid, REFIID riid, void** ppv)
{
    if (!ppv) return E_POINTER;
    *ppv = nullptr;
    if (!IsEqualCLSID(rclsid, CLSID_SwitchboardProvider)) return CLASS_E_CLASSNOTAVAILABLE;

    CClassFactory* factory = new (std::nothrow) CClassFactory();
    if (!factory) return E_OUTOFMEMORY;

    HRESULT hr = factory->QueryInterface(riid, ppv);
    factory->Release();
    return hr;
}

STDAPI DllCanUnloadNow()
{
    return (g_moduleRefs > 0) ? S_FALSE : S_OK;
}

BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID)
{
    if (reason == DLL_PROCESS_ATTACH) DisableThreadLibraryCalls(hinst);
    return TRUE;
}
