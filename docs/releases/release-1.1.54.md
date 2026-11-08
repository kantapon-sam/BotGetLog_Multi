# v1.1.54 — LLDP aggregation membership in CSV

Both `DataLLDP_Neighbor_` exports now append `Aggregation Interface`,
`Aggregation Description`, and `Aggregation Member State` to each physical-port
row. The original columns, including the filtered file's `NeighborDes`, retain
their names, positions and values.

The exporter reads ZTE Smartgroup membership from `show lacp internal`, Huawei
Eth-Trunk member tables, and Nokia LAG membership from existing port output.
The DNM14 Huawei sample identifies the two 100G ports as separate Eth-Trunk1
(MBAG description) and Eth-Trunk2 (BBAG description).

Legacy logs without explicit membership produce empty aggregation columns.
Missing group descriptions remain empty; physical-port descriptions and traffic
are not used to infer group membership or service type. Huawei subinterfaces do
not overwrite physical trunk membership. Conflicting group claims are marked
`CONFLICT` without selecting an arbitrary group.

The collection command sets are unchanged from 1.1.53. This release changes CSV
output; MapViewer's grouping rules are unchanged. The current merger maps old
DTAC columns by name and leaves the added fields empty for legacy records.

Validation: real ZTE, Huawei and Nokia logs; preservation of all original LLDP
values and other exports; legacy logs, inactive members, CSV quoting and
conflicting evidence; mixed new TRUE and old DTAC files using the installed
merger in isolated directories. No additional live node collection is required.
