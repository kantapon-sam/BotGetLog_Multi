# BotGetLog Multi 1.2.6

Fixes repeated collection of Juniper CN nodes after their logs have already completed successfully.

- Accept Junos `{master}` and `{backup}` role lines before the initial command prompt.
- Match the full prompt hostname against the filename, including routing-engine suffixes and underscore/hyphen normalization.
- Recognize `quit`, `exit`, or `logout` followed by the connection-close acknowledgement, including output joined on the same line.
- Keep incomplete captures and logs from a different node or routing engine ineligible for completed-log skipping.

Validation: 39 completeness checks, existing Juniper live parsing, vendor-banner, prompt-boundary, network-retry, daily-selection and daily-budget regressions. Offline replay of eight previously rejected completed production captures passes without recollecting devices or modifying the saved logs.

Includes the dist package for Auto Update, a portable Windows package, and SHA-256 checksums. Existing inventory and collected logs are retained during upgrade.
