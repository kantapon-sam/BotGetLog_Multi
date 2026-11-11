# BotGetLog Multi 1.2.40

TRUE Auto-add now follows the configured IPv4 Allow and Exclude ranges. Exclude takes priority. The default excludes `10.207.0.0/16`. Existing inventory and manual additions in that range remain available for collection.

The collector reads the destination management IP from the port Description and uses the LLDP neighbor name on that port to verify rediscovery. Previously removed unreachable nodes and duplicate chassis addresses are remembered across collection days.

Scheduled server runs use the rules saved in Control Console. Standalone Desktop runs read `_config/auto-add-ip.properties` beside their workbook, with the same defaults. The Windows launcher checks for this release through the existing Auto Update channel. The portable package includes its Java runtime.
