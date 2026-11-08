# BotGetLog_Multi 1.2.2

- Preserve Nokia decimal wavelengths such as 1307.500 nm in Link Optical reports.
- Read optical powers and warning thresholds when Nokia output includes alarm markers.
- Read one-lane optics and multi-lane tables containing /L-W, /H-W and /L-WA suffixes.
- Reset optical readings and warning thresholds at each port; incomplete lane data cannot carry into another port.
- Share Nokia optical parsing between CSV export and Live Node Monitor.
- Keep genuinely unavailable measurements empty and retain the existing lane-average convention.

Includes the 1.2.1 True Logs prompt-boundary fix and existing collection behavior.

## Downloads

- **dist**: application bundle for an existing Java installation and Auto Update.
- **portable**: application bundle with a Windows Java runtime; start with run.bat.
- **SHA256SUMS.txt**: download checksums.
