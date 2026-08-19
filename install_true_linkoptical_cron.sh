#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INCREMENTAL_CRON_TIME="${INCREMENTAL_CRON_TIME:-0 2 * * *}"
FULL_CRON_TIME="${FULL_CRON_TIME:-0 18 * * 5}"
INCREMENTAL_RUN_SCRIPT="$APP_DIR/run_true_linkoptical_auto_incremental_to_mapviewer.sh"
FULL_RUN_SCRIPT="$APP_DIR/run_true_linkoptical_auto_allsite_to_mapviewer.sh"
MARKER="# BotGetLog TRUE Link Optical Auto"
INCREMENTAL_MARKER="# BotGetLog TRUE Link Optical Auto Incremental"
FULL_MARKER="# BotGetLog TRUE Link Optical Auto Weekly Full"

if [[ ! -f "$INCREMENTAL_RUN_SCRIPT" ]]; then
  echo "[ERROR] run script not found: $INCREMENTAL_RUN_SCRIPT" >&2
  exit 1
fi
if [[ ! -f "$FULL_RUN_SCRIPT" ]]; then
  echo "[ERROR] run script not found: $FULL_RUN_SCRIPT" >&2
  exit 1
fi

chmod +x "$INCREMENTAL_RUN_SCRIPT" "$FULL_RUN_SCRIPT"

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

crontab -l 2>/dev/null \
  | grep -v -F "$MARKER" \
  | grep -v -F "$INCREMENTAL_RUN_SCRIPT" \
  | grep -v -F "$FULL_RUN_SCRIPT" > "$tmp" || true
{
  echo "$INCREMENTAL_MARKER"
  echo "$INCREMENTAL_CRON_TIME $INCREMENTAL_RUN_SCRIPT >> $APP_DIR/dist/_output/System_Log/cron.log 2>&1"
  echo "$FULL_MARKER"
  echo "$FULL_CRON_TIME $FULL_RUN_SCRIPT >> $APP_DIR/dist/_output/System_Log/cron.log 2>&1"
} >> "$tmp"

crontab "$tmp"
echo "[OK] Installed incremental cron: $INCREMENTAL_CRON_TIME $INCREMENTAL_RUN_SCRIPT"
echo "[OK] Installed weekly full cron: $FULL_CRON_TIME $FULL_RUN_SCRIPT"
