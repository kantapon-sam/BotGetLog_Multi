param(
    [string]$BotDistDir = "",
    [string]$MapViewerInputDir = "",
    [string]$JavaExe = "java",
    [switch]$SkipImport
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptRoot

if ([string]::IsNullOrWhiteSpace($BotDistDir)) {
    $BotDistDir = Join-Path $projectRoot "dist"
}
if ([string]::IsNullOrWhiteSpace($MapViewerInputDir)) {
    if (-not [string]::IsNullOrWhiteSpace($env:MAPVIEWER_INPUT_DIR)) {
        $MapViewerInputDir = $env:MAPVIEWER_INPUT_DIR
    } else {
        $MapViewerInputDir = Join-Path (Split-Path -Parent $projectRoot) "LLDP_MapViewer\_input"
    }
}

$botDistFull = [System.IO.Path]::GetFullPath($BotDistDir)
$jPTPath = Join-Path $botDistFull "BotGetLog_TrueCorp.jar"
if (-not (Test-Path -LiteralPath $jPTPath -PathType Leaf)) {
    throw "BotGetLog_TrueCorp.jar not found: $jPTPath"
}

$logDir = Join-Path $botDistFull "_output\System_Log"
if (-not (Test-Path -LiteralPath $logDir)) {
    New-Item -ItemType Directory -Path $logDir | Out-Null
}
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runLog = Join-Path $logDir ("true-linkoptical-auto-" + $timestamp + ".log")

function Write-RunLog {
    param([Parameter(Mandatory = $true)][string]$Message)
    $line = "{0} {1}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Message
    Write-Host $line
    Add-Content -LiteralPath $runLog -Value $line
}

Write-RunLog "Starting TRUE Link Optical Auto All Site."
Write-RunLog ("BotDistDir={0}" -f $botDistFull)
Write-RunLog ("MapViewerInputDir={0}" -f ([System.IO.Path]::GetFullPath($MapViewerInputDir)))

$oldLocation = Get-Location
try {
    Set-Location -LiteralPath $botDistFull
    & $JavaExe -Xms256m -Xmx2048m -XX:+UseG1GC -jar $jPTPath --auto-link-optical --link-optical-all 2>&1 |
        Tee-Object -FilePath $runLog -Append
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        throw "TRUE Link Optical Auto exited with code $exitCode"
    }
} finally {
    Set-Location -LiteralPath $oldLocation
}

Write-RunLog "TRUE Link Optical Auto finished."

if ($SkipImport) {
    Write-RunLog "SkipImport was set. CSV import to MapViewer was skipped."
    exit 0
}

$copyScript = Join-Path $scriptRoot "copy_true_linkoptical_to_mapviewer.ps1"
& powershell -NoLogo -NoProfile -ExecutionPolicy Bypass -File $copyScript `
    -SourceDir (Join-Path $botDistFull "_output\LLDP_Neighbor") `
    -DestinationDir $MapViewerInputDir 2>&1 |
    Tee-Object -FilePath $runLog -Append

$copyExitCode = $LASTEXITCODE
if ($copyExitCode -ne 0) {
    throw "Copy to MapViewer exited with code $copyExitCode"
}

Write-RunLog "Finished TRUE Link Optical Auto All Site workflow."
