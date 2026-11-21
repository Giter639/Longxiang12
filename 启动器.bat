@echo off
setlocal
title FocusDu Launcher
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0FocusDu\tools\launch_focusdu.ps1"
set "EXIT_CODE=%ERRORLEVEL%"
if not "%EXIT_CODE%"=="0" pause
exit /b %EXIT_CODE%
