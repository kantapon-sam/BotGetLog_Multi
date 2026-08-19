param(
    [string]$TaskName = "BotGetLog TRUE Link Optical Auto",
    [string]$DailyAt = "02:00",
    [string]$RunBat = "",
    [string]$MapViewerInputDir = ""
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptRoot

if ([string]::IsNullOrWhiteSpace($RunBat)) {
    $RunBat = Join-Path $projectRoot "Run_TRUE_LinkOptical_Auto_AllSite_To_MapViewer.bat"
}

$runBatFull = [System.IO.Path]::GetFullPath($RunBat)
if (-not (Test-Path -LiteralPath $runBatFull -PathType Leaf)) {
    throw "Run BAT not found: $runBatFull"
}

try {
    $triggerTime = [DateTime]::ParseExact($DailyAt, "HH:mm", [Globalization.CultureInfo]::InvariantCulture)
} catch {
    throw "DailyAt must use HH:mm format, for example 02:00"
}

$arguments = '/c ""' + $runBatFull + '""'
if (-not [string]::IsNullOrWhiteSpace($MapViewerInputDir)) {
    $resolvedInput = [System.IO.Path]::GetFullPath($MapViewerInputDir)
    $arguments = '/c "set ""MAPVIEWER_INPUT_DIR=' + $resolvedInput + '"" && ""' + $runBatFull + '"""'
}

$action = New-ScheduledTaskAction -Execute "cmd.exe" -Argument $arguments -WorkingDirectory $projectRoot
$trigger = New-ScheduledTaskTrigger -Daily -At $triggerTime
$principal = New-ScheduledTaskPrincipal -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) `
    -LogonType Interactive `
    -RunLevel LeastPrivilege
$settings = New-ScheduledTaskSettingsSet `
    -MultipleInstances IgnoreNew `
    -StartWhenAvailable `
    -ExecutionTimeLimit (New-TimeSpan -Hours 12)

Register-ScheduledTask `
    -TaskName $TaskName `
    -Action $action `
    -Trigger $trigger `
    -Principal $principal `
    -Settings $settings `
    -Force | Out-Null

Write-Host ("Installed scheduled task: {0}" -f $TaskName)
Write-Host ("Daily time: {0}" -f $DailyAt)
Write-Host ("Run BAT: {0}" -f $runBatFull)
if (-not [string]::IsNullOrWhiteSpace($MapViewerInputDir)) {
    Write-Host ("MAPVIEWER_INPUT_DIR: {0}" -f ([System.IO.Path]::GetFullPath($MapViewerInputDir)))
}
Write-Host "Note: this task runs when the current Windows user is logged on."
