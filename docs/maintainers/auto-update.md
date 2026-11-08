# Desktop Auto Update

[Documentation index](../README.md) · [Release workflow](releasing.md)

The launcher checks for a newer version at startup and asks the user before
installing it. It reads its installed version from the JAR's
`Implementation-Version`, then fetches the published [update manifest](../../update/version.json)
from the `master` branch on raw GitHub. Users can move directly to the latest
published version; intermediate versions need not be installed separately.

## Update flow

1. Fetch the manifest and compare versions.
2. Enrich the update prompt with GitHub Release notes.
3. After user confirmation, download the dist ZIP and verify its SHA-256.
4. Start the separate `updater/BotGetLog_Updater.jar` process so it can replace
   application files after the launcher exits.
5. Apply the package and relaunch the application.

The downloader retries failed downloads and can use the GitHub release-asset API
when the normal download route fails. A failed checksum prevents installation.
The updater keeps the operational `UserInterface_Input.xlsx`, `_output` and
`launcher-data`, including saved credentials and ZTE Enable settings. Application
JARs, `defaults`, `lib`, `updater` and `README.TXT` are updated. Existing workbook
data is retained; supported command-set migrations are handled by the updater.

The dist ZIP contains the application files at its root, including
`Bot Tool Launcher.jar`, `BotGetLog_TrueCorp.jar`, `BotGetLog_DTAC.jar`,
`README.TXT`, `defaults/`, `lib/` and `updater/`. Do not wrap them in another
`dist/` directory. The portable ZIP is for a complete Windows installation and
also includes Java and `run.bat`; the manifest points to the dist ZIP.

## Source and diagnostics

- [AutoUpdateManager.java](../../src/com/java/updater/AutoUpdateManager.java): startup check, manifest, release notes and verified download
- [UpdaterMain.java](../../src/com/java/updater/UpdaterMain.java): package installation and preservation rules
- [AppMetadata.java](../../src/com/java/shared/AppMetadata.java): installed version and application paths
- [Manifest template](../../update/version.template.json): version, asset URL, SHA-256 and notes

Update diagnostics are written to `_output/System_Log/update.log`. A test
installation can override the manifest with the JVM property
`-Dbotgetlog.update.manifestUrl=<manifest-url>`.

Publish and verify release assets before publishing the manifest. Test both
manifest download and release-note enrichment on Java 8: clients before 1.2.21
can fail on a long public release body. Keep that body below 500 characters and
link to detailed documentation. See the [release workflow](releasing.md).
