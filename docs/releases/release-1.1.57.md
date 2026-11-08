# v1.1.57 - Vendor detection and command failure reporting

- Ignore hash-only warning banners while waiting for SSH and Telnet device prompts.
- Identify Huawei, Nokia and ZTE from actual prompt lines, preserving the configured vendor when prompt evidence is absent.
- Correct vendor detection for saved-log retries, including Nokia hostnames with parentheses and configuration paths.
- Report rejected device commands as failed collections instead of successful completion.

## Validation

- Offline replay covers ten prompt forms, SSH/Telnet byte streams and command-error completion.
- Six existing collector regression suites pass, along with worker adapter compatibility and 19 Control Console activity tests.
- Six affected Huawei devices were successfully recollected with the corrected command set.

## Downloads

- **dist**: application bundle for an existing Java installation and Auto Update.
- **portable**: application bundle with a Windows Java runtime; start with `run.bat`.
- **SHA256SUMS.txt**: download checksums.
