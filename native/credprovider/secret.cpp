#include "switchboard_cp.h"
#include <dpapi.h>
#include <shlobj.h>
#include <wtsapi32.h>
#include <vector>

// LoadEnrolledPassword reads %ProgramData%\Switchboard\unlock.bin and undoes
// the machine-scope DPAPI seal "server unlock enroll" applied.
//
// The file's DACL admits only SYSTEM and Administrators, which is the actual
// control -- a machine-scope blob is decryptable by anything that can read it.
// This runs inside LogonUI as SYSTEM, so it clears that bar without the daemon
// ever holding the plaintext.
HRESULT LoadEnrolledPassword(std::wstring& password)
{
    password.clear();

    PWSTR programData = nullptr;
    // The known folder rather than %ProgramData%: LogonUI's environment is not
    // the interactive user's, and an unset variable would send us looking in
    // the wrong place.
    HRESULT hr = SHGetKnownFolderPath(FOLDERID_ProgramData, 0, nullptr, &programData);
    if (FAILED(hr)) return hr;

    std::wstring path(programData);
    CoTaskMemFree(programData);
    path += L"\\Switchboard\\unlock.bin";

    HANDLE file = CreateFileW(path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr,
                              OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) return HRESULT_FROM_WIN32(GetLastError());

    LARGE_INTEGER size = {};
    if (!GetFileSizeEx(file, &size) || size.QuadPart == 0 || size.QuadPart > (1 << 20))
    {
        CloseHandle(file);
        return E_UNEXPECTED;
    }

    std::vector<BYTE> blob(static_cast<size_t>(size.QuadPart));
    DWORD read = 0;
    BOOL ok = ReadFile(file, blob.data(), static_cast<DWORD>(blob.size()), &read, nullptr);
    CloseHandle(file);
    if (!ok || read != blob.size()) return E_UNEXPECTED;

    DATA_BLOB in = { static_cast<DWORD>(blob.size()), blob.data() };
    DATA_BLOB out = {};
    // CRYPTPROTECT_UI_FORBIDDEN because there is no desktop here to prompt on;
    // a prompt would hang LogonUI rather than fail.
    if (!CryptUnprotectData(&in, nullptr, nullptr, nullptr, nullptr,
                            CRYPTPROTECT_UI_FORBIDDEN | CRYPTPROTECT_LOCAL_MACHINE, &out))
    {
        return HRESULT_FROM_WIN32(GetLastError());
    }

    // The blob holds the password as UTF-8: the Go side sealed the bytes of a
    // Go string. Decoding it as anything else would break every non-ASCII
    // password, and would do so silently.
    hr = S_OK;
    int wide = MultiByteToWideChar(CP_UTF8, 0, reinterpret_cast<char*>(out.pbData),
                                   static_cast<int>(out.cbData), nullptr, 0);
    if (wide <= 0)
    {
        hr = HRESULT_FROM_WIN32(GetLastError());
    }
    else
    {
        password.resize(static_cast<size_t>(wide));
        MultiByteToWideChar(CP_UTF8, 0, reinterpret_cast<char*>(out.pbData),
                            static_cast<int>(out.cbData), &password[0], wide);
    }

    SecureZeroMemory(out.pbData, out.cbData);
    LocalFree(out.pbData);
    return hr;
}

// CurrentSessionUser names the account whose session is locked. LogonUI runs
// inside that session, so asking about our own session is the same question.
HRESULT CurrentSessionUser(std::wstring& domain, std::wstring& user)
{
    domain.clear();
    user.clear();

    LPWSTR value = nullptr;
    DWORD bytes = 0;

    if (!WTSQuerySessionInformationW(WTS_CURRENT_SERVER_HANDLE, WTS_CURRENT_SESSION,
                                     WTSUserName, &value, &bytes))
    {
        return HRESULT_FROM_WIN32(GetLastError());
    }
    user = value ? value : L"";
    WTSFreeMemory(value);
    value = nullptr;

    if (WTSQuerySessionInformationW(WTS_CURRENT_SERVER_HANDLE, WTS_CURRENT_SESSION,
                                    WTSDomainName, &value, &bytes))
    {
        domain = value ? value : L"";
        WTSFreeMemory(value);
    }

    if (user.empty()) return E_UNEXPECTED;

    // A local account reports the machine name as its domain, which is what
    // LSA wants anyway; an empty one would be interpreted as "any".
    if (domain.empty())
    {
        wchar_t computer[MAX_COMPUTERNAME_LENGTH + 1] = {};
        DWORD length = ARRAYSIZE(computer);
        if (GetComputerNameW(computer, &length)) domain = computer;
    }
    return S_OK;
}
