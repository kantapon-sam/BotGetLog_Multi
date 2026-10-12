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
- The updater replaces application jars, defaults, lib, updater, and README.TXT.
- The updater keeps UserInterface_Input.xlsx, _output, and launcher-data (including saved ZTE Enable passwords).
- SID.jar exports Huawei, ZTE, and Nokia loopback/SID rows. Segment_Routing_Prefix.jar remains as a compatibility entry point.

ZTE Enable settings:
- Open Bot Settings - ZTE Enable in the Windows launcher.
- Store up to five passwords: one primary and four fallbacks. Passwords are case-sensitive.
- The default attempt limit is two. The collector stops as soon as privileged mode succeeds.
- Leave a saved field blank to keep its value; use Show to view it or the arrows to change priority.
- Changes apply to new node connections. Each Windows installation has its own settings.
- Server settings are managed separately by an Admin in Control Console > Bot Settings - ZTE Enable.
