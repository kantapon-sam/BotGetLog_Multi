# Daily distinct-IP budget (1.2.26)

The scheduled 20:00 workflow may enable a 7,500 distinct management-IP budget
shared by the primary pass, RX, SFP, checkpoint retries, discovery and Ring work.
An IP counts once across all these phases and across restarts. Retrying the
same IP still uses its existing per-IP attempt allowance, without consuming
another distinct-IP slot. The Friday Full remains uncapped by this policy.

The companion LLDP_MapViewer scheduler freezes the policy in the assigned
collection cycle's `unique-node-policy.properties`. A durable append journal
records admission under the existing cross-process budget lock; simultaneous
workers and separate JVMs cannot claim extra ordinary slots. Admission is
conservative after a crash: reserved IPs remain counted. Damaged policy,
journal or exception evidence fails closed instead of resetting the budget.

The companion Ring planner uses the remaining total budget for whole eligible
Chain/SubChain groups, prioritizing groups not sampled recently and using a
deterministic daily shuffle for ties. Complete logs remain protected by the
existing reciprocal-LLDP checks. Rotation history records sampled Ring IDs;
sampling does not certify successful collection of every member.

Only exact IPs with planner evidence of an LLDP topology change, one-way link,
physical port mismatch, or newly discovered LLDP neighbor may exceed the
distinct-IP cap. Exception scope contains the assigned day, reason and audit
reference. Unchanged Chain membership and unrelated inventory additions are
not exceptions. Ordinary work cannot use spare slots after LLDP overflow.

Deploy the collector and matching companion scheduler/planner together while
the full collection workflow is idle. Preserve workbooks, logs, existing
attempt counters and the rotation journal. No change to Topo/MAP/SPOF drawing,
collection commands, concurrent connection limits or Friday cron is required.
