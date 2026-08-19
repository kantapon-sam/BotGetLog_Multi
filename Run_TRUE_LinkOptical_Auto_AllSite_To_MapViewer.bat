@echo off
setlocal
cd /d "%~dp0"

rem Optional: set MAPVIEWER_INPUT_DIR to the server LLDP_MapViewer _input folder.
rem Example:
rem set "MAPVIEWER_INPUT_DIR=D:\LLDP_MapViewer\_input"

powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\run_true_linkoptical_auto_allsite.ps1" %*
set "EXIT_CODE=%errorlevel%"

echo.
if not "%EXIT_CODE%"=="0" (
    echo TRUE Link Optical Auto ended with code %EXIT_CODE%.
)
exit /b %EXIT_CODE%
