param(
    [string]$SourceDir = "",
    [string]$DestinationDir = "",
    [int]$LldpFileCount = 2,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptRoot

if ([string]::IsNullOrWhiteSpace($SourceDir)) {
    $SourceDir = Join-Path $projectRoot "dist\_output\LLDP_Neighbor"
    if (-not (Test-Path -LiteralPath $SourceDir -PathType Container)) {
        $SourceDir = Join-Path $projectRoot "_output\LLDP_Neighbor"
    }
}

if ([string]::IsNullOrWhiteSpace($DestinationDir)) {
    if (-not [string]::IsNullOrWhiteSpace($env:MAPVIEWER_INPUT_DIR)) {
        $DestinationDir = $env:MAPVIEWER_INPUT_DIR
    } else {
        $DestinationDir = Join-Path (Split-Path -Parent $projectRoot) "LLDP_MapViewer\_input"
    }
}

function Get-FullPath {
    param([Parameter(Mandatory = $true)][string]$Path)
    return [System.IO.Path]::GetFullPath($Path)
}

function Get-LatestFiles {
    param(
        [Parameter(Mandatory = $true)][string]$Folder,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][int]$Count
    )

    if ($Count -lt 1) {
        $Count = 1
    }

    return @(Get-ChildItem -LiteralPath $Folder -File -Filter $Pattern -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime, Name -Descending |
        Select-Object -First $Count)
}

function Copy-FileAtomically {
    param(
        [Parameter(Mandatory = $true)][System.IO.FileInfo]$SourceFile,
        [Parameter(Mandatory = $true)][string]$DestinationRoot,
        [Parameter(Mandatory = $true)][bool]$WhatIfOnly
    )

    $destRootFull = Get-FullPath $DestinationRoot
    $destRootWithSlash = $destRootFull.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    $targetPath = Get-FullPath (Join-Path $destRootFull $SourceFile.Name)

    if (-not $targetPath.StartsWith($destRootWithSlash, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to copy outside destination folder: $targetPath"
    }

    if ($WhatIfOnly) {
        Write-Host ("[DRYRUN] {0} -> {1}" -f $SourceFile.FullName, $targetPath)
        return
    }

    if (-not (Test-Path -LiteralPath $destRootFull)) {
        New-Item -ItemType Directory -Path $destRootFull | Out-Null
    }

    $tempPath = Join-Path $destRootFull ("." + $SourceFile.Name + ".tmp." + [System.Guid]::NewGuid().ToString("N"))
    Copy-Item -LiteralPath $SourceFile.FullName -Destination $tempPath -Force
    Move-Item -LiteralPath $tempPath -Destination $targetPath -Force
    Write-Host ("[COPY] {0} -> {1}" -f $SourceFile.Name, $targetPath)
}

$sourceFull = Get-FullPath $SourceDir
$destinationFull = Get-FullPath $DestinationDir

if (-not (Test-Path -LiteralPath $sourceFull -PathType Container)) {
    throw "Source folder not found: $sourceFull"
}

Write-Host "TRUE Link Optical -> LLDP MapViewer"
Write-Host ("Source:      {0}" -f $sourceFull)
Write-Host ("Destination: {0}" -f $destinationFull)

$selected = New-Object System.Collections.Generic.List[System.IO.FileInfo]
$seen = New-Object "System.Collections.Generic.HashSet[string]" ([System.StringComparer]::OrdinalIgnoreCase)

foreach ($file in (Get-LatestFiles -Folder $sourceFull -Pattern "DataLLDP_Neighbor_*.csv" -Count $LldpFileCount)) {
    if ($seen.Add($file.FullName)) {
        $selected.Add($file)
    }
}
foreach ($file in (Get-LatestFiles -Folder $sourceFull -Pattern "DataPort_*.csv" -Count 1)) {
    if ($seen.Add($file.FullName)) {
        $selected.Add($file)
    }
}
foreach ($file in (Get-LatestFiles -Folder $sourceFull -Pattern "DataDescription_MB_*.csv" -Count 1)) {
    if ($seen.Add($file.FullName)) {
        $selected.Add($file)
    }
}

if ($selected.Count -eq 0) {
    throw "No Link Optical CSV files found in source folder."
}

Write-Host ("Selected {0} file(s)." -f $selected.Count)
foreach ($file in $selected) {
    Copy-FileAtomically -SourceFile $file -DestinationRoot $destinationFull -WhatIfOnly ([bool]$DryRun)
}

$latLongPath = Join-Path $destinationFull "Lat&Long.csv"
if (-not (Test-Path -LiteralPath $latLongPath -PathType Leaf)) {
    Write-Warning "Lat&Long.csv was not found in the destination. LLDP Map Viewer needs this file to draw the map."
}

if ($DryRun) {
    Write-Host "Dry run finished. No files were changed."
} else {
    Write-Host "Import finished. Refresh LLDP Map Viewer after login; the app will pick the newest DataLLDP_Neighbor_*.csv."
}
