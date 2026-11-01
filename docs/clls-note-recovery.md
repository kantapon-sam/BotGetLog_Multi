# Automatic CLLS recovery notes (1.2.31)

A future successful login with the configured primary CLLS account automatically resolves
that node's alternative-account Note in the matching Control Console. No manual
removal or log cleanup is needed. The event identifies the exact management IP
and expected node, plus the actual username and selected credential slot.

The collector must submit credentials in a fresh node login and verify the same
node's authenticated prompt. A timeout, rejection, lock, wrong node, gateway
prompt, retained completed log or a prompt carried over from an earlier session
cannot resolve a Note. Recovery describes authentication; Enable and collection
success are separate checks. A configured backup whose actual username
is `clls` also resolves the Note; other successful backup accounts still create or
refresh their normal alternative-account Note. Daily admission limits and retry
policy are unchanged.

The existing `_output/System_Log/clls-fallback-events.jsonl` stream remains
append-only and password-free. Legacy alternative-account rows retain their
version-1 schema. New recovery rows have `version:1`, `kind:"clls-success"`, a UUID
`eventId`, `accountRole` (`clls-primary` or `clls-backup`), `ip`, `node`, actual
`username`, `slot` (0 for primary, 1-5 for backup), and an
ISO-8601 `timestamp` with Bangkok offset and available subsecond precision.
A primary username may be an account such as `vdes2442@clls`; its explicit role,
rather than its spelling, identifies primary recovery. Backup recovery requires
the literal `clls` username. File locking preserves whole records across
concurrent writers. The timestamp
represents the verified outcome, not when a credential was attempted.

The Control consumer keeps the latest outcome per exact IP and normalized node,
including a recovery watermark after a Note disappears. Only a strictly newer
CLLS success clears an alternative-account outcome. Equal-time conflicts retain
the alternative-account outcome. Replayed or late older records cannot clear a
newer Note or resurrect one already resolved; a genuinely later alternative-account
success can create a new Note. Upgrade both components together while idle.
Existing Notes remain until future verified CLLS recovery; no recovery is inferred
from old completed logs or account configuration.

Offline tests cover primary and backup CLLS, all supported prompt families,
fresh SSH submission, stale state, authentication/transport failures, exact node
identity, password-free JSON, replay identifiers and locked concurrent appends.
