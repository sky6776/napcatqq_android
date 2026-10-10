@echo off
setlocal EnableExtensions DisableDelayedExpansion
cd /d "%~dp0"

rem Override with `set NAPCATHOST_TOOLCHAIN=...` to use a different local toolchain.
if not defined NAPCATHOST_TOOLCHAIN set "NAPCATHOST_TOOLCHAIN=E:\my-android\android-toolchain"

set "MODE=%~1"
set "INTERACTIVE=0"
if not defined MODE (
    set "MODE=menu"
    set "INTERACTIVE=1"
)

if /I not "%MODE%"=="menu" if /I not "%MODE%"=="build" if /I not "%MODE%"=="install" if /I not "%MODE%"=="copy" goto usage

echo ============================================================
echo NapCatHost offline APK builder
echo ============================================================
echo.

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\build.ps1"
if errorlevel 1 goto build_failed

set "LATEST_APK_FILE=%~dp0dist\LATEST_APK.txt"
if not exist "%LATEST_APK_FILE%" (
    echo [ERROR] Latest APK marker was not found: "%LATEST_APK_FILE%"
    goto failed
)
set /p "APK_NAME="<"%LATEST_APK_FILE%"
set "APK=%~dp0dist\%APK_NAME%"
if not exist "%APK%" (
    echo [ERROR] Built APK was not found: "%APK%"
    goto failed
)

echo.
echo [OK] APK: "%APK%"

if /I "%MODE%"=="build" goto done
if /I "%MODE%"=="install" goto install_session
if /I "%MODE%"=="copy" goto copy_to_phone

echo.
echo Select the next action:
echo   [B] Build only
echo   [I] Install now with Android Package Manager session
echo   [C] Copy to phone Download folder and open system installer
choice /C BIC /N /M "Enter B, I or C: "
if errorlevel 3 goto copy_to_phone
if errorlevel 2 goto install_session
goto done

:install_session
echo.
echo Installing with Android Package Manager session...
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\install-device.ps1"
if errorlevel 1 goto install_failed
echo [OK] Installation completed.
goto done

:copy_to_phone
set "ADB=%NAPCATHOST_TOOLCHAIN%\sdk\platform-tools\adb.exe"
set "REMOTE_APK=/sdcard/Download/%APK_NAME%"
if not exist "%ADB%" (
    echo [ERROR] ADB was not found: "%ADB%"
    goto failed
)

echo.
echo Checking the connected Android device...
"%ADB%" get-state 1>nul
if errorlevel 1 (
    echo [ERROR] No authorized ADB device is connected.
    goto failed
)

echo Copying APK to %REMOTE_APK% ...
"%ADB%" shell mkdir -p /sdcard/Download
if errorlevel 1 goto copy_failed
"%ADB%" push "%APK%" "%REMOTE_APK%"
if errorlevel 1 goto copy_failed

echo [OK] APK copied to the phone Download folder.
echo Opening the Android system installer...
"%ADB%" shell am start -a android.intent.action.VIEW -d "file://%REMOTE_APK%" -t application/vnd.android.package-archive -f 0x10000001
if errorlevel 1 (
    echo [WARN] The installer could not be opened automatically.
    echo        Unlock the phone, open Files ^> Download, and tap the APK.
) else (
    echo [OK] Complete the installation on the phone screen.
)
goto done

:usage
echo Usage: build.cmd [build^|install^|copy]
echo   build   Build and verify the APK only.
echo   install Build, then install with a Package Manager session.
echo   copy    Build, copy to Download, then open the system installer.
exit /b 2

:build_failed
echo.
echo [ERROR] APK build or verification failed.
goto failed

:install_failed
echo.
echo [ERROR] Package Manager installation failed.
goto failed

:copy_failed
echo.
echo [ERROR] Failed to copy the APK to the phone.
goto failed

:failed
if "%INTERACTIVE%"=="1" pause
exit /b 1

:done
echo.
echo All requested steps completed.
if "%INTERACTIVE%"=="1" pause
exit /b 0
