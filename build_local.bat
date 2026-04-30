@echo off
setlocal
cd /d "%~dp0"

set "ANT_PATH=C:\Program Files\NetBeans-12.6\netbeans\extide\ant\bin\ant.bat"
set "USED_ANT="

if /I "%~1"=="--javac-only" goto javac_fallback

if exist "%ANT_PATH%" (
    set "USED_ANT=%ANT_PATH%"
) else (
    where ant >nul 2>nul
    if not errorlevel 1 set "USED_ANT=ant"
)

if defined USED_ANT (
    echo [build] Running Ant clean compile...
    call "%USED_ANT%" -noinput -buildfile "%~dp0build.xml" clean compile
    if errorlevel 1 (
        echo [build] Ant compile failed.
        exit /b 1
    )
    echo [build] Ant clean compile completed successfully.
    exit /b 0
)

:javac_fallback
echo [build] Ant not found. Falling back to javac verification compile...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
    "$ErrorActionPreference='Stop';" ^
    "$cp=(Get-ChildItem -Path 'lib' -Filter *.jar | ForEach-Object { $_.FullName }) -join ';';" ^
    "$out=Join-Path 'build' 'tmp-compile';" ^
    "if (Test-Path $out) { Remove-Item -Recurse -Force $out };" ^
    "New-Item -ItemType Directory -Path $out | Out-Null;" ^
    "$sources=Get-ChildItem -Recurse -Path 'src' -Filter *.java | ForEach-Object { $_.FullName };" ^
    "if(-not $sources){ throw 'No Java source files found.' };" ^
    "& javac -encoding UTF-8 -source 1.8 -target 1.8 -cp $cp -d $out $sources;" ^
    "if($LASTEXITCODE -ne 0){ exit $LASTEXITCODE }"
if errorlevel 1 (
    echo [build] javac verification compile failed.
    exit /b 1
)

echo [build] javac verification compile completed successfully.
echo [build] Output: build\tmp-compile
exit /b 0
