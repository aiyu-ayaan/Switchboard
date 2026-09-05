@echo off
setlocal enabledelayedexpansion

:: Check for administrative permissions
>nul 2>&1 "%SYSTEMROOT%\system32\cacls.exe" "%SYSTEMROOT%\system32\config\system"
if '%errorlevel%' NEQ '0' (
    echo Requesting administrative privileges to unregister Switchboard Camera...
    powershell -Command "Start-Process cmd -ArgumentList '/c \"\"%~f0\" %*\"' -Verb RunAs"
    exit /b
)

pushd "%~dp0"

echo Unregistering Switchboard Camera DirectShow filter...

if exist "UnityCaptureFilter32.dll" (
    regsvr32.exe /u /s "UnityCaptureFilter32.dll"
)
if exist "UnityCaptureFilter64.dll" (
    regsvr32.exe /u /s "UnityCaptureFilter64.dll"
)

echo Switchboard Camera unregistered successfully.
popd
if "%1" neq "/silent" (
    timeout /t 3 >nul
)
exit /b 0
