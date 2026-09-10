# v1.1.49 - Huawei ARP status and description parsing

Huawei ARP reports now keep annotated interface states such as `up(E)`, `up(s)` and `*down` in the PHY and Protocol columns. These values previously produced empty status fields or leaked annotations into Description.

Descriptions containing quotes and commas now use proper CSV escaping. Wrapped descriptions remain attached to their interface. The original status annotations and description text are retained.

The ARP interface and VPN field fixes from 1.1.48 remain included. Existing log files can be used to regenerate corrected reports without collecting the nodes again.

Validation covers Huawei spacing and annotated states, quoted/comma/wrapped/empty descriptions, Nokia ARP, gateway pacing and cancellation, vendor selection, and Auto Update. Both distribution and portable packages include versioned Java 8 application JARs and SHA-256 checksums.
