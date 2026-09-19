# v1.1.59 - DTAC waits for retry queue before finishing

- Keep the DTAC progress window active after the primary pass reaches 100% while queued network retries are still running.
- Show `Retry queue / finalizing` instead of displaying a premature Finished dialog.
- Display the Finished dialog only after every retry round, executor shutdown and Summary/Failed report write have completed.
- Prevent the Finished dialog from terminating active DTAC retry workers with `System.exit(0)`.

## Validation

- Regression coverage confirms that 100% primary progress alone cannot trigger Finished.
- Finished becomes eligible only when both the primary count and full-run lifecycle flag are complete.
- TRUE and DTAC retry-policy regression tests pass.
- The full project compiles successfully with the NetBeans Ant build.

## Downloads

- **dist**: application bundle for an existing Java installation and Auto Update.
- **portable**: application bundle with a Windows Java runtime; start with `run.bat`.
- **SHA256SUMS.txt**: download checksums.
