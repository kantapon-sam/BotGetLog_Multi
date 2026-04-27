@echo off
setlocal

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

start "" "https://github.com/kantapon-sam/BotGetLog_Multi/releases/new?tag=v%VERSION%"
