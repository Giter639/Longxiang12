@echo off
title 专注有度 启动器
setlocal enabledelayedexpansion

set "SDK=%LOCALAPPDATA%\Android\Sdk"
set "ADB=%SDK%\platform-tools\adb.exe"
set "EMU=%SDK%\emulator\emulator.exe"
set "AVD=PixelTest"
set "PKG=com.focusdu.app"
set "ACT=%PKG%/.MainActivity"
set "APK=%~dp0FocusDu\app\build\outputs\apk\debug\app-debug.apk"

if not exist "%ADB%" goto err_adb
if not exist "%APK%" goto err_apk

set "HASDEV="
for /f "tokens=1,2 skip=1" %%a in ('"%ADB%" devices 2^>nul') do (
    if "%%b"=="device" set "HASDEV=1"
)

if not defined HASDEV (
    if not exist "%EMU%" (
        echo [启动器] 找不到模拟器，请插上 OPPO 真机（打开 USB 调试）后重试
        pause
        exit /b 1
    )
    echo [启动器] 正在启动模拟器（首次约 30-60 秒，请勿关闭弹出的窗口）...
    start "" "%EMU%" -avd %AVD%
    set /a w=0
    :waitboot
    "%ADB%" wait-for-device 2>nul
    ping -n 3 127.0.0.1 >nul
    set /a w+=2
    "%ADB%" shell getprop sys.boot_completed 2>nul | findstr "1" >nul
    if errorlevel 1 (
        if !w! lss 120 goto waitboot
        echo [启动器] 模拟器启动超时，请查看模拟器窗口
        pause
        exit /b 1
    )
)

rem 点亮屏幕并解锁（模拟器闲置熄屏的场景）
"%ADB%" shell input keyevent KEYCODE_WAKEUP >nul 2>&1
"%ADB%" shell input keyevent 82 >nul 2>&1

rem 未安装则安装一次（已装则跳过，秒开）
"%ADB%" shell pm list packages 2>nul | findstr "%PKG%" >nul
if errorlevel 1 (
    echo [启动器] 首次运行，安装 APK ...
    "%ADB%" install -r "%APK%" >nul 2>&1
)

rem 打开 App
"%ADB%" shell am start -n %ACT% >nul 2>&1
exit /b 0

:err_adb
echo [启动器] 找不到 adb：%SDK%
echo          请确认已安装 Android Studio（SDK 默认路径）
pause
exit /b 1

:err_apk
echo [启动器] 找不到 APK，请先在 Android Studio 跑一次 Run：
echo          %APK%
pause
exit /b 1
