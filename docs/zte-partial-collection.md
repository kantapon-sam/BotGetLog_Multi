# ZTE partial collection (1.2.34)

A finished ZTE-LLDP-Link_OPTIC session may be Complete (Partial) when every expected command was issued at the exact filename hostname, CPU/LLDP/version data is present, the session closes normally, and the only errors are device service timeout 141365 in show opt brief or show interface. Other vendors, authentication failures, wrong prompts, missing commands and unrelated errors retain their existing failure behavior.

Retain the raw log and export successful sections. Omit the entire failed command section: absent interface state is UNKNOWN and absent optical/traffic values are empty, never zero. A retained complete capture is preferred to a partial capture for the same device. Control Console 1.1.16 displays the PARTIAL event separately from full success; historical failures are not relabeled.

Service-timeout captures never grant full completion. Partial captures remain eligible for daily checkpoint/repair, with the existing per-IP and unique-IP daily limits. Installation must not reset counters, overwrite input workbooks, delete retained logs or start unbudgeted retries.

Install the matching Bot and Link_Optical artifacts while the collector is idle, plus the maintained all-completed exporter and duplicate-log cleanup from LLDP_MapViewer. Publish the matching immutable TrueLogs runtime without restarting it or moving running jobs off their pinned runtime. Windows dist/portable packages and the update manifest carry the same version.

Validation covers three actual service-timeout captures and two complete captures, strict retry eligibility, rejected malformed/wrong-node transcripts, unknown measurements, full-log preference, exporter validation and existing daily-budget/network-retry regressions.

## Rejected paging setup (1.2.35)

Some ZTE software rejects `terminal length 0` with `%Error 140303: Invalid input
detected at '^' marker.` The existing reader can still advance pages with Space.
A verified finished capture with this exact setup response is **Complete**, not
Partial: setup changes display paging and does not collect data. The exception
requires the exact same-node command sequence, usable CPU/version/LLDP, matching
LLDP neighbor count, optical/interface/LACP evidence and normal session closure.
Missing responses, unfinished markers, other errors and other vendors remain
excluded. Raw captures and historical failed attempt events are never rewritten.

If the same capture also has a supported optical/interface service timeout
141365, it remains **Complete (Partial)** and eligible for normal daily repair.
Only fully verified captures receive the usual completion protection.

`ZtePagingSetupRegression` checks full/partial classification, truncated or
wrong-node captures, other errors, raw-file preservation and optional retained
incident files. `ScheduledSshReadRegression` exercises ZTE Space continuation.
