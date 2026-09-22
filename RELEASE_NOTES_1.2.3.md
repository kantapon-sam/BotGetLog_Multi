# v1.2.3 - Juniper MX2020 live monitoring

- Add Juniper command collection for Routing Engine CPU and memory, port status,
  descriptions, CRC counters, and optical power.
- Parse Junos live output into the existing monitor result format.
- Add eight MX2020 nodes and the `J-LLDP-Link_OPTIC` command set to
  `UserInterface_Input.xlsx`.
- Recognize Junos prompts such as `vdes2442@clls@HAMMBKBD02W_re0>` while
  retaining `HAMMBKBD02W_re0` as the actual node name.
- Keep the full command set for scheduled collection. Live probes filter the
  extensive and optical output to finish within the monitor timeout.

## Validation

- Offline Junos transcript regression and existing live probe regressions.
- Connected to all eight MX2020 IPs and read CPU/memory using the deployed
  gateway credentials.
- A Full live probe on `10.185.0.11` returned CPU/memory, 236 physical ports,
  CRC counters, and optical measurements in about 40 seconds.
