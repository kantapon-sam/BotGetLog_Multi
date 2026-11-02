# SSH pagination and final prompts

Version 1.2.33 fixes scheduled SSH collection when a device erases a pagination
marker and writes its final prompt on the same line. Huawei can continue using
Space even when `screen-length 0 temporary` is unavailable. After `display
version`, its `ESC[16D` cursor-left sequences erase `More`; lowercasing those
sequences changed their meaning and caused a false prompt timeout.

Prompt recognition now interprets the original response before normalizing text.
The separate pagination matcher still sends one Space for each supported marker.
Same-node prompt identity, response settling, incomplete-response rejection,
connection budgets and log-preservation behavior remain enabled.

Validation includes fragmented ANSI-erased pagers, three consecutive pages,
wrong-node and unfinished responses, Huawei HRP and memory fallback, ZTE
privilege checks, other vendor prompt boundaries and 20 concurrent reader
fixtures. Five regression suites pass 608 checks. Replaying a captured response
with 1-, 7- and 8192-byte chunks reproduces the old timeout and verifies the fix;
an isolated device collection also completed through `quit`.

Install the new Bot runtime for future jobs. This change does not require a
MapViewer, Terminal or TrueLogs service restart. Existing TrueLogs jobs retain
their pinned runtime. Publishing a Bot version does not publish collected MAP
snapshots; honor the separate operator hold on those files and restarts.
