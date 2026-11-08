# Bot 1.1.48

Huawei ARP tables can put only one space between long interface names and VPN instances, or between other padded columns. The parser now recognizes ARP fields independently of column width, including optional expiry values and the two-token `I -` type. Interface, VPN, PHY, Protocol and Description are matched correctly, and formerly skipped rows are retained.

The release also incorporates the gateway connection pacing and authenticated prompt vendor selection already deployed on the server. Their compiled behavior was compared with the deployed runtime before rollout.

Validation:
- Huawei ARP regression coverage for single spaces, tabs, long IPv4 addresses, optional expiry/VPN, static/dynamic types and description joins.
- Nokia ARP parsing and collection commands, gateway pacing, prompt vendor selection and automatic updater regressions passed.
- 293 retained Huawei logs produced 342,157 report rows matching the source ARP records; 10,194 rows missing from the prior parser were recovered. All reports have 11 columns and zero invalid rows.
- Distribution and portable packages contain matching Java 8 application JARs and 19 libraries. Package SHA-256 values are published with the release.

Existing ARP CSV files must be regenerated from their original logs to receive the fix. True Logs supports regeneration with the latest validated report runtime while retaining the original collection version and raw logs.
