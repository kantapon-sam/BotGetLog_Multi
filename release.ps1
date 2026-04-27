param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [string]$GitHubOwner,

    [Parameter(Mandatory = $true)]
    [string]$GitHubRepo,

    [string]$ManifestBranch = "master",
    [string]$AntPath = "",
    [switch]$SkipBuild,
    [switch]$CreateGitTag
)

$ErrorActionPreference = "Stop"

function Write-Step {
    param([string]$Message)
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Set-FileContentUtf8NoBom {
    param(
        [string]$Path,
        [string]$Content
    )

    $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($Path, $Content, $utf8NoBom)
}

function Resolve-AntCommand {
    param([string]$RequestedAntPath)

    if ($RequestedAntPath) {
        if (-not (Test-Path -LiteralPath $RequestedAntPath)) {
            throw "Ant path not found: $RequestedAntPath"
        }
        return $RequestedAntPath
    }

    $antCommand = Get-Command ant -ErrorAction SilentlyContinue
    if ($antCommand) {
        return $antCommand.Source
    }

    $candidates = @(
        "C:\Program Files\NetBeans-25\netbeans\extide\ant\bin\ant.bat",
        "C:\Program Files\NetBeans-24\netbeans\extide\ant\bin\ant.bat",
        "C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw "Unable to find Ant. Install Ant, run from NetBeans environment, or pass -AntPath."
}

function Update-ImplementationVersion {
    param(
        [string]$ManifestPath,
        [string]$NewVersion
    )

    $manifestContent = Get-Content -LiteralPath $ManifestPath -Raw
    if ($manifestContent -match 'Implementation-Version:\s*.+') {
        $updated = [regex]::Replace(
            $manifestContent,
            'Implementation-Version:\s*.+',
            "Implementation-Version: $NewVersion",
            1
        )
    } else {
        $updated = $manifestContent.TrimEnd() + "`r`nImplementation-Version: $NewVersion`r`n"
    }

    Set-FileContentUtf8NoBom -Path $ManifestPath -Content $updated
}

function Invoke-Build {
    param([string]$ProjectRoot, [string]$RequestedAntPath)

    $ant = Resolve-AntCommand -RequestedAntPath $RequestedAntPath
    & $ant -noinput -buildfile (Join-Path $ProjectRoot "build.xml") clean jar
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed."
    }
}

function New-ReleaseZip {
    param(
        [string]$ProjectRoot,
        [string]$VersionTag
    )

    $distDir = Join-Path $ProjectRoot "dist"
    if (-not (Test-Path -LiteralPath $distDir)) {
        throw "dist folder not found: $distDir"
    }

    $updaterJar = Join-Path $distDir "updater\BotGetLog_Updater.jar"
    if (-not (Test-Path -LiteralPath $updaterJar)) {
        throw "Updater jar not found in dist: $updaterJar"
    }

    $releaseDir = Join-Path $ProjectRoot "outputs\releases\$VersionTag"
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null

    $zipName = "BotGetLog_Multi-dist-$VersionTag.zip"
    $zipPath = Join-Path $releaseDir $zipName
    if (Test-Path -LiteralPath $zipPath) {
        Remove-Item -LiteralPath $zipPath -Force
    }

    Compress-Archive -Path (Join-Path $distDir "*") -DestinationPath $zipPath -CompressionLevel Optimal
    return $zipPath
}

function Write-UpdateManifest {
    param(
        [string]$ProjectRoot,
        [string]$VersionText,
        [string]$Owner,
        [string]$Repo,
        [string]$Branch,
        [string]$ZipName,
        [string]$Sha256
    )

    $releaseTag = "v$VersionText"
    $releaseUrl = "https://github.com/$Owner/$Repo/releases/download/$releaseTag/$ZipName"
    $manifestObject = [ordered]@{
        version = $VersionText
        url = $releaseUrl
        sha256 = $Sha256.ToLowerInvariant()
        notes = "Released via release.ps1 on $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    }

    $manifestJson = $manifestObject | ConvertTo-Json -Depth 3
    $manifestPath = Join-Path $ProjectRoot "update\version.json"
    Set-FileContentUtf8NoBom -Path $manifestPath -Content ($manifestJson + "`r`n")

    $rawManifestUrl = "https://raw.githubusercontent.com/$Owner/$Repo/$Branch/update/version.json"
    return @{
        ManifestPath = $manifestPath
        RawManifestUrl = $rawManifestUrl
        ReleaseUrl = $releaseUrl
    }
}

function Update-ManifestUrlInSource {
    param(
        [string]$ProjectRoot,
        [string]$RawManifestUrl
    )

    $sourcePath = Join-Path $ProjectRoot "src\com\java\myapp\AutoUpdateManager.java"
    $sourceContent = Get-Content -LiteralPath $sourcePath -Raw
    $pattern = 'https://raw\.githubusercontent\.com/[^"]+/update/version\.json'
    $replacement = $RawManifestUrl.Replace('\', '\\')

    if ($sourceContent -match $pattern) {
        $updated = [regex]::Replace($sourceContent, $pattern, $replacement, 1)
    } else {
        throw "Unable to find manifest URL placeholder in AutoUpdateManager.java"
    }

    Set-FileContentUtf8NoBom -Path $sourcePath -Content $updated
}

function Ensure-CleanGitRoot {
    param([string]$ProjectRoot)

    $gitRoot = (git -C $ProjectRoot rev-parse --show-toplevel).Trim()
    if (-not $gitRoot) {
        throw "This folder is not inside a git repository."
    }

    return $gitRoot
}

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gitRoot = Ensure-CleanGitRoot -ProjectRoot $projectRoot

Write-Step "Updating manifest version"
Update-ImplementationVersion -ManifestPath (Join-Path $projectRoot "manifest.mf") -NewVersion $Version

Write-Step "Updating GitHub manifest URL in source"
$rawManifestUrl = "https://raw.githubusercontent.com/$GitHubOwner/$GitHubRepo/$ManifestBranch/update/version.json"
Update-ManifestUrlInSource -ProjectRoot $projectRoot -RawManifestUrl $rawManifestUrl

if (-not $SkipBuild) {
    Write-Step "Building project"
    Invoke-Build -ProjectRoot $projectRoot -RequestedAntPath $AntPath
} else {
    Write-Step "Skipping build as requested"
}

Write-Step "Creating release ZIP"
$zipPath = New-ReleaseZip -ProjectRoot $projectRoot -VersionTag $Version
$zipName = Split-Path -Leaf $zipPath

Write-Step "Calculating SHA-256"
$sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipPath).Hash

Write-Step "Writing update/version.json"
$manifestInfo = Write-UpdateManifest `
    -ProjectRoot $projectRoot `
    -VersionText $Version `
    -Owner $GitHubOwner `
    -Repo $GitHubRepo `
    -Branch $ManifestBranch `
    -ZipName $zipName `
    -Sha256 $sha256

if ($CreateGitTag) {
    Write-Step "Creating local git tag"
    git -C $gitRoot tag "v$Version"
}

Write-Host ""
Write-Host "Release package ready:" -ForegroundColor Green
Write-Host "ZIP: $zipPath"
Write-Host "SHA256: $sha256"
Write-Host "Manifest: $($manifestInfo.ManifestPath)"
Write-Host "Release URL: $($manifestInfo.ReleaseUrl)"
Write-Host "Raw manifest URL: $($manifestInfo.RawManifestUrl)"
