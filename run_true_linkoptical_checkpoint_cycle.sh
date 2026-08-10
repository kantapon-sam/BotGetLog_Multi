#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOT_DIST_DIR="${BOT_DIST_DIR:-$APP_DIR/dist}"
LOG_DIR="$BOT_DIST_DIR/_output/System_Log"
MODE="${1:-}"
CYCLE_LOCK_DIR="$LOG_DIR/.true-linkoptical-cycle.lock"
RUN_LOG="$LOG_DIR/true-linkoptical-${MODE:-invalid}-checkpoint-cycle-$(date +%Y%m%d-%H%M%S).log"
RETRY_SCRIPT="${RETRY_SCRIPT:-$APP_DIR/run_true_linkoptical_checkpoint_rerun_to_mapviewer.sh}"
FINAL_PUBLISH_SCRIPT="${FINAL_PUBLISH_SCRIPT:-$APP_DIR/publish_true_linkoptical_all_completed_to_mapviewer.sh}"
FULL_PRIMARY_SCRIPT="${FULL_PRIMARY_SCRIPT:-$APP_DIR/run_true_linkoptical_auto_allsite_single_pass_to_mapviewer.sh}"
CHECKPOINT_RETRY_THREADS="${TRUE_CHECKPOINT_RETRY_THREADS:-10}"
PRIMARY_THREADS="${TRUE_PRIMARY_THREADS:-20}"
PRIMARY_FAILURE_PHASE="PRIMARY_THREAD_${PRIMARY_THREADS}"
RETRY_FAILURE_PHASE="RETRY_THREAD_${CHECKPOINT_RETRY_THREADS}"
PRIMARY_CLEAN_TYPES="${BOT_CLEAN_TOTAL_LOG_TYPES:-}"
NEW_SITE_QUEUE_MAX_ROUNDS="${BOT_NEW_SITE_QUEUE_MAX_ROUNDS:-${BOT_SITE_UPDATE_MAX_FULL_RERUNS:-20}}"
SITE_UPDATE_RESULT_FILE="$LOG_DIR/.true-linkoptical-site-update-result.$$"
NEW_SITE_QUEUE_FILE="$LOG_DIR/.true-linkoptical-new-site-queue.$$"
NEW_SITE_QUEUE_AUDIT="$LOG_DIR/true-linkoptical-new-site-queue-$(date +%Y%m%d-%H%M%S).log"
PRIMARY_CLEAN_TOTAL_LOG="${BOT_CLEAN_TOTAL_LOG_BEFORE_RUN:-0}"
PRIMARY_KEEP_TOTAL_LOG_HISTORY=1

mkdir -p "$LOG_DIR"

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$RUN_LOG"
}

case "$MODE" in
  full)
    PRIMARY_SCRIPT="$APP_DIR/run_true_linkoptical_auto_allsite_single_pass_to_mapviewer.sh"
    ;;
  incremental)
    PRIMARY_SCRIPT="$APP_DIR/run_true_linkoptical_auto_incremental_single_pass_to_mapviewer.sh"
    ;;
  *)
    log "[ERROR] Usage: $0 {full|incremental}"
    exit 2
    ;;
esac

if [[ ! -x "$PRIMARY_SCRIPT" ]]; then
  log "[ERROR] Primary single-pass runner not found or not executable: $PRIMARY_SCRIPT"
  exit 1
fi
if [[ ! -x "$RETRY_SCRIPT" ]]; then
  log "[ERROR] Checkpoint retry runner not found or not executable: $RETRY_SCRIPT"
  exit 1
fi
if [[ ! -x "$FINAL_PUBLISH_SCRIPT" ]]; then
  log "[ERROR] Final Link Optical publish script not found or not executable: $FINAL_PUBLISH_SCRIPT"
  exit 1
fi
if [[ ! -x "$FULL_PRIMARY_SCRIPT" ]]; then
  log "[ERROR] Full single-pass runner not found or not executable: $FULL_PRIMARY_SCRIPT"
  exit 1
fi
if ! [[ "$NEW_SITE_QUEUE_MAX_ROUNDS" =~ ^[0-9]+$ ]]; then
  log "[ERROR] BOT_NEW_SITE_QUEUE_MAX_ROUNDS must be a non-negative integer: $NEW_SITE_QUEUE_MAX_ROUNDS"
  exit 2
fi
if ! [[ "$PRIMARY_THREADS" =~ ^[0-9]+$ ]] || [[ "$PRIMARY_THREADS" -lt 1 ]] || [[ "$PRIMARY_THREADS" -gt 200 ]]; then
  log "[ERROR] Invalid TRUE_PRIMARY_THREADS=$PRIMARY_THREADS"
  exit 2
fi
if [[ "$PRIMARY_CLEAN_TOTAL_LOG" != "0" && "$PRIMARY_CLEAN_TOTAL_LOG" != "1" ]]; then
  log "[ERROR] BOT_CLEAN_TOTAL_LOG_BEFORE_RUN must be 0 or 1: $PRIMARY_CLEAN_TOTAL_LOG"
  exit 2
fi
if [[ "$MODE" == "full" && "$PRIMARY_CLEAN_TOTAL_LOG" == "1" ]]; then
  PRIMARY_KEEP_TOTAL_LOG_HISTORY=0
fi

running_pid="$(pgrep -f 'BotGetLog_TrueCorp.jar.*--auto-link-optical' | head -n 1 || true)"
if [[ -n "$running_pid" ]]; then
  log "[SKIP] TRUE Link Optical is already running (pid=$running_pid). Entire $MODE checkpoint cycle was not started."
  exit 0
fi

if ! mkdir "$CYCLE_LOCK_DIR" 2>/dev/null; then
  lock_pid=""
  [[ -f "$CYCLE_LOCK_DIR/pid" ]] && lock_pid="$(cat "$CYCLE_LOCK_DIR/pid" 2>/dev/null || true)"
  if [[ -n "$lock_pid" ]] && kill -0 "$lock_pid" 2>/dev/null; then
    log "[SKIP] TRUE Link Optical checkpoint cycle is already active (pid=$lock_pid)."
    exit 0
  fi
  log "[WARN] Removing stale checkpoint cycle lock."
  rm -rf "$CYCLE_LOCK_DIR"
  mkdir "$CYCLE_LOCK_DIR"
fi
echo "$$" > "$CYCLE_LOCK_DIR/pid"

cleanup() {
  rm -f "$SITE_UPDATE_RESULT_FILE" "$SITE_UPDATE_RESULT_FILE.tmp."* 2>/dev/null || true
  rm -f "$NEW_SITE_QUEUE_FILE" "$NEW_SITE_QUEUE_FILE.tmp."* 2>/dev/null || true
  if [[ -f "$CYCLE_LOCK_DIR/pid" ]] && [[ "$(cat "$CYCLE_LOCK_DIR/pid" 2>/dev/null || true)" == "$$" ]]; then
    rm -rf "$CYCLE_LOCK_DIR"
  fi
}
trap cleanup EXIT INT TERM
rm -f "$NEW_SITE_QUEUE_FILE" "$NEW_SITE_QUEUE_FILE.tmp."* 2>/dev/null || true

if [[ "$PRIMARY_CLEAN_TOTAL_LOG" == "1" ]]; then
  if [[ "$MODE" == "full" ]]; then
    log "Starting TRUE Link Optical $MODE primary collection pass with threadPoolSize=$PRIMARY_THREADS. Total_Log cleanup is enabled for Type(s)=${PRIMARY_CLEAN_TYPES:-ALL}; publication is deferred."
  else
    log "Starting TRUE Link Optical $MODE primary collection pass with workbook thread settings. Total_Log cleanup is enabled for Type(s)=${PRIMARY_CLEAN_TYPES:-ALL}; publication is deferred."
  fi
else
  if [[ "$MODE" == "full" ]]; then
    log "Starting TRUE Link Optical $MODE primary collection pass with threadPoolSize=$PRIMARY_THREADS. Total_Log cleanup is disabled; publication is deferred."
  else
    log "Starting TRUE Link Optical $MODE primary collection pass with workbook thread settings. Total_Log cleanup is disabled; publication is deferred."
  fi
fi
primary_args=()
if [[ "$MODE" == "full" ]]; then
  primary_args+=("--link-optical-threads=$PRIMARY_THREADS")
fi
set +e
BOT_CLEAN_TOTAL_LOG_BEFORE_RUN="$PRIMARY_CLEAN_TOTAL_LOG" \
BOT_KEEP_TOTAL_LOG_HISTORY="$PRIMARY_KEEP_TOTAL_LOG_HISTORY" \
BOT_CLEAN_TOTAL_LOG_TYPES="$PRIMARY_CLEAN_TYPES" \
TRUE_LINKOPTICAL_FAILURE_PHASE="$PRIMARY_FAILURE_PHASE" \
BOT_LINKOPTICAL_JAR_EXPORT_MODE=prescan \
BOT_NEW_SITE_QUEUE_FILE="$NEW_SITE_QUEUE_FILE" \
BOT_DEFER_MAPVIEWER_PUBLISH=1 \
  "$PRIMARY_SCRIPT" "${primary_args[@]}" 2>&1 | tee -a "$RUN_LOG"
primary_status="${PIPESTATUS[0]}"
set -e

if [[ "$primary_status" -ne 0 ]]; then
  log "[ERROR] TRUE Link Optical $MODE primary pass failed with status=$primary_status; checkpoint rerun not started."
  exit "$primary_status"
fi

retry_status=0
case "$(printf '%s' "${BOT_ENABLE_CHECKPOINT_RETRY:-1}" | tr '[:upper:]' '[:lower:]')" in
  0|false|no|off)
    log "[INFO] Checkpoint rerun disabled by BOT_ENABLE_CHECKPOINT_RETRY=${BOT_ENABLE_CHECKPOINT_RETRY}."
    ;;
  *)
    log "Primary collection pass finished. Starting no-date checkpoint rerun with reduced threads before final publication."
    # Sites discovered by the primary pass are already present in the workbook
    # and are therefore included by this all-site checkpoint pass. Its pre-scan
    # replaces the queue with only the next generation discovered afterwards.
    rm -f "$NEW_SITE_QUEUE_FILE"
    set +e
    TRUE_CHECKPOINT_RETRY_THREADS="$CHECKPOINT_RETRY_THREADS" \
    TRUE_LINKOPTICAL_FAILURE_PHASE="$RETRY_FAILURE_PHASE" \
    BOT_CLEAN_TOTAL_LOG_BEFORE_RUN=0 \
    BOT_KEEP_TOTAL_LOG_HISTORY=1 \
    BOT_LINKOPTICAL_JAR_EXPORT_MODE=prescan \
    BOT_NEW_SITE_QUEUE_FILE="$NEW_SITE_QUEUE_FILE" \
    BOT_DEFER_MAPVIEWER_PUBLISH=1 \
      "$RETRY_SCRIPT" 2>&1 | tee -a "$RUN_LOG"
    retry_status="${PIPESTATUS[0]}"
    set -e
    ;;
esac

if [[ "$retry_status" -ne 0 ]]; then
  log "[ERROR] Checkpoint rerun failed with status=$retry_status. Recursive discovery and final publication were not started."
  exit "$retry_status"
fi

queue_round=0
queue_pending=0
while [[ -s "$NEW_SITE_QUEUE_FILE" ]]; do
  if (( queue_round >= NEW_SITE_QUEUE_MAX_ROUNDS )); then
    pending_queue_file="$LOG_DIR/true-linkoptical-new-site-queue-pending-$(date +%Y%m%d-%H%M%S).txt"
    cp -f "$NEW_SITE_QUEUE_FILE" "$pending_queue_file"
    log "[QUEUE][PENDING] New sites remain after $queue_round recursive round(s); limit=$NEW_SITE_QUEUE_MAX_ROUNDS. Saved for audit/next schedule: $pending_queue_file"
    queue_pending=1
    break
  fi

  queue_round=$((queue_round + 1))
  queue_count="$(awk 'NF { count++ } END { print count + 0 }' "$NEW_SITE_QUEUE_FILE")"
  queue_preview="$(awk 'NF { gsub(/\r/, ""); printf "%s%s", separator, $0; separator=", " }' "$NEW_SITE_QUEUE_FILE")"
  printf '%s round=%d count=%s sites=%s\n' "$(date '+%Y-%m-%d %H:%M:%S')" \
    "$queue_round" "$queue_count" "$queue_preview" >> "$NEW_SITE_QUEUE_AUDIT"
  log "[QUEUE] Recursive new-site round $queue_round/$NEW_SITE_QUEUE_MAX_ROUNDS: count=$queue_count sites=$queue_preview"
  log "[QUEUE] Running only these newly added sites with threadPoolSize=$CHECKPOINT_RETRY_THREADS; Total_Log cleanup is disabled."

  set +e
  GRD_USERINPUT_SYNC_SCRIPT=/bin/true \
  BOT_CLEAN_TOTAL_LOG_BEFORE_RUN=0 \
  BOT_KEEP_TOTAL_LOG_HISTORY=1 \
  BOT_CLEAN_TOTAL_LOG_TYPES="" \
  TRUE_LINKOPTICAL_FAILURE_PHASE="NEW_SITE_THREAD_${CHECKPOINT_RETRY_THREADS}" \
  BOT_LINKOPTICAL_SITE_QUEUE_FILE="$NEW_SITE_QUEUE_FILE" \
  BOT_NEW_SITE_QUEUE_FILE="$NEW_SITE_QUEUE_FILE" \
  BOT_LINKOPTICAL_JAR_EXPORT_MODE=prescan \
  BOT_DEFER_MAPVIEWER_PUBLISH=1 \
    "$FULL_PRIMARY_SCRIPT" "--link-optical-threads=$CHECKPOINT_RETRY_THREADS" 2>&1 | tee -a "$RUN_LOG"
  queue_status="${PIPESTATUS[0]}"
  set -e
  if [[ "$queue_status" -ne 0 ]]; then
    log "[ERROR] Recursive new-site round $queue_round failed with status=$queue_status. Final publication was not started."
    exit "$queue_status"
  fi
done

if [[ "$queue_pending" -eq 0 ]]; then
  log "[QUEUE] Recursive discovery reached closure after $queue_round round(s); no newly added site remains."
fi

log "Collection and recursive discovery finished. Exporting all completed Total_Log files and merging MapViewer once."
rm -f "$SITE_UPDATE_RESULT_FILE"
set +e
BOT_DIST_DIR="$BOT_DIST_DIR" \
BOT_SITE_UPDATE_RESULT_FILE="$SITE_UPDATE_RESULT_FILE" \
  "$FINAL_PUBLISH_SCRIPT" 2>&1 | tee -a "$RUN_LOG"
publish_status="${PIPESTATUS[0]}"
set -e
if [[ "$publish_status" -ne 0 ]]; then
  log "[ERROR] Final Link Optical publication failed with status=$publish_status."
  exit "$publish_status"
fi
if [[ -s "$SITE_UPDATE_RESULT_FILE" ]]; then
  log "[INFO] Final merged-data sync added inventory outside this Link Optical queue; it will be collected by the next scheduled cycle: $(cat "$SITE_UPDATE_RESULT_FILE")"
fi

log "Finished TRUE Link Optical $MODE checkpoint cycle (recursive new-site rounds=$queue_round, pending=$queue_pending, final publications=1)."
