param(
    [Parameter(Mandatory = $true)]
    [string]$Version,

    [Parameter(Mandatory = $true)]
    [string]$GitHubOwner,

    [Parameter(Mandatory = $true)]
    [string]$GitHubRepo,

    [string]$ManifestBranch = "master",
    [string]$AntPath = "",
    [string]$ReleaseNotes = "",
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

function Normalize-ReleaseNotesText {
    param([string]$Text)

    if ([string]::IsNullOrWhiteSpace($Text)) {
        return ""
    }

    return ([regex]::Replace($Text.Trim(), '\s+', ' ')).Trim()
}

function Sanitize-ReleaseNotesText {
    param(
        [string]$Text,
        [string]$VersionText
    )

    $normalized = Normalize-ReleaseNotesText -Text $Text
    if ([string]::IsNullOrWhiteSpace($normalized)) {
        return ""
    }

    $versionPattern = [regex]::Escape($VersionText)
    $patterns = @(
        "^(?i)releases?\s+v?$versionPattern\s*:\s*",
        "^(?i)released\s+v?$versionPattern\s*:\s*",
        "^(?i)version\s+v?$versionPattern\s*:\s*",
        "^(?i)releases?\s+v?\d+(?:\.\d+)*\s*:\s*",
        "^(?i)released\s+v?\d+(?:\.\d+)*\s*:\s*",
        "^(?i)version\s+v?\d+(?:\.\d+)*\s*:\s*",
        "^(?i)refresh release\s+v?\d+(?:\.\d+)*\s*$",
        "^(?i)releases?\s+v?\d+(?:\.\d+)*\s*$"
    )

    foreach ($pattern in $patterns) {
        $normalized = [regex]::Replace($normalized, $pattern, "")
    }

    return Normalize-ReleaseNotesText -Text $normalized
}

function Get-GitHubReleaseNotesInfo {
    param(
        [string]$Owner,
        [string]$Repo,
        [string]$VersionText
    )

    $headers = @{
        "Accept" = "application/vnd.github+json"
        "User-Agent" = "BotGetLog_Multi-release-script"
    }
    $tagCandidates = @("v$VersionText", $VersionText) | Select-Object -Unique

    foreach ($tagName in $tagCandidates) {
        try {
            $uri = "https://api.github.com/repos/$Owner/$Repo/releases/tags/$tagName"
            $response = Invoke-RestMethod -Uri $uri -Headers $headers -Method Get -TimeoutSec 15 -ErrorAction Stop
            $body = Sanitize-ReleaseNotesText -Text $response.body -VersionText $VersionText
            if (-not [string]::IsNullOrWhiteSpace($body)) {
                return [pscustomobject]@{
                    Text = $body
                    Source = "GitHub release body ($tagName)"
                }
            }
        } catch {
            continue
        }
    }

    return $null
}

function Get-LocalTagReleaseNotesInfo {
    param(
        [string]$GitRoot,
        [string]$VersionText
    )

    $tagCandidates = @("v$VersionText", $VersionText) | Select-Object -Unique
    foreach ($tagName in $tagCandidates) {
        $tagContents = (& git -C $GitRoot for-each-ref "refs/tags/$tagName" "--format=%(contents)" 2>$null) | Out-String
        $normalized = Sanitize-ReleaseNotesText -Text $tagContents -VersionText $VersionText
        if (-not [string]::IsNullOrWhiteSpace($normalized)) {
            return [pscustomobject]@{
                Text = $normalized
                Source = "local git tag message ($tagName)"
            }
        }
    }

    return $null
}

function Get-LatestCommitReleaseNotesInfo {
    param(
        [string]$GitRoot,
        [string]$VersionText
    )

    $commitMessage = (& git -C $GitRoot log -1 "--pretty=%B" 2>$null) | Out-String
    $normalized = Sanitize-ReleaseNotesText -Text $commitMessage -VersionText $VersionText
    if (-not [string]::IsNullOrWhiteSpace($normalized)) {
        return [pscustomobject]@{
            Text = $normalized
            Source = "latest commit message"
        }
    }

    return $null
}

function Get-ReleaseNotesInfo {
    param(
        [string]$VersionText,
        [string]$ProvidedNotes,
        [string]$Owner,
        [string]$Repo,
        [string]$GitRoot
    )

    $normalized = Normalize-ReleaseNotesText -Text $ProvidedNotes
    if (-not [string]::IsNullOrWhiteSpace($normalized)) {
        return [pscustomobject]@{
            Text = $normalized
            Source = "manual override"
        }
    }

    $gitHubReleaseNotes = Get-GitHubReleaseNotesInfo -Owner $Owner -Repo $Repo -VersionText $VersionText
    if ($gitHubReleaseNotes) {
        return $gitHubReleaseNotes
    }

    $tagReleaseNotes = Get-LocalTagReleaseNotesInfo -GitRoot $GitRoot -VersionText $VersionText
    if ($tagReleaseNotes) {
        return $tagReleaseNotes
    }

    $commitReleaseNotes = Get-LatestCommitReleaseNotesInfo -GitRoot $GitRoot -VersionText $VersionText
    if ($commitReleaseNotes) {
        return $commitReleaseNotes
    }

    return [pscustomobject]@{
        Text = "Refresh build metadata, launcher release notes, and package artifacts for version $VersionText."
        Source = "default fallback"
    }
}

function New-WrappedCommentLines {
    param(
        [string]$Indent,
        [string]$Text,
        [int]$MaxWidth = 100
    )

    $prefix = "$Indent// "
    $availableWidth = [Math]::Max(30, $MaxWidth - $prefix.Length)
    $words = ([regex]::Replace($Text.Trim(), '\s+', ' ')).Split(' ')
    $lines = New-Object System.Collections.Generic.List[string]
    $current = ""

    foreach ($word in $words) {
        if ([string]::IsNullOrWhiteSpace($word)) {
            continue
        }

        if ([string]::IsNullOrEmpty($current)) {
            $current = $word
            continue
        }

        if (($current.Length + 1 + $word.Length) -le $availableWidth) {
            $current = "$current $word"
        } else {
            $lines.Add($prefix + $current)
            $current = $word
        }
    }

    if (-not [string]::IsNullOrEmpty($current)) {
        $lines.Add($prefix + $current)
    }

    return $lines
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
        "C:\Program Files\NetBeans-12.6\netbeans\extide\ant\bin\ant.bat",
        "C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    throw "Unable to find Ant. Install Ant, run from NetBeans environment, or pass -AntPath."
}

function Update-ApplicationVersion {
    param(
        [string]$ProjectPropertiesPath,
        [string]$NewVersion
    )

    $propertiesContent = Get-Content -LiteralPath $ProjectPropertiesPath -Raw
    if ($propertiesContent -match '(?m)^application\.version=.*$') {
        $updated = [regex]::Replace(
            $propertiesContent,
            '(?m)^application\.version=.*$',
            "application.version=$NewVersion",
            1
        )
    } else {
        $updated = $propertiesContent.TrimEnd() + "`r`napplication.version=$NewVersion`r`n"
    }

    Set-FileContentUtf8NoBom -Path $ProjectPropertiesPath -Content $updated
}

function Update-LauncherVersionComment {
    param(
        [string]$ProjectRoot,
        [string]$NewVersion,
        [string]$ReleaseNotesText
    )

    $launcherPath = Join-Path $ProjectRoot "src\com\java\launcher\BotToolLauncher.java"
    $lines = New-Object System.Collections.Generic.List[string]
    foreach ($line in Get-Content -LiteralPath $launcherPath) {
        $lines.Add($line)
    }

    $versionLineIndex = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match '^\s*private static final String FALLBACK_VERSION = "') {
            $versionLineIndex = $i
            break
        }
    }

    if ($versionLineIndex -lt 0) {
        throw "Unable to find FALLBACK_VERSION in src\com\java\launcher\BotToolLauncher.java"
    }

    $lines[$versionLineIndex] = [regex]::Replace(
        $lines[$versionLineIndex],
        'FALLBACK_VERSION = "[^"]+"',
        "FALLBACK_VERSION = `"$NewVersion`""
    )

    $indentMatch = [regex]::Match($lines[$versionLineIndex], '^(\s*)')
    $indent = if ($indentMatch.Success) { $indentMatch.Groups[1].Value } else { "    " }

    $removeStart = $versionLineIndex
    while ($removeStart -gt 0 -and $lines[$removeStart - 1].TrimStart().StartsWith("//")) {
        $removeStart--
    }

    if ($removeStart -lt $versionLineIndex -and -not $lines[$removeStart].TrimStart().StartsWith("// Version ")) {
        $removeStart = $versionLineIndex
    }

    if ($removeStart -lt $versionLineIndex) {
        $lines.RemoveRange($removeStart, $versionLineIndex - $removeStart)
        $versionLineIndex = $removeStart
    }

    $commentText = "Version ${NewVersion}: $ReleaseNotesText"
    $commentLines = New-WrappedCommentLines -Indent $indent -Text $commentText
    for ($i = $commentLines.Count - 1; $i -ge 0; $i--) {
        $lines.Insert($versionLineIndex, $commentLines[$i])
    }

    $content = [string]::Join("`r`n", $lines) + "`r`n"
    Set-FileContentUtf8NoBom -Path $launcherPath -Content $content
}

function Invoke-Build {
    param([string]$ProjectRoot, [string]$RequestedAntPath)

    $ant = Resolve-AntCommand -RequestedAntPath $RequestedAntPath
    & $ant -noinput -buildfile (Join-Path $ProjectRoot "build.xml") clean jar
    if ($LASTEXITCODE -ne 0) {
        throw "Build failed."
    }
}

function Assert-RequiredReleaseArtifacts {
    param([string]$ProjectRoot)

    $distDir = Join-Path $ProjectRoot "dist"
    if (-not (Test-Path -LiteralPath $distDir)) {
        throw "dist folder not found: $distDir"
    }

    $requiredArtifacts = @(
        "BotGetLog_TrueCorp.jar",
        "BotGetLog_DTAC.jar",
        "Bot Tool Launcher.jar",
        "Link_Optical.jar",
        "ARP.jar",
        "PTP.jar",
        "README.TXT",
        "defaults\UserInterface_Input.xlsx",
        "defaults\NEW_Site.csv",
        "updater\BotGetLog_Updater.jar"
    )

    $missingArtifacts = @()
    foreach ($relativePath in $requiredArtifacts) {
        $artifactPath = Join-Path $distDir $relativePath
        if (-not (Test-Path -LiteralPath $artifactPath)) {
            $missingArtifacts += $relativePath
        }
    }

    if ($missingArtifacts.Count -gt 0) {
        $missingList = $missingArtifacts -join ", "
        throw "Release package is incomplete. Missing required dist artifacts: $missingList"
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

function New-PortablePackage {
    param(
        [string]$ProjectRoot,
        [string]$VersionTag
    )

    $distDir = Join-Path $ProjectRoot "dist"
    if (-not (Test-Path -LiteralPath $distDir)) {
        throw "dist folder not found: $distDir"
    }

    $portableRoot = Join-Path $ProjectRoot "portable"
    $portableName = "BotGetLog_Multi_Portable_$VersionTag"
    $portableSource = Get-ChildItem -LiteralPath $portableRoot -Directory |
        Where-Object { $_.Name -ne $portableName } |
        Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName "jre\bin\java.exe") } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $portableSource) {
        throw "Portable JRE source not found under: $portableRoot"
    }

    $portableDir = Join-Path $portableRoot $portableName
    if (Test-Path -LiteralPath $portableDir) {
        Remove-Item -LiteralPath $portableDir -Recurse -Force
    }
    New-Item -ItemType Directory -Path $portableDir | Out-Null

    Copy-Item -LiteralPath (Join-Path $portableSource.FullName "jre") -Destination (Join-Path $portableDir "jre") -Recurse
    Copy-Item -Path (Join-Path $distDir "*") -Destination $portableDir -Recurse

    $portableOutputDirs = @(
        "_output\Total_Log",
        "_output\Bot_Work_Log",
        "_output\System_Log"
    )
    foreach ($relativeDir in $portableOutputDirs) {
        New-Item -ItemType Directory -Path (Join-Path $portableDir $relativeDir) -Force | Out-Null
    }

    $runBatContent = @'
@echo off
setlocal
cd /d "%~dp0"

if not exist "%~dp0jre\bin\java.exe" (
    echo Portable Java runtime not found in "%~dp0jre".
    pause
    exit /b 1
)

if not exist "%~dp0Bot Tool Launcher.jar" (
    echo Application JAR not found in "%~dp0".
    pause
    exit /b 1
)

"%~dp0jre\bin\java.exe" -Xms256m -Xmx2048m -XX:+UseG1GC -jar "%~dp0Bot Tool Launcher.jar"
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
    echo.
    echo Program exited with code %EXIT_CODE%.
    pause
)

exit /b %EXIT_CODE%
'@
    Set-FileContentUtf8NoBom -Path (Join-Path $portableDir "run.bat") -Content $runBatContent

    $releaseDir = Join-Path $ProjectRoot "outputs\releases\$VersionTag"
    New-Item -ItemType Directory -Force -Path $releaseDir | Out-Null

    $zipName = "BotGetLog_Multi-portable-$VersionTag.zip"
    $zipPath = Join-Path $releaseDir $zipName
    if (Test-Path -LiteralPath $zipPath) {
        Remove-Item -LiteralPath $zipPath -Force
    }

    Compress-Archive -Path (Join-Path $portableDir "*") -DestinationPath $zipPath -CompressionLevel Optimal

    return @{
        PortableDir = $portableDir
        PortableZip = $zipPath
        PortableSource = $portableSource.FullName
    }
}

function Write-UpdateManifest {
    param(
        [string]$ProjectRoot,
        [string]$VersionText,
        [string]$ReleaseNotesText,
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
        notes = "Released ${VersionText}: $ReleaseNotesText"
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

    $sourcePath = Join-Path $ProjectRoot "src\com\java\updater\AutoUpdateManager.java"
    $sourceContent = Get-Content -LiteralPath $sourcePath -Raw
    $pattern = 'https://raw\.githubusercontent\.com/[^"]+/update/version\.json'
    $replacement = $RawManifestUrl.Replace('\', '\\')

    if ($sourceContent -match $pattern) {
        $updated = [regex]::Replace($sourceContent, $pattern, $replacement, 1)
    } else {
        throw "Unable to find manifest URL placeholder in src\com\java\updater\AutoUpdateManager.java"
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

Write-Step "Resolving release notes"
$releaseNotesInfo = Get-ReleaseNotesInfo `
    -VersionText $Version `
    -ProvidedNotes $ReleaseNotes `
    -Owner $GitHubOwner `
    -Repo $GitHubRepo `
    -GitRoot $gitRoot
$normalizedReleaseNotes = $releaseNotesInfo.Text
Write-Host "Release notes source: $($releaseNotesInfo.Source)" -ForegroundColor DarkGray
Write-Host $normalizedReleaseNotes -ForegroundColor DarkGray

Write-Step "Updating project application version"
Update-ApplicationVersion -ProjectPropertiesPath (Join-Path $projectRoot "nbproject\project.properties") -NewVersion $Version

Write-Step "Updating launcher version comment"
Update-LauncherVersionComment -ProjectRoot $projectRoot -NewVersion $Version -ReleaseNotesText $normalizedReleaseNotes

Write-Step "Updating GitHub manifest URL in source"
$rawManifestUrl = "https://raw.githubusercontent.com/$GitHubOwner/$GitHubRepo/$ManifestBranch/update/version.json"
Update-ManifestUrlInSource -ProjectRoot $projectRoot -RawManifestUrl $rawManifestUrl

if (-not $SkipBuild) {
    Write-Step "Building project"
    Invoke-Build -ProjectRoot $projectRoot -RequestedAntPath $AntPath
} else {
    Write-Step "Skipping build as requested"
}

Write-Step "Validating required release artifacts"
Assert-RequiredReleaseArtifacts -ProjectRoot $projectRoot

Write-Step "Creating release ZIP"
$zipPath = New-ReleaseZip -ProjectRoot $projectRoot -VersionTag $Version
$zipName = Split-Path -Leaf $zipPath

Write-Step "Creating portable package"
$portableInfo = New-PortablePackage -ProjectRoot $projectRoot -VersionTag $Version

Write-Step "Calculating SHA-256"
$sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $zipPath).Hash

Write-Step "Writing update/version.json"
$manifestInfo = Write-UpdateManifest `
    -ProjectRoot $projectRoot `
    -VersionText $Version `
    -ReleaseNotesText $normalizedReleaseNotes `
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
Write-Host "Portable folder: $($portableInfo.PortableDir)"
Write-Host "Portable ZIP: $($portableInfo.PortableZip)"
Write-Host "Portable JRE source: $($portableInfo.PortableSource)"
Write-Host "SHA256: $sha256"
Write-Host "Manifest: $($manifestInfo.ManifestPath)"
Write-Host "Release URL: $($manifestInfo.ReleaseUrl)"
Write-Host "Raw manifest URL: $($manifestInfo.RawManifestUrl)"
