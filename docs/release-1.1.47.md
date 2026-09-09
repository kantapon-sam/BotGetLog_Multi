## Changes

- For N-ARP, read the numeric Service IDs from `show service service-using vprn`, then run `show router <Service ID> arp` and `show router <Service ID> interface` for each VPRN.
- Join ARP entries to interface ports/SAPs and status within the same Service ID. Resolve uniquely matching truncated interface names and preserve Base-router and older service-ARP log support.
- Populate Nokia `Model` from the equipment type (`System Type` or legacy `Type`). Read `Node` from the device prompt without overwriting it with `System Name` or `Name`.

## Verification

Built the complete application for Java 8 and passed the N-ARP command-generation and parser regressions. Live read-only testing on a Nokia 7250 IXR-e returned three VPRNs with 17 ARP entries plus five Base-router entries; all 22 rows joined to their ports and status. Regression coverage includes duplicate Service IDs, repeated interface names across VPRNs, truncated and wrapped interface names, legacy logs, and prompt-derived node identity.

## Downloads

- `BotGetLog_Multi-dist-1.1.47.zip`: application and Auto Update package; requires Java 8.
- `BotGetLog_Multi-portable-1.1.47.zip`: Windows portable package including Java; extract and run `run.bat`.
- `SHA256SUMS.txt`: package checksums.

Existing user input and runtime logs are preserved by the updater. The packages use the existing public input templates and exclude runtime logs and stored user credentials.
