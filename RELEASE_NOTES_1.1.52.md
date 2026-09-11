# v1.1.52 - Nokia GPS/GNSS port status

Nokia GPS/GNSS ports now report Current State in LLDP CSV exports and live port
details when the device prints `Admin Status` and `Oper Status` on the same line.
Both `Oper State` and `Oper Status` are supported. The operational value is used
even when it differs from the administrative value; absent operational data remains
unknown. Existing Ethernet status parsing is preserved.

Validation covers same-line and separate-line fields, up/down combinations,
missing operational status, adjacent ports, and Nokia, Huawei and ZTE live parsing.
A nine-port saved-log replay changes only the GPS port's Current State from blank
to up. The packaged collector also passes these checks on the server's Java 8.

The release retains 1.1.51's Huawei ARP repair and existing collection limits.
Distribution and portable packages are supplied with SHA-256 checksums.
