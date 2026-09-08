; The app registers its "Send to Switchboard" Explorer verb under HKCU at
; every launch, so uninstalling has to take it back out — otherwise the menu
; entry outlives the app and points at a path that no longer exists.
!macro customUnInstall
  DeleteRegKey HKCU "Software\Classes\*\shell\SwitchboardSend"
!macroend

; Starting Switchboard again once an update has installed, without StdUtils.
;
; An in-app update runs this installer as `--updated /S --force-run` and then
; quits, so the only thing that can bring the app back is the installer itself.
; electron-builder does that with `StdUtils::ExecShellAsUser`, which exists to
; hand a launch *down* from an elevated installer to the signed-in user by
; asking the desktop shell to perform the ShellExecute. When the shell will not
; take that call it fails, and the result is popped into `$0` and dropped — so
; the install finishes and nothing opens.
;
; This installer is per user (`perMachine: false`), so there is usually nothing
; to hand a launch down from and a plain `ExecShell` is the whole of what is
; needed. electron-builder's own launch still runs afterwards, and on a machine
; where `ExecShellAsUser` works that is two launches; the second one ends
; immediately, because Switchboard holds a single-instance lock and a second
; copy hands its arguments to the first and quits.
!macro customInstall
  ${if} ${isForceRun}
  ${andIf} ${Silent}
    ExecShell "" "$INSTDIR\${APP_EXECUTABLE_FILENAME}"
  ${endIf}
!macroend
