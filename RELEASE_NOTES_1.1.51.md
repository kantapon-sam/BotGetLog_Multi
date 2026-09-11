# v1.1.51 - Huawei ARP description wrap repair

Huawei ARP reports now reconstruct descriptions when saved interface tables contain
flattened 80-column terminal wraps. The parser reads the Description column from
the table header and removes the corresponding continuation indentation at wrap
boundaries. Real spaces, commas, quotation marks and PHY/Protocol annotations are
preserved. Saved logs and collection behavior are unchanged.

Validation covered six saved Huawei logs and 1,726 ARP rows. Only 194 ARP descriptions
changed; all other columns stayed identical. All 931 reconstructed interface
descriptions matched separately collected detailed interface descriptions from the
same devices and date. Automated tests cover repeated wraps, different header
positions, genuine spaces and unrelated wide spacing, plus Nokia ARP and all four
True Logs report types: ARP, LLDP-Link_OPTIC, PTP and ISIS_Peer.

This is a layout-specific repair. Descriptions with intentional spacing at the same
exact wrap positions cannot be distinguished from padding using the table alone.
The release includes the existing 1.1.50 daily collection limits and paired LLDP
retry behavior. Distribution and portable packages include SHA-256 checksums.
