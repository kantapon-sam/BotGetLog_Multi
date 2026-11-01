# Optional CLLS backup accounts — 1.2.29

Version 1.2.29 adds opt-in collection with existing log preservation. Pass
`--link-optical-preserve-existing-logs` or inherit
`TRUE_LINKOPTICAL_PRESERVE_EXISTING_LOGS=1` through every child collector.
Validated complete logs retain the ordinary checkpoint skip; duplicate logs
stay in place. Global repair monitors, selected bulk cleanup and final global
deduplication are disabled. A conflicting partial is moved to a unique UUID
archive in `_output/Deleted_Log` without replacing an earlier archive. Despite
that directory's legacy name, these files are retained. Failed archival stops
that node before writing into the earlier transcript.

Only when explicitly requested, also pass
`--link-optical-discard-incomplete-logs` or set
`TRUE_LINKOPTICAL_DISCARD_INCOMPLETE_LOGS=1`. This requires preservation mode and
removes only a positively identified incomplete transcript before replacement:
known command bounds, a matching node/vendor head, a stable readable regular
UTF-8 file, the first command present, and a missing closing command or explicit
`[BOT-COLLECTION-INCOMPLETE]` marker. Complete files, duplicates, unknown command
sets, invalid headers, decoding/read errors and ambiguous partials are retained;
uncertain conflicting files use the unique archive instead. The default remains
archival without removal. These collector flags do not authorize shell-level
cleanup, a daily-budget reset, or deletion of exported CSV files; configure the
maintained companion scheduler/publisher preservation flags separately.

Version 1.2.28 corrects SSH sessions whose accumulated login transcript retains
the initial SAM IP-selection menu. That historical menu must not prevent a
backup after an explicit node authentication rejection, or reject a successful
primary login. Only a current terminal SAM menu prompt means the node session
has returned to the gateway. Actual transport failures and account/IP locks
still stop fallback. Regression coverage includes the combined SAM banner and
ZTE rejection through a verified backup prompt, plus current-menu, ANSI,
timeout, closed-connection and lock cases. Existing logs and daily counts are
retained; updating does not restore attempts consumed by a previous run.

Scheduled TRUE collection supports the existing primary CLLS login and up to
five optional backup username/password pairs. An Admin manages the server pairs
in the companion Control Console's SAM & CLLS settings. Leaving all backup slots
empty preserves primary-only behavior. Windows installations keep their own
local credential store; an old primary-only CLI or dialog save retains backups.

The encrypted store keeps `clls.username` and `clls.password` for the primary
account and adds `clls.backup.1.username` / `clls.backup.1.password` through slot
5. Passwords use the existing encrypted v1 format. Empty, incomplete or
undecryptable backup entries are not attempted. An identical username/password
pair is tried at most once in the login sequence.

For an operator-controlled idle installation, `TrueCllsCredentialCli
--backup-slot=1 --stdin` accepts the username and password as two UTF-8 input
lines, saves only that slot and verifies the encrypted readback. Use a slot from
1 through 5. Do not put either secret in command arguments or shell history.
The CLI preserves the primary and other backup slots, and refuses to overwrite
an existing unreadable or malformed store. The Control Console supports removing
unneeded slots; the CLI does not clear credentials implicitly.

Fallback starts only after an explicit node authentication rejection. It reuses
that node's existing login exchange and waits for a fresh username prompt before
submitting the next pair. It stops on a verified matching node prompt, account
lock, IP block, gateway return, timeout or closed connection. It does not open
another connection or bypass the shared connection and daily admission budgets.
The device may stop authentication before all configured slots are reached.
Personal Live/TrueLogs credentials and L2 accounts remain separate.

A verified successful backup login whose username is not literal `clls`
(case-insensitive) appends one UTF-8 JSON object per line to
`_output/System_Log/clls-fallback-events.jsonl`. Fields are `version` (1), `ip`,
`node`, `slot` (1–5), `username`, and `timestamp` (ISO-8601 with Bangkok offset).
No password is included. The same object appears after
`[CLLS-FALLBACK-SUCCESS]` in console/work logs. These events support the companion
Control Console's persistent notes. Failed authentication does not create notes.
Version 1.2.31 emits a verified
`clls-success` recovery event for the same node so the matching Control Console
can resolve its Note automatically. See [automatic recovery](clls-note-recovery.md).

Upgrade the matching Control Console and Bot together while collection is idle.
Preserve the workbook, credential store, daily budget state and all collected
logs. This change requires no log cleanup or collection-counter reset.

Validation uses fake authentication streams and isolated encrypted stores. It
covers sparse/empty slots, duplicate pairs, explicit rejection, Huawei Error:
responses, lock/block/timeout stops, exact prompt identity, primary-only saves,
safe success events and concurrent append integrity. Existing Juniper login,
scheduled SSH reader and network retry regressions also pass.
