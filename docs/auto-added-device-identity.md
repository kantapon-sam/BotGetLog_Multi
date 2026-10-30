# Automatic discovery with duplicate hostnames (1.2.25)

Automatic node discovery identifies workbook rows by management IP. Distinct
eligible IPs can be added even when their hostnames are identical. Existing IPs
and repeated discoveries of one IP are still skipped. Vendor command-set
selection and the approved management-IP scope remain in place.

The recursive new-site queue records exact workbook row selectors (`#<row>`)
rather than hostname queries. Adding one device therefore does not select an
already collected device with the same hostname.

Install the matching `SyncGrdNodesToUserInput.java` from LLDP_MapViewer as well.
That synchronizer retains same-name rows without matching physical evidence;
it no longer replaces an unreachable IP just because another IP uses its name.
A verified duplicate requires the same usable base MAC, vendor and device prompt.
Missing, zero, multicast or broadcast MAC values do not establish equivalence.
Explicit IP migration still requires one unambiguous named row and compatible
device evidence.

This release does not change Topo, MAP, SPOF, LLDP CSV columns, collection
commands, connection limits or daily completion budgets. It does not rename
these devices. Apply the scheduled Bot artifacts and inventory repair only
between complete collection workflows, preserving existing workbooks, logs,
queues and budgets. Running TrueLogs jobs keep their pinned runtime.

Regression coverage includes both discovery orders, Huawei and ZTE sharing a
hostname, repeated discovery, retained existing rows, command-set selection,
exact-row queues and physical-evidence checks in the companion synchronizer.
