@echo off
setlocal
cd /d "%~dp0"

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

set "TAG=v%VERSION%"

echo.
echo This will delete tag %TAG% locally and on origin.
echo If a GitHub Release already exists for %TAG%, delete that release separately on GitHub.
echo.
set /p CONFIRM=Type YES to continue: 
if /I not "%CONFIRM%"=="YES" (
    echo Cancelled.
    pause
    exit /b 0
)

echo.
echo Checking local tag %TAG% ...
git rev-parse "%TAG%" >nul 2>nul
if errorlevel 1 (
    echo Local tag %TAG% not found. Skipping local delete.
) else (
    echo Deleting local tag %TAG% ...
    git tag -d "%TAG%"
    if errorlevel 1 (
        echo Failed to delete local tag.
        pause
        exit /b 1
    )
)

echo.
echo Checking remote tag %TAG% on origin ...
git ls-remote --tags origin "refs/tags/%TAG%" | findstr /r /c:".*" >nul
if errorlevel 1 (
    echo Remote tag %TAG% not found on origin. Skipping remote delete.
) else (
    echo Deleting remote tag %TAG% from origin ...
    git push origin ":refs/tags/%TAG%"
    if errorlevel 1 (
        echo Failed to delete remote tag.
        pause
        exit /b 1
    )
)

echo.
echo Tag cleanup for %TAG% completed.
echo Reminder: if GitHub Release %TAG% was already published, remove it manually on GitHub too.
pause
