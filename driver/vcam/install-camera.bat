@echo off
setlocal enabledelayedexpansion

:: Check for administrative permissions
>nul 2>&1 "%SYSTEMROOT%\system32\cacls.exe" "%SYSTEMROOT%\system32\config\system"
if '%errorlevel%' NEQ '0' (
    echo Requesting administrative privileges to register Switchboard Camera...
    powershell -Command "Start-Process cmd -ArgumentList '/c \"\"%~f0\" %*\"' -Verb RunAs"
    exit /b
)

pushd "%~dp0"

set "DEVICE_NAME=Switchboard Camera"
echo Registering %DEVICE_NAME% DirectShow filter...

if exist "UnityCaptureFilter32.dll" (
    regsvr32.exe /s "UnityCaptureFilter32.dll" "/i:UnityCaptureName=%DEVICE_NAME%"
)
if exist "UnityCaptureFilter64.dll" (
    regsvr32.exe /s "UnityCaptureFilter64.dll" "/i:UnityCaptureName=%DEVICE_NAME%"
)

echo %DEVICE_NAME% registered successfully!
echo You can now select '%DEVICE_NAME%' in Chrome, Edge, Zoom, Teams, Discord, OBS, and other apps.
popd
if "%1" neq "/silent" (
    timeout /t 3 >nul
)
exit /b 0
