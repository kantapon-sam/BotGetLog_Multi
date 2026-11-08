# ZTE PN live monitoring (1.2.36)

Full Metrics uses `show interface brief` for ZTE PN nodes, alongside CPU/memory
and optical commands. A full `show interface` chassis dump can contain tens of
thousands of lines and exceed the Live Monitor timeout even after login succeeds.
PN is recognized from the inventory type or a PN/PN1/PN2-style node-name prefix.

Selecting ports adds `show interface <port>` for up to five selected ports.
Descriptions and CRC values from those detailed responses remain available;
CRC that was not collected remains unknown. RN/AGN retain their existing compact
profile, and other node profiles retain their existing command selection.

This changes the one-shot Live Monitor command list only. Scheduled collection
and TrueLogs workbook command lists still collect their configured full data.
The SSH reader, connection limits, timeout and retained logs are unchanged.

Validation includes compact PN command selection, selected-port limits and CRC
merging, existing SSH/paging regressions, and an actual PN live probe using its
unchanged inventory identity through the shared connection budget.
