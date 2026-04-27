# Auto Update with GitHub

This project now includes a simple Java auto-update flow designed for GitHub-hosted releases.

## Release architecture

1. The app reads its current version from `Implementation-Version` in `manifest.mf`.
2. At startup, `AutoUpdateManager` downloads `update/version.json` from GitHub.
3. If the manifest version is newer, the user is prompted to update.
4. The app downloads a ZIP that contains the distributable `dist` contents.
5. The app launches `dist/updater/BotGetLog_Updater.jar`.
6. The updater replaces files in the installed `dist` folder and relaunches the main JAR.

## Files added

- `src/com/java/myapp/AutoUpdateManager.java`
- `src/com/java/myapp/UpdaterMain.java`
- `src/com/java/myapp/AppMetadata.java`
- `update/version.template.json`

## What to publish on GitHub

Each release should include a ZIP built from the contents of the local `dist` folder.

Suggested ZIP layout:

```text
BotGetLog_Multi.jar
README.TXT
lib/...
updater/BotGetLog_Updater.jar
```

Do not zip the parent `dist` folder itself. Zip the files inside `dist` so the updater can copy them directly into the installed `dist` folder.

## One-time configuration

Update the placeholder URL in `src/com/java/myapp/AutoUpdateManager.java`:

```java
https://raw.githubusercontent.com/YOUR_GITHUB_USERNAME/YOUR_REPOSITORY/main/update/version.json
```

You can also override it at runtime:

```text
-Dbotgetlog.update.manifestUrl=https://raw.githubusercontent.com/your-org/your-repo/main/update/version.json
```

## Release steps

1. Increase `Implementation-Version` in `manifest.mf`.
2. Build the project.
3. Zip the generated `dist` contents.
4. Calculate the ZIP SHA-256.
5. Copy `update/version.template.json` to `update/version.json` and fill in the new version, release URL, and SHA-256.
6. Commit `update/version.json` to GitHub.
7. Create a GitHub Release and upload the ZIP asset.

## PowerShell release helper

Use `release.ps1` from the project root to automate the repetitive parts:

```powershell
.\release.ps1 -Version 5.5.0 -GitHubOwner your-org -GitHubRepo your-repo
```

What it does:

- updates `manifest.mf`
- updates the raw GitHub manifest URL in `AutoUpdateManager.java`
- builds with Ant unless `-SkipBuild` is passed
- creates `outputs/releases/<version>/BotGetLog_Multi-dist-<version>.zip`
- calculates SHA-256
- writes `update/version.json`

Optional flags:

- `-ManifestBranch main`
- `-AntPath "C:\path\to\ant.bat"`
- `-SkipBuild`
- `-CreateGitTag`

## Notes

- The manifest file is fetched from raw GitHub, not the GitHub API. This avoids API rate-limit friction for desktop clients.
- The updater is intentionally a separate JAR so the main app never needs to overwrite its own running file.
- If the manifest URL still contains placeholders, the update check is skipped safely.
