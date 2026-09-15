# v1.1.55 — One Group Interface column in LLDP exports

Both `DataLLDP_Neighbor_` exports now append only `Group Interface`.
It replaces the three aggregation columns introduced in 1.1.54 and retains
the same verified Smartgroup, Eth-Trunk and LAG names. Aggregation Description
and Aggregation Member State are no longer included in the CSV.

Every original LLDP column keeps its name, position and value, including the
filtered file's NeighborDes column. Collection commands and group identification
are unchanged. Missing or conflicting membership leaves Group Interface empty.

Validation: re-export the saved Nokia, ZTE and Huawei samples, compare every
original value and group name with 1.1.54, and verify the installed merger with
new TRUE CSVs and legacy DTAC CSVs. No new network collection is required.
