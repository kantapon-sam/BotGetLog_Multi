# ZTE privilege negotiation

Version 1.2.32 accepts a balanced parenthesized site alias in a ZTE hostname,
for example `CPE-LBR0053_(LBR8566)#` or `CPE-LBR0053_(LBR8566)>`.
Earlier privilege checks rejected those names after successful authentication
and recorded `ZTE enable failed` before collecting commands. The alias remains
part of the same-node identity check; a different alias, malformed parentheses,
`(config)` and `(config-...)` prompts still fail. Primary and backup logins use
the same check, including password-free enable and saved-password negotiation.
This does not change saved credentials, retry allowances or retained logs.

Regression coverage replays all ten reported hostname shapes through the actual
login and enable readers, with both account roles and both prompt modes. It also
checks ANSI decoration, filename normalization, wrong aliases and config modes.
Run this regression with Java 8 and the built Bot JAR plus its `lib/*` classpath:
`com.java.botgetlog.truecorp.ZtePrivilegeRegression`.

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
