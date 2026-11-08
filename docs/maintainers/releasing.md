# Release workflow

[Documentation index](../README.md) · [Auto Update](auto-update.md) · [Repository agreements](../../AGENTS.md)

A user-facing Bot version is complete when GitHub Release assets, the Desktop
update manifest and the matching TrueLogs runtime have all been verified.
Scheduled-Bot deployment is a separate activation step when included in the task.

## Prepare and validate

1. Update the application version consistently in
   [project.properties](../../nbproject/project.properties),
   [manifest.mf](../../manifest.mf) and any version-specific launcher notes.
   Build with JDK 8 and the project's Ant build. Run regressions relevant to the
   collector, launcher or updater changes against the resulting artifacts.
2. Use [release.ps1](../../release.ps1) to prepare dist and portable packages.
   Review its parameters and local publisher configuration before running it.
   The helper can update the local manifest and tag; that does not by itself
   verify or publish GitHub release assets.
3. Check package layout, JAR versions, required libraries and SHA-256 sums.
   Exclude operational workbooks, credentials, logs and local evidence. Keep
   release binaries in release storage, not Git source commits.
4. Commit the validated source, fetch and preserve remote changes, then publish
   through a normal fast-forward-safe push. Tag the exact source used for the
   release. Do not move a published tag or replace an existing version's assets.

The build can call the companion TrueLogs publisher when local configuration is
present. Use `-Dtrue.web.publish.skip=true` for a build that must not publish.
Do not interpret a local build as proof that a runtime was deployed.

## Publish the delivery channels

1. Create a draft GitHub Release for the source tag. Upload the dist ZIP,
   portable ZIP and checksum file. Keep the public body below **500 characters**
   for older Java 8 clients, with a link to detailed notes at the release tag.
2. Download the uploaded assets and verify their hashes and contents before
   publishing the release.
3. Publish [update/version.json](../../update/version.json) with that version,
   published dist URL and verified SHA-256. The manifest must not point at an
   unavailable or unverified asset. Verify the remote commit and test the public
   raw manifest, release-note enrichment and actual download using Java 8.
   Raw GitHub caches may briefly serve the previous manifest after a push.
4. Publish the matching immutable TrueLogs core through the companion
   [runtime publisher](https://github.com/kantapon-sam/LLDP_MapViewer/blob/main/true-log-console/README.md).
   Include a matching worker when its contract changed. Verify the current
   pointer, artifact hashes and a new job. Running jobs retain their pinned
   runtime; a core update does not require restarting the TrueLogs service.
5. For an authorized scheduled-Bot deployment, stop the existing collection
   workflow, retain the installed artifacts for recovery, activate the tested
   collector and verify it before starting new collection. Preserve workbooks,
   credentials, logs and budget history. Resetting budgets or changing cleanup
   policy is a separate operational action.

Use the companion [OPERATIONS](https://github.com/kantapon-sam/LLDP_MapViewer/blob/main/docs/OPERATIONS.md)
for the maintained scheduler and deployment boundaries. Do not install the old
scheduler copies in this repository over the maintained server scripts.

Documentation-only organization does not require a new application version.
Keep historical release notes under `docs/releases/`; links pinned to older
release tags remain valid after files move on `master`.
