; ============================================================
; Installer WhatsApp Web Client untuk Windows (wizard NSIS).
;
; Build dari folder windows/:
;   makensis -DAPP_VERSION=1.0.0 installer/installer.nsi
; Hasil: dist/WhatsAppWebClient-Setup-1.0.0.exe
;
; Perilaku upgrade: bila versi lama sudah terpasang, uninstaller versi lama
; dijalankan otomatis (senyap) sebelum file baru ditulis, sehingga entri
; Add/Remove Programs dan file lama tidak menumpuk.
; ============================================================
!include "MUI2.nsh"
!include "LogicLib.nsh"

!ifndef APP_VERSION
  !define APP_VERSION "1.0.0"
!endif
; Path relatif terhadap folder script (makensis selalu bekerja dari sana).
!ifndef SOURCE_EXE
  !define SOURCE_EXE "..\dist\WhatsAppWebClient.exe"
!endif
!ifndef OUTFILE
  !define OUTFILE "..\dist\WhatsAppWebClient-Setup-${APP_VERSION}.exe"
!endif

!define APP_NAME     "WhatsAppWebClient"
!define APP_DISPLAY  "WhatsApp Web Client"
!define APP_EXE      "$INSTDIR\WhatsAppWebClient.exe"
!define UNINST_KEY   "Software\Microsoft\Windows\CurrentVersion\Uninstall\WhatsAppWebClient"
!define APP_KEY      "Software\WhatsAppWebClient"

Name "${APP_DISPLAY} ${APP_VERSION}"
OutFile "${OUTFILE}"
InstallDir "$LOCALAPPDATA\Programs\WhatsAppWebClient"
InstallDirRegKey HKCU "${APP_KEY}" "InstallDir"
RequestExecutionLevel user
Unicode true
SetCompressor /SOLID lzma

!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "${APP_EXE}"
!define MUI_FINISHPAGE_RUN_TEXT "Jalankan ${APP_DISPLAY}"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_LICENSE "license.txt"
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "Indonesian"
!insertmacro MUI_LANGUAGE "English"

Function .onInit
  ; 1. Aplikasi masih berjalan -> file exe terkunci dan tidak bisa ditimpa.
  nsExec::ExecToStack 'cmd /c tasklist /FI "IMAGENAME eq WhatsAppWebClient.exe" | find /I "WhatsAppWebClient.exe" >nul'
  Pop $0
  ${If} $0 = 0
    MessageBox MB_YESNO|MB_ICONEXCLAMATION \
      "${APP_DISPLAY} masih berjalan. Tutup otomatis sekarang?$\n$\n\
      Pilih Tidak untuk membatalkan pemasangan." IDNO cancel
    nsExec::ExecToStack 'cmd /c taskkill /F /IM WhatsAppWebClient.exe >nul'
    Pop $0
    Sleep 800
  ${EndIf}

  ; 2. Ada versi lama -> lepas dulu (senyap, tanpa wizard) lalu lanjut memasang.
  ReadRegStr $1 HKCU "${APP_KEY}" "UninstallExe"
  ${If} $1 != ""
    MessageBox MB_YESNO|MB_ICONQUESTION \
      "Versi sebelumnya sudah terpasang dan akan dilepas otomatis sebelum\
      memasang ${APP_VERSION}. Lanjutkan?" IDNO cancel
    ExecWait '"$1" /S'
  ${EndIf}
  Return

cancel:
  Abort
FunctionEnd

Section "Install"
  SetOutPath "$INSTDIR"
  File "${SOURCE_EXE}"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  CreateDirectory "$SMPROGRAMS\${APP_DISPLAY}"
  CreateShortCut "$SMPROGRAMS\${APP_DISPLAY}\${APP_DISPLAY}.lnk" "${APP_EXE}"
  CreateShortCut "$DESKTOP\${APP_DISPLAY}.lnk" "${APP_EXE}"

  WriteRegStr HKCU "${APP_KEY}" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "${APP_KEY}" "UninstallExe" "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "${APP_DISPLAY}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "${APP_DISPLAY}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "${APP_EXE}"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
SectionEnd

Section "Uninstall"
  Delete "$INSTDIR\WhatsAppWebClient.exe"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"
  Delete "$SMPROGRAMS\${APP_DISPLAY}\${APP_DISPLAY}.lnk"
  RMDir "$SMPROGRAMS\${APP_DISPLAY}"
  Delete "$DESKTOP\${APP_DISPLAY}.lnk"
  DeleteRegKey HKCU "${UNINST_KEY}"
  DeleteRegKey HKCU "${APP_KEY}"
  ; Profil WebView2 (%LocalAppData%\WhatsAppWebClient) sengaja tidak dihapus
  ; supaya login WhatsApp tetap ada kalau aplikasi dipasang ulang.
SectionEnd
