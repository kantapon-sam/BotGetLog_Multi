# v1.1.53 — ZTE Smartgroup member collection

The standard `ZTE-LLDP-Link_OPTIC` command set now runs `show lacp internal`
after `show interface` and before `quit`, so future raw logs include the
Smartgroup IDs, physical member ports and aggregation states reported by ZTE.

The desktop updater distributes this command through its existing `cmdSet`
sheet synchronization. Huawei, Nokia and other command columns are unchanged.
This release adds collection evidence; it does not yet add Smartgroup or MB/BB
columns to the LLDP CSV or change Forecast grouping.

Validation: compare every workbook cell and unchanged XLSX package entry;
exercise the packaged updater in an isolated installation; verify native LLDP
exports from one user-authorized ZTE collection and saved Nokia/Huawei logs.
