# v1.1.50 - Daily collection limits and paired LLDP retries

TRUE automatic and selected Link Optical collection now share a persistent daily budget: at most three admitted collections per IP per Bangkok calendar day by default. Primary collection, incomplete retries, checkpoint passes and background retries use the same counter across JVMs.

When the LLDP reconciliation workflow requests both endpoints of a mismatched physical link, the Bot reserves both endpoints together before archiving logs. Each pair can be reserved once per day. If either endpoint has no budget remaining, both are deferred and their existing logs are retained. Concurrent processes cannot reserve the same pair twice. Duplicate workbook rows for the same IP and command set are collected once.

Scheduled cleanup also retains logs for exhausted IPs. Corrupt or conflicting budget state stops collection or cleanup safely. Failed or interrupted attempts still consume their admitted slot; the next Bangkok calendar day uses a new budget. Manual live probes and IPRAN Log Center user jobs keep their existing collection behavior.

The server LLDP planner and targeted refresh scripts provide the paired retry queue; these companion scripts are deployed with LLDP MAP. The Bot release consumes that queue when configured. The default budget directory is `/transport/BotGetLog_Multi/dist/_output/System_Log/daily-collection-budget` on Unix and `_output/System_Log/daily-collection-budget` on Windows. `TRUE_DAILY_COLLECTION_BUDGET_DIR` and `TRUE_DAILY_COLLECTION_LIMIT` configure the shared location and limit.

Validation covers daily rollover, failed attempts, pair reservations, concurrent threads and JVMs, exhausted-log preservation, duplicate selection, and Link Optical export behavior. Version 1.1.49 Huawei ARP parsing fixes remain included. Both distribution and portable packages include SHA-256 checksums.
