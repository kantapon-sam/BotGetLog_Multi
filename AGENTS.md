# Working agreements

## Automatic Git publication

The user authorized automatic commit and push after each completed coding task. Once relevant checks pass, review the diff, stage only task-related source/tests/documentation, commit with a clear message, and push without asking for another confirmation. The normal destination is `origin/master`; honor an explicit task-specific branch or PR workflow instead.

Fetch before publishing and preserve remote commits. Use a normal fast-forward-safe push; never force-push, reset unrelated work, or include another task's staged/uncommitted files. Keep credentials, operational workbooks, collected logs, build outputs and local deployment evidence out of commits. Publish release binaries or tags only when the task requests a release. If a protected branch requires a PR, push a `codex/` branch and create a PR. Verify the remote commit and report its link. Report failed checks or push failures accurately.

## Bot version delivery

For a user-facing Bot version update, the user requested GitHub Releases,
Windows Auto Update, and the TrueLogs runtime to be updated together
(2026-10-26). A source push or scheduled-Bot server patch alone is not complete.
Publish verified dist/portable packages and checksums, verify the release assets,
then update update/version.json. Publish the matching immutable TrueLogs runtime
through the companion repository's publisher and verify its current pointer.
Retain running jobs on their pinned runtimes; do not restart TrueLogs merely to
update its Bot. Routine publication within this version-update scope does not
need another confirmation. Report any failed stage explicitly.

Keep the public GitHub Release body below 500 characters, linking to detailed
notes under docs/. Clients before v1.2.21 parse it with a recursive Java 8 regex
and can crash before showing the update prompt when the body is long. Validate
release-note enrichment as well as manifest download when testing startup.

## Linux scheduler source

The companion `LLDP_MapViewer` repository's `scripts/` is the maintained source for the deployed Linux checkpoint scheduler, cron installer and daily-policy helpers. See its `docs/OPERATIONS.md`. The older scheduler copies in this repository are not a deployment baseline; do not overwrite deployed scheduler scripts from them. A task changing both scheduler behavior and collector Java must publish the changes to both repositories.
