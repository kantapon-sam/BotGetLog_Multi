# v1.2.4 - Juniper CN type and port speed display

- Show the eight MX2020 Juniper nodes as type `CN` in Live Node Monitor.
- Display Junos rates such as `100Gbps` as `100G`, including Down ports without descriptions. CPU, memory, CRC, optical values and Juniper commands are unchanged.
- Auto Update changes the type in an existing workbook only when one of the eight exact node/IP pairs still has type `MX2020` and command set `J-LLDP-Link_OPTIC`. It keeps all other inventory cells and creates a workbook backup.

Verification: Java 8 build and Juniper/Huawei/Nokia/ZTE live-parser regressions passed. An upgrade of the published 1.2.3 workbook changed only the eight intended type cells outside `cmdSet`. A live port check on `CWTTNTBB24W` returned 300 ports, with Down ports `et-2/0/7` through `et-2/0/9` reporting `100G`.
