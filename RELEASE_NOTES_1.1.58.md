# v1.1.58 - TRUE and DTAC network retry queue

- Queue nodes that fail because of a network or connection problem after the primary run finishes.
- Retry the queue for up to three rounds, waiting 30 seconds before each round.
- Do not queue authentication, vendor, command-set or incomplete-command failures.
- Check for a completed log before and after every retry so completed nodes are not collected twice.
- Reset the retry queue and round count whenever the user starts a new Run.
- Keep final TRUE and DTAC success/failure summaries aligned when a queued node recovers.

## Validation

- TRUE and DTAC retry-policy regression tests pass.
- TRUE Link Optical export-mode and vendor-banner regression tests pass.
- The full project compiles successfully with the NetBeans Ant build.

## Downloads

- **dist**: application bundle for an existing Java installation and Auto Update.
- **portable**: application bundle with a Windows Java runtime; start with `run.bat`.
- **SHA256SUMS.txt**: download checksums.
