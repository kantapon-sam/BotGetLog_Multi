BotGetLog_Multi
===============

Included tools:
- Bot Tool Launcher.jar
- BotGetLog_TrueCorp.jar
- BotGetLog_DTAC.jar
- Link_Optical.jar
- ARP.jar
- PTP.jar
- MPLS_LSP.jar
- SID.jar
- Segment_Routing_Prefix.jar
- ISIS_Peer.jar
- Deleted_Log_Checker.jar

How to run:
1. Portable package: run run.bat.
2. Dist package: run "Bot Tool Launcher.jar" with Java.

Required folders in the application directory:
- defaults
- lib
- updater
- _output

Default input files:
- defaults\UserInterface_Input.xlsx
- defaults\NEW_Site.csv

Update behavior:
- Long GitHub release notes are parsed without recursive regex backtracking,
  preventing a Java 8 StackOverflowError before the launcher window opens.
- The updater replaces application jars, defaults, lib, updater, and README.TXT.
- The updater keeps UserInterface_Input.xlsx, _output, and launcher-data (including saved ZTE Enable passwords).
- SID.jar exports Huawei, ZTE, and Nokia loopback/SID rows. Segment_Routing_Prefix.jar remains as a compatibility entry point.

ZTE Enable settings:
- Open Bot Settings - ZTE Enable in the Windows launcher.
- No Enable passwords are embedded in the application. Add passwords and Save before first use.
- Only saved passwords are used. Clear saved passwords or delete device-enable.enc to leave no passwords for new connections.
- Passwords are stored encrypted in launcher-data/device-enable.enc with device-enable.key; keep both when backing up or moving saved settings.
- Store up to five passwords: one primary and four fallbacks. Passwords are case-sensitive.
- The default attempt limit is two. The collector stops as soon as privileged mode succeeds.
- Leave a saved field blank to keep its value; use Show to view it or the arrows to change priority.
- Changes apply to new node connections. Each Windows installation has its own settings.
- Server settings are managed separately by an Admin in Control Console > Bot Settings - ZTE Enable.

Juniper Link Optical collection:
- At login, the collector accepts the configured hostname or its re0/re1 peer
  on the same chassis, then pins the actual RE name for prompts and the log file.
  Different chassis names and RE changes during collection remain failures.
- Junos ---(more)--- and percentage paging prompts are continued automatically.
- An interrupted command fails the current attempt. The existing bounded node
  retry starts again at paging setup and preserves the incomplete log as evidence;
  reconnecting alone never counts as a completed command. Daily limits still apply.

ZTE Link Optical bandwidth:
- Max BW uses the physical interface's reported BW from show interface. The
  transceiver type in show opt brief is only a fallback for logs without BW.
  This avoids exporting a 6G module label as the capacity of a 10G interface,
  and retains negotiated 1G/100M rates on faster-capable modules.

Maintainer release publication:
- A public version update includes a GitHub Release with dist and portable ZIPs,
  checksums, and update/version.json pointing to the verified downloadable dist
  ZIP. Publish and verify assets before committing the updater manifest.
- TrueLogs uses a separate immutable Bot runtime. Updating the scheduled Bot
  alone does not update TrueLogs. Configured builds publish it automatically
  after the complete dist build; release.ps1 -SkipBuild invokes the same target.
- In ignored nbproject/private/private.properties, set true.web.publish.script
  to the companion LLDP_MapViewer/true-log-console/scripts/publish.ps1 and set
  true.web.publish.config to that publisher's private JSON configuration.
  Set true.web.publish.powershell to pwsh.exe when using a PowerShell 7 host.
  Never commit SSH credentials or DPAPI files. Unconfigured developer builds
  do not publish. A configured publication failure fails the build/release.
- The runtime installer validates checksums and worker compatibility before
  switching the pointer. New jobs use the new Bot; running jobs retain their
  pinned runtime. No TrueLogs restart is needed for a Bot-only update.
