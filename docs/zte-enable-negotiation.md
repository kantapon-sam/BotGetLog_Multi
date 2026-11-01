# ZTE privilege negotiation (1.2.30)

TRUE collection checks privilege after node authentication and before paging or
collection commands. A fresh user prompt (`NODE>`) sends `enable`; a normal
primary login at `NODE#` keeps its existing behavior. SSH login and reconnect
responses replace any older prompt state, so a previous privileged session cannot
hide a new user-mode session.

An optional CLLS backup login to ZTE explicitly sends `enable` even when the login
prompt is `NODE#`. Some alternative accounts show that prompt before completing
Enable authentication. Reconnecting with the selected backup repeats negotiation
for the new session. This does not try another CLLS account on command errors or
obtain an additional connection outside the usual collection admission limits.

- If the device requests `Password:`, use only the saved encrypted ZTE Enable
  passwords, in their configured order and attempt limit.
- If `enable` returns the same node's plain privileged prompt without an error,
  continue without sending a password. No saved password is required in this case.
- An error, timeout, lock, wrong node or configuration-mode prompt stops collection.
  Error text followed by `NODE#` is not a successful privilege transition.
- A password request with no saved Enable password stops collection. The node
  login password is never guessed or substituted as an Enable password.

Enable secrets remain in `launcher-data/device-enable.enc` and its existing key.
Installing this release does not change those settings. Console diagnostics record
slot outcomes without passwords. The alternative-user Note continues to describe
successful node authentication; completed-log validation separately rejects failed
collection commands.

Offline Java 8 regressions cover primary and backup streams, explicit backup `#`,
password and password-free transitions, stale prompt recovery, error plus `#`,
wrong passwords, timeouts and same-node checks. These tests do not establish that
any particular production device has completed collection; verify its live log
with the installed collector validator before export.
