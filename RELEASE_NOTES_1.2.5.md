# v1.2.5 - Complete Juniper command set

- Add `quit` as the final command in `J-LLDP-Link_OPTIC`.
- Match the command-cell colors of the adjacent command sets: first command red, remaining commands yellow.
- Retain the v1.2.4 fixes for CN node types and `100Gbps` to `100G` live speed display.

Auto Update backs up the existing workbook, replaces only its `cmdSet` sheet from the release defaults, and changes type `MX2020` to `CN` only for the eight exact Juniper node/IP pairs. Other inventory data remains intact.
