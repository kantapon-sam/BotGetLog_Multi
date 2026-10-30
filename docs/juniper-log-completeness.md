# Juniper log completeness (1.2.24)

Version 1.2.24 fixes complete Juniper SSH logs being rejected when the first
device output includes an empty prompt before the first command. The validator
now accepts empty prompts from the expected node, including normal RE prompt
forms, while still requiring the expected command sequence and valid session
closure. Prompts from another node, unexpected commands and incomplete logs
remain invalid.

Some CLLS sessions echo a single `q` immediately before
`Connection closed by foreign host.`. That exact close line is now accepted
after the expected node's quit, exit or logout command. Arbitrary prefixes and
unrelated text are still rejected.

The fix prevents these complete logs from being moved aside and collected
again solely because of their prompt or close-line formatting. It does not
disable retries for incomplete sessions or refreshes requested by topology
checks. Existing archived logs are retained; installing this version does not
automatically restore them or reset collection counters.

Validation includes 63 focused regression checks and 30 complete archived
Juniper logs from the affected collection cycle.

For a running scheduled collector, stop its checkpoint cycle before replacing
JARs and retain its logs, workbook, daily budget and queue backup. Resume with
the original collection day, `BOT_CLEAN_TOTAL_LOG_BEFORE_RUN=0`,
`BOT_KEEP_TOTAL_LOG_HISTORY=1`, `BOT_RESET_DAILY_COLLECTION_BUDGET=0` and
`BOT_CLEAN_DUPLICATE_IP_LOGS_BEFORE_EXPORT=0`. Keep the installed scheduler from
the LLDP_MapViewer repository. TrueLogs receives an immutable matching runtime;
jobs already running retain their pinned runtime without a service restart.
