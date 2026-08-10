#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOT_DIST_DIR="${BOT_DIST_DIR:-$APP_DIR/dist}"
LOG_DIR="$BOT_DIST_DIR/_output/System_Log"
SINGLE_PASS_SCRIPT="${SINGLE_PASS_SCRIPT:-$APP_DIR/run_true_linkoptical_auto_allsite_single_pass_to_mapviewer.sh}"
CHECKPOINT_RETRY_THREADS="${TRUE_CHECKPOINT_RETRY_THREADS:-10}"
FAILURE_PHASE="${TRUE_LINKOPTICAL_FAILURE_PHASE:-RETRY_THREAD_${CHECKPOINT_RETRY_THREADS}}"
JAR_EXPORT_MODE="${BOT_LINKOPTICAL_JAR_EXPORT_MODE:-skip}"
RUN_LOG="$LOG_DIR/true-linkoptical-checkpoint-rerun-$(date +%Y%m%d-%H%M%S).log"

mkdir -p "$LOG_DIR"

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$RUN_LOG"
}

if ! [[ "$CHECKPOINT_RETRY_THREADS" =~ ^[0-9]+$ ]] || [[ "$CHECKPOINT_RETRY_THREADS" -lt 1 ]] || [[ "$CHECKPOINT_RETRY_THREADS" -gt 200 ]]; then
  log "[ERROR] Invalid TRUE_CHECKPOINT_RETRY_THREADS=$CHECKPOINT_RETRY_THREADS"
  exit 1
fi
if [[ ! -x "$SINGLE_PASS_SCRIPT" ]]; then
  log "[ERROR] Single-pass runner not found or not executable: $SINGLE_PASS_SCRIPT"
  exit 1
fi

running_pid="$(pgrep -f 'BotGetLog_TrueCorp.jar.*--auto-link-optical' | head -n 1 || true)"
if [[ -n "$running_pid" ]]; then
  log "[SKIP] TRUE Link Optical is already running (pid=$running_pid). Checkpoint rerun was not started."
  exit 0
fi

if [[ -n "${BOT_NEW_SITE_QUEUE_FILE:-}" && "$JAR_EXPORT_MODE" == "prescan" ]]; then
  log "[THREAD] Checkpoint uses --link-optical-threads=$CHECKPOINT_RETRY_THREADS and pre-scans only logs created/changed in this pass. Newly discovered sites are added to UserInterface_Input.xlsx and queued in $BOT_NEW_SITE_QUEUE_FILE."
else
  log "[THREAD] Checkpoint uses --link-optical-threads=$CHECKPOINT_RETRY_THREADS."
fi
log "[INFO] Retry uses existing Total_Log as the checkpoint; no date filter and no Total_Log deletion."
log "[EXPORT] Checkpoint JAR export mode=$JAR_EXPORT_MODE; final all-completed publication is handled by the cycle."

set +e
GRD_USERINPUT_SYNC_SCRIPT=/bin/true \
BOT_CLEAN_TOTAL_LOG_BEFORE_RUN=0 \
BOT_KEEP_TOTAL_LOG_HISTORY=1 \
BOT_ENABLE_CHECKPOINT_RETRY=0 \
TRUE_LINKOPTICAL_FAILURE_PHASE="$FAILURE_PHASE" \
BOT_LINKOPTICAL_JAR_EXPORT_MODE="$JAR_EXPORT_MODE" \
BOT_DEFER_MAPVIEWER_PUBLISH="${BOT_DEFER_MAPVIEWER_PUBLISH:-0}" \
  "$SINGLE_PASS_SCRIPT" "--link-optical-threads=$CHECKPOINT_RETRY_THREADS" 2>&1 | tee -a "$RUN_LOG"
status="${PIPESTATUS[0]}"
set -e

if [[ "$status" -ne 0 ]]; then
  log "[ERROR] TRUE Link Optical checkpoint rerun failed with status=$status."
  exit "$status"
fi

log "Finished TRUE Link Optical checkpoint rerun with threadPoolSize=$CHECKPOINT_RETRY_THREADS."
