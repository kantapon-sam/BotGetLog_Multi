#!/usr/bin/env bash
set -euo pipefail

SOURCE_RUNNER="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/run_true_linkoptical_checkpoint_cycle.sh}"
TEST_ROOT="$(mktemp -d)"
export TEST_ROOT
trap 'rm -rf "$TEST_ROOT"' EXIT

APP_DIR="$TEST_ROOT/app"
MAP_DIR="$TEST_ROOT/map"
mkdir -p "$APP_DIR/dist/_output/System_Log" "$MAP_DIR/_input" "$TEST_ROOT/bin"
cp "$SOURCE_RUNNER" "$APP_DIR/run_true_linkoptical_checkpoint_cycle.sh"
chmod +x "$APP_DIR/run_true_linkoptical_checkpoint_cycle.sh"
printf 'Site code,IP loopback,Interface,Neighbor SysName\n' \
  > "$MAP_DIR/_input/DataLLDP_Neighbor_2026-01-01_000000.csv"

cat > "$TEST_ROOT/bin/pgrep" <<'SH'
#!/usr/bin/env bash
exit 1
SH

cat > "$APP_DIR/run_true_linkoptical_auto_allsite_single_pass_to_mapviewer.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
: > "${BOT_NEW_SITE_QUEUE_FILE:?}"
SH

cat > "$TEST_ROOT/retry.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
: > "${BOT_NEW_SITE_QUEUE_FILE:?}"
SH

cat > "$TEST_ROOT/publish.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
destination="${MAPVIEWER_INPUT_DIR:?}"
mkdir -p "$destination"
printf '%s|%s\n' "$destination" "${MAPVIEWER_RESTART_AFTER_MERGE:-unset}" > "$TEST_ROOT/initial-publish-policy"
output="$destination/DataLLDP_Neighbor_2030-01-01_000001.csv"
printf 'Site code,IP loopback,Interface,Neighbor SysName\n' > "$output"
touch -d '2030-01-01 00:00:01' "$output"
SH

cat > "$TEST_ROOT/targeted.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf '%s,%s\n' \
  "${BOT_TARGETED_RING_DEFER_MAPVIEWER_RESTART:-unset}" \
  "${BOT_TARGETED_RING_INITIAL_RESTART_PENDING:-unset},${BOT_TARGETED_RING_FINAL_PUBLICATION_REQUIRED:-unset}" \
  > "$TEST_ROOT/targeted-policy"
[[ -n "${TRUE_RING_REFRESH_STAGING_INPUT_DIR:-}" ]]
bash "${MAPVIEWER_RESTART_SCRIPT:?}"
SH

cat > "$TEST_ROOT/restart.sh" <<'SH'
#!/usr/bin/env bash
set -euo pipefail
printf 'restart\n' >> "$TEST_ROOT/restart-count"
SH

chmod +x "$TEST_ROOT/bin/pgrep" \
  "$APP_DIR/run_true_linkoptical_auto_allsite_single_pass_to_mapviewer.sh" \
  "$TEST_ROOT/retry.sh" "$TEST_ROOT/publish.sh" "$TEST_ROOT/targeted.sh" "$TEST_ROOT/restart.sh"

PATH="$TEST_ROOT/bin:$PATH" \
BOT_DIST_DIR="$APP_DIR/dist" \
MAPVIEWER_DIR="$MAP_DIR" \
MAPVIEWER_INPUT_DIR="$MAP_DIR/_input" \
MAPVIEWER_RESTART_SCRIPT="$TEST_ROOT/restart.sh" \
RETRY_SCRIPT="$TEST_ROOT/retry.sh" \
FINAL_PUBLISH_SCRIPT="$TEST_ROOT/publish.sh" \
FULL_PRIMARY_SCRIPT="$APP_DIR/run_true_linkoptical_auto_allsite_single_pass_to_mapviewer.sh" \
TARGETED_RING_SCRIPT="$TEST_ROOT/targeted.sh" \
BOT_TARGETED_RING_REFRESH_ENABLED=1 \
BOT_TARGETED_RING_PLAN_ONLY=0 \
BOT_DEFER_INITIAL_MAPVIEWER_RESTART=1 \
  bash "$APP_DIR/run_true_linkoptical_checkpoint_cycle.sh" full > "$TEST_ROOT/run.log" 2>&1

grep -q '^.*/.true-access-ring-refresh-staging\..*|false$' "$TEST_ROOT/initial-publish-policy"
[[ "$(cat "$TEST_ROOT/targeted-policy")" == "1,1,1" ]]
[[ "$(wc -l < "$TEST_ROOT/restart-count" | tr -d ' ')" == "1" ]]
grep -q 'Initial MapViewer restart will be deferred' "$TEST_ROOT/run.log"
grep -q 'initial merged snapshot in isolated staging' "$TEST_ROOT/run.log"
echo "PASS: initial publication staged and exactly one final restart coordinated with Ring refresh"
