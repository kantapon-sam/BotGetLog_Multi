# Scheduled SSH collection

The scheduled collector reads SSH output in blocks of up to 8192 available
bytes. It writes the original bytes to the log, retains a fixed-size text
window for matching, and checks command completion after each received block.
The established node identity and quiet prompt-settle interval still apply.
Pagination markers can span blocks and still send one continuation space.
Remote-close detection examines the preceding window plus the whole new block
so neither fragmentation nor a long following line hides a failure.

The earlier scheduled path read one byte at a time and scanned its tail after
every byte. Its response buffer stopped growing at 4096 characters, so a
length-modulo condition then called `Thread.yield()` on every subsequent byte.
Large interface responses could occupy a worker for minutes, use substantial
CPU, and reach a remote session closure before the reader caught up.

The separate TrueLogs command reader retains its buffered implementation.
Telnet, login negotiation, command lists, concurrency, daily admission limits,
retry policy, log completeness and export validation keep their existing rules.

## Verification

Compile with Java 8 against the matching Bot JAR and its manifest libraries.
Run `ScheduledSshReadRegression` for raw byte preservation, split prompts,
Huawei HRP, Nokia/Juniper/ZTE prompts, misleading descriptions, fragmented
pagers, remote closure, truncated output and twenty concurrent streams. It
checks block I/O counts instead of asserting machine-dependent timing.

Also run `CompletedExitEofRegression`, `TranscriptDescriptionPromptRegression`,
`TrueLivePromptBoundaryRegression`, `JuniperSessionRecoveryRegression`,
`HuaweiHrpPromptRegression` and `TrueBufferedReadRegression`. Tests that use
calendar-date fixtures need `TRUE_COLLECTION_DAY` pinned to that fixture date.
For TrueLogs, use the companion repository's real SSH loopback worker fixture
with twenty synchronized nodes, fragmented output and pagination.

Deploy a verified immutable candidate while the scheduled workflow is stopped,
retaining its previous JAR and all runtime input/log files. Publish the matching
TrueLogs runtime through its runtime publisher; existing jobs keep their pinned
core. A checkpoint-only resume must disable startup log cleanup. Selective
cleanup and daily admission resets require the operator's instruction; use the
existing selective cleanup and cycle-lock-owned reset helpers.
