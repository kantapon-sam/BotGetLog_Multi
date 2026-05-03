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

if "%~2"=="" (
    set /p MESSAGE=Enter commit message [Release v%VERSION%]: 
) else (
    set "MESSAGE=%~2"
)

if "%MESSAGE%"=="" set "MESSAGE=Release v%VERSION%"

git rev-parse "v%VERSION%" >nul 2>nul
if not errorlevel 1 (
    echo.
    echo Tag v%VERSION% already exists.
    echo Use a new version number or delete the old tag first.
    pause
    exit /b 1
)

echo.
echo Staging files...
git add manifest.mf build.xml release.ps1 make_release.bat push_release.bat build_local.bat BotGetLog_Multi.bat UserInterface_Input.xlsx update\version.json update\version.template.json nbproject\project.properties lib src\com\java
if errorlevel 1 (
    echo git add failed.
    pause
    exit /b 1
)

echo.
echo Creating commit...
git commit -m "%MESSAGE%"
if errorlevel 1 (
    echo git commit failed. Check if there are changes to commit.
    pause
    exit /b 1
)

echo.
echo Pushing master...
git push origin master
if errorlevel 1 (
    echo git push master failed.
    pause
    exit /b 1
)

echo.
echo Creating tag v%VERSION% ...
git tag v%VERSION%
if errorlevel 1 (
    echo git tag failed.
    pause
    exit /b 1
)

echo.
echo Pushing tag v%VERSION% ...
git push origin v%VERSION%
if errorlevel 1 (
    echo git push tag failed.
    pause
    exit /b 1
)

echo.
echo Git push and tag completed successfully.
pause
