# v1.2.0 - Multi-vendor SID collection and report

- Add `HW-SID`, `ZTE-SID`, and `N-SID` command sets to the default `UserInterface_Input.xlsx`.
- Add `SID.jar` and a launcher entry for multi-vendor Loopback/SID reporting.
- Export one row per node with Loopback 11, 15, 19, 20, 99, 98, and 1023; missing values remain blank.
- Keep `Segment_Routing_Prefix.jar` as a compatibility entry point to the new SID report.
- Allow Auto Update to install `SID.jar` and synchronize the `cmdSet` sheet for existing users without replacing their device inventory.
- Add the SID report type to TrueLogs with automatic Huawei, ZTE, and Nokia command selection.

## Validation

- Huawei, ZTE, and Nokia parser fixtures reproduce the requested column order and blank-field behavior.
- Command-set regression verifies every SID command in `UserInterface_Input.xlsx`.
- TrueLogs application, worker completeness, and native report integration tests pass.
- The full project compiles successfully with the NetBeans Ant build.

## Downloads

- **dist**: application bundle for an existing Java installation and Auto Update.
- **portable**: application bundle with a Windows Java runtime; start with `run.bat`.
- **SHA256SUMS.txt**: download checksums.
