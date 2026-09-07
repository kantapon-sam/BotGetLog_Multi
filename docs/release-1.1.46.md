## Changes

- Include Huawei `50|100GE` dual-rate physical ports in Live Monitor inventory, including Up and Down ports.
- Read dual-rate interface details, CRC counters and averaged optical lanes. Prevent logical-interface values from leaking into adjacent physical-port results.
- Accept exact Huawei dual-rate names for selected-port reads without allowing arbitrary pipe operators.
- Include the deployed shared Bot/Live connection-budget implementation and safe SSH gateway cleanup. Default shared capacity remains 20 connections, with up to five Live slots.
- Preserve existing Nokia, ZTE and Huawei parsing and package all 19 runtime dependencies.

## Verification

Java 8 clean-directory build and offline regressions passed for Huawei dual-rate inventory/details, existing multi-vendor parsing, Nokia live speed selection, Bot/Live budget coordination and SSH setup/cleanup failures. Package manifests, required libraries, portable/application parity and unchanged public input templates were checked.

The live-reader changes were also tested on a production Huawei aggregation node before publication: all 20 dual-rate 100G interfaces appeared, with optical data on all 11 Up interfaces and successful selected-port CRC reads for both Up and Down examples.

## Downloads and compatibility

- `BotGetLog_Multi-dist-1.1.46.zip`: application and Auto Update package; requires Java 8.
- `BotGetLog_Multi-portable-1.1.46.zip`: Windows portable package including Java; extract and run `run.bat`.
- `SHA256SUMS.txt`: download checksums.

This release is the latest Auto Update version. Existing runtime logs and user credentials are not included in these packages. Live Monitor's selected-port API also requires the corresponding LLDP Map application update; a Bot-only update does not install LLDP Map.
