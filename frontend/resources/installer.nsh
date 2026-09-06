; The app registers its "Send to Switchboard" Explorer verb under HKCU at
; every launch, so uninstalling has to take it back out — otherwise the menu
; entry outlives the app and points at a path that no longer exists.
!macro customUnInstall
  DeleteRegKey HKCU "Software\Classes\*\shell\SwitchboardSend"
!macroend
