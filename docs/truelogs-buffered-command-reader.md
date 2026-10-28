# TrueLogs buffered command reader

The live probe used by TrueLogs reads command responses in blocks of up to
8192 available bytes. A fixed 4096-character ring replaces the sliding text
buffer that previously shifted after every byte. Prompt matching runs at
prompt terminators; pagination matching runs only when a supported marker can
complete. The existing prompt normalization and pagination matchers remain
the source of truth.

Bytes after a matched prompt are pushed back for the next read. This preserves
CR/LF, command boundaries, byte-to-character conversion, pagination spaces,
Huawei HRP and Nokia role normalization, and the Junos command-redraw guard.
The existing inactivity timeout and 2 MB transcript retention bound remain.
Activity progress updates once per received block. Login negotiation and the
scheduled collector's stream reader are unchanged.

`true.log.bufferedRead` defaults to `true`. Set the JVM property to `false`
to run the retained reader for differential testing. The global connection
budget, per-gateway connection pacing, node concurrency, commands, retries and
report processors are not changed by this reader.

## Offline validation

Compile `Telnet_Multi.java` and the test sources with Java 8 against a matching
Bot JAR and its manifest libraries. Put the compiled classes before that JAR
on the test classpath. Run:

- `com.java.botgetlog.truecorp.TrueBufferedReadRegression`: differential raw
  responses, pagination writes, saved prompts and unread bytes; 1/7/8192-byte
  chunks; read-boundary splits; multiple vendors; misleading description
  terminators; Junos redraw; non-ASCII bytes; ring wrap; 2 MB retention; I/O
  failure; and twenty concurrent readers. The benchmark reports CPU/wall time
  but asserts output and block-I/O behavior, not unstable timing thresholds.
- `TrueLivePromptBoundaryRegression`, `HuaweiHrpPromptRegression`,
  `JuniperLiveNodeRegression`, `JuniperSessionRecoveryRegression`,
  `TranscriptDescriptionPromptRegression`, and `TelnetMultiLiveSpeedRegression`
  in the same package.

The companion LLDP_MapViewer repository supplies a real-SSH loopback fixture:

```text
python true-log-console/scripts/test_job_collector_ssh.py \
  --runtime CANDIDATE_DIST --baseline-runtime INSTALLED_DIST \
  --worker true-log-console/target/true-user-log-worker.jar \
  --nodes 20 --interface-lines 2500 --paginate --fragment-size 1021 \
  --synchronize-nodes
```

Both runtimes use the same production job worker. A barrier keeps all twenty
SSH sessions open before commands complete. Every node must finish both
commands and its complete saved log must match the installed core byte for
byte. Credentials and device data are synthetic; the listener binds only
loopback. Also run the companion worker completeness, progress-isolation and
native report tests with the candidate runtime.

## Runtime delivery

This patch is a TrueLogs runtime update, without a desktop version increment.
For a scoped deployment, compile the changed outer class and all its nested
classes against the verified installed 1.2.22 runtime, overlay only those
class entries in a copy of its JAR, and retain its manifest and libraries.
Verify all other archive entries are byte-identical and test the resulting
bundle, then use the companion `scripts/publish.ps1` runtime publisher. The
content hash identifies the new immutable runtime even though its base
version remains 1.2.22.

The runtime publisher validates worker compatibility before switching the
pointer. New jobs use it; existing jobs keep their pinned runtime. It requires
no web restart and does not replace the scheduled Bot JAR. Keep the preceding
runtime for rollback and verify the runtime hashes, web process identity,
scheduled JAR, and retained logs/reports after publication. Synthetic test
timings are not a prediction of production core usage; measure that on a
representative subsequent user job.
