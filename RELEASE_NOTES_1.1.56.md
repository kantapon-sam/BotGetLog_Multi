# v1.1.56 — LLDP Group Description and Nokia LAG descriptions

DataLLDP_Neighbor CSV exports now append Group Interface and Group Description.
The original columns, including NeighborDes in filtered exports, retain their order.
ZTE and Huawei use the description of the verified Smartgroup/Eth-Trunk.
Nokia's N-LLDP-Link_OPTIC command set adds show lag description before logout.
Wrapped LAG descriptions are joined without absorbing physical member descriptions.
Old logs without LAG descriptions leave the new field blank. Conflicting membership
or descriptions are not guessed. DTAC CSV files without these fields remain compatible.

Validation includes one Nokia node's read-only command output, saved ZTE/Huawei logs,
three-vendor native exports, command isolation, existing-user updater synchronization,
and the deployed TRUE/DTAC CSV merger. No full collection is started by this release.
