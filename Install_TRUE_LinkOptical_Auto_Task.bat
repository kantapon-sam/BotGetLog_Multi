@echo off
setlocal
cd /d "%~dp0"

rem Default daily time is 02:00.
rem Example with custom time:
rem powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install_true_linkoptical_auto_task.ps1" -DailyAt 03:30

powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install_true_linkoptical_auto_task.ps1" %*
set "EXIT_CODE=%errorlevel%"

echo.
if not "%EXIT_CODE%"=="0" (
    echo Scheduled task install ended with code %EXIT_CODE%.
)
pause
exit /b %EXIT_CODE%
