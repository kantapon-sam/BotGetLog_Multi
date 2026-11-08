# BotGetLog_Multi 1.2.1

- Require the established authenticated device prompt when the True Logs live probe reads command output.
- Prevent Nokia description fragments such as `Tie#` and `CORE-ODF#` from ending a command response early and shifting subsequent commands.
- Preserve Nokia A/B prompt-context compatibility and leading alarm-marker compatibility.
- Retain live command-count and last-device-data progress reporting for True Logs.

## Validation

- The production transcript regression containing `Tie#` and `CORE-ODF#` fails with 1.2.0 and passes with 1.2.1.
- Existing live-speed, vendor-selection, banner, Nokia ARP and retry-queue regressions pass.
- True Logs runtime compatibility, live progress, all 16 worker-completeness cases and native report fixtures pass.

## Downloads

- **dist**: application bundle for an existing Java installation and Auto Update.
- **portable**: application bundle with a Windows Java runtime; start with `run.bat`.
- **SHA256SUMS.txt**: download checksums.
