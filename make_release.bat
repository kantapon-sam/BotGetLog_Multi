@echo off
setlocal
cd /d "%~dp0"

set "OWNER=kantapon-sam"
set "REPO=BotGetLog_Multi"
set "ANT_PATH=C:\Program Files\NetBeans-12.6\netbeans\extide\ant\bin\ant.bat"

if "%~1"=="" (
    set /p VERSION=Enter version example 1.0.1: 
) else (
    set "VERSION=%~1"
)

if "%VERSION%"=="" (
    echo Version is required.
    pause
    exit /b 1
)

echo.
echo Creating release package for version %VERSION% ...
echo.

powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0release.ps1" -Version %VERSION% -GitHubOwner %OWNER% -GitHubRepo %REPO% -AntPath "%ANT_PATH%"
if errorlevel 1 (
    echo.
    echo Release creation failed.
    pause
    exit /b 1
)

echo.
echo Release package created successfully.
echo ZIP: outputs\releases\%VERSION%\BotGetLog_Multi-dist-%VERSION%.zip
echo.
pause
