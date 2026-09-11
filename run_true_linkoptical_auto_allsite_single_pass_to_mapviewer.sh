#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOT_DIST_DIR="${BOT_DIST_DIR:-$APP_DIR/dist}"
MAPVIEWER_INPUT_DIR="${MAPVIEWER_INPUT_DIR:-/transport/LLDP_MapViewer/_input}"
MAPVIEWER_LOG_DIR="${MAPVIEWER_LOG_DIR:-$(cd "$(dirname "$MAPVIEWER_INPUT_DIR")" && pwd)/_logs}"
LLDP_DATA_QUALITY_FILE="${LLDP_DATA_QUALITY_FILE:-$MAPVIEWER_LOG_DIR/lldp_data_quality_latest.csv}"
MAPVIEWER_DIR="${MAPVIEWER_DIR:-$(cd "$(dirname "$MAPVIEWER_INPUT_DIR")" && pwd)}"
DTAC_LLDPMAP_MANUAL_DIR="${DTAC_LLDPMAP_MANUAL_DIR:-$MAPVIEWER_DIR/_manual_dtac_lldp}"
LINKOPTICAL_MERGE_SCRIPT="${LINKOPTICAL_MERGE_SCRIPT:-$MAPVIEWER_DIR/scripts/merge_linkoptical_manual_dtac.sh}"
LINKOPTICAL_ALL_COMPLETED_EXPORT_SCRIPT="${LINKOPTICAL_ALL_COMPLETED_EXPORT_SCRIPT:-$MAPVIEWER_DIR/scripts/export_true_linkoptical_all_completed.sh}"
GRD_USERINPUT_SYNC_SCRIPT="${GRD_USERINPUT_SYNC_SCRIPT:-$MAPVIEWER_DIR/scripts/sync_grd_nodes_to_user_input.sh}"
JAVA_BIN="${JAVA_BIN:-/transport/java8/bin/java}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
JAR_PATH="${JAR_PATH:-$BOT_DIST_DIR/BotGetLog_TrueCorp.jar}"
FINAL_PUBLISH_SCRIPT="${FINAL_PUBLISH_SCRIPT:-$APP_DIR/publish_true_linkoptical_all_completed_to_mapviewer.sh}"
TYPE_LOG_CLEAN_SCRIPT="${TYPE_LOG_CLEAN_SCRIPT:-$APP_DIR/scripts/clean_true_linkoptical_logs_by_type.py}"
JAR_EXPORT_MODE="${BOT_LINKOPTICAL_JAR_EXPORT_MODE:-full}"
SITE_QUEUE_FILE="${BOT_LINKOPTICAL_SITE_QUEUE_FILE:-}"
NEW_SITE_QUEUE_FILE="${BOT_NEW_SITE_QUEUE_FILE:-}"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "[ERROR] BotGetLog_TrueCorp.jar not found: $JAR_PATH" >&2
  exit 1
fi
if [[ ! -d "$MAPVIEWER_INPUT_DIR" ]]; then
  echo "[ERROR] MapViewer input folder not found: $MAPVIEWER_INPUT_DIR" >&2
  exit 1
fi
case "$JAR_EXPORT_MODE" in
  full|prescan|skip) ;;
  *)
    echo "[ERROR] BOT_LINKOPTICAL_JAR_EXPORT_MODE must be full, prescan, or skip: $JAR_EXPORT_MODE" >&2
    exit 2
    ;;
esac

LOG_DIR="$BOT_DIST_DIR/_output/System_Log"
mkdir -p "$LOG_DIR"
RUN_LOG="$LOG_DIR/true-linkoptical-auto-$(date +%Y%m%d-%H%M%S).log"
RUN_LOCK_DIR="$LOG_DIR/.true-linkoptical-auto.lock"
RUN_MARKER=""

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$RUN_LOG"
}

is_truthy() {
  case "$(printf '%s' "${1:-0}" | tr '[:upper:]' '[:lower:]')" in
    1|true|yes|on) return 0 ;;
    *) return 1 ;;
  esac
}

clean_total_log_before_run() {
  local total_log_dir="$BOT_DIST_DIR/_output/Total_Log"
  local clean_types="${BOT_CLEAN_TOTAL_LOG_TYPES:-}"
  if [[ "${BOT_KEEP_TOTAL_LOG_HISTORY:-0}" == "1" ]]; then
    log "[INFO] Keeping existing Total_Log files because BOT_KEEP_TOTAL_LOG_HISTORY=1."
    return 0
  fi
  if [[ "${BOT_CLEAN_TOTAL_LOG_BEFORE_RUN:-1}" != "1" ]]; then
    log "[INFO] Total_Log full cleanup disabled for this run. Existing complete logs can be used as checkpoint."
    return 0
  fi

  mkdir -p "$total_log_dir"
  if [[ -n "$clean_types" ]]; then
    if [[ ! -f "$TYPE_LOG_CLEAN_SCRIPT" ]]; then
      log "[ERROR] Selective Total_Log cleanup script not found: $TYPE_LOG_CLEAN_SCRIPT"
      return 1
    fi
    log "[CLEAN] Removing Total_Log files for enabled workbook Type(s): $clean_types"
    "$PYTHON_BIN" "$TYPE_LOG_CLEAN_SCRIPT" \
      --workbook "$BOT_DIST_DIR/UserInterface_Input.xlsx" \
      --total-log-dir "$total_log_dir" \
      --types "$clean_types" \
      --daily-budget-dir "${TRUE_DAILY_COLLECTION_BUDGET_DIR:-$BOT_DIST_DIR/_output/System_Log/daily-collection-budget}" \
      --daily-limit "${TRUE_DAILY_COLLECTION_LIMIT:-3}" \
      --apply 2>&1 | tee -a "$RUN_LOG"
    return 0
  fi

  local count
  count="$(find "$total_log_dir" -maxdepth 1 -type f -name '*.txt' 2>/dev/null | wc -l | tr -d ' ')"
  if [[ "$count" == "0" ]]; then
    log "[CLEAN] Total_Log already empty."
    return 0
  fi

  "$PYTHON_BIN" "$TYPE_LOG_CLEAN_SCRIPT" \
    --workbook "$BOT_DIST_DIR/UserInterface_Input.xlsx" \
    --total-log-dir "$total_log_dir" --types ALL \
    --daily-budget-dir "${TRUE_DAILY_COLLECTION_BUDGET_DIR:-$BOT_DIST_DIR/_output/System_Log/daily-collection-budget}" \
    --daily-limit "${TRUE_DAILY_COLLECTION_LIMIT:-3}" \
    --apply 2>&1 | tee -a "$RUN_LOG"
}

sync_nodes_to_user_input() {
  local phase="${1:-scheduled}"
  if [[ ! -x "$GRD_USERINPUT_SYNC_SCRIPT" ]]; then
    log "[WARN] Node user-input sync script not found or not executable: $GRD_USERINPUT_SYNC_SCRIPT"
    return 0
  fi
  log "Syncing configured network nodes from latest LLDP and Utilization into UserInterface_Input.xlsx ($phase)."
  if ! BOT_DIST_DIR="$BOT_DIST_DIR" MAPVIEWER_INPUT_DIR="$MAPVIEWER_INPUT_DIR" MAPVIEWER_DIR="$MAPVIEWER_DIR" \
      "$GRD_USERINPUT_SYNC_SCRIPT" 2>&1 | tee -a "$RUN_LOG"; then
    log "[WARN] Node user-input sync failed during $phase; continuing TRUE Link Optical workflow."
  fi
}

find_running_auto_pid() {
  pgrep -f "^$JAVA_BIN .*BotGetLog_TrueCorp.jar.*--auto-link-optical" | head -n 1 || true
}

running_pid="$(find_running_auto_pid)"
if [[ -n "$running_pid" ]]; then
  log "[SKIP] TRUE Link Optical Auto is already running (pid=$running_pid). Skip this scheduled run."
  exit 0
fi

if ! mkdir "$RUN_LOCK_DIR" 2>/dev/null; then
  lock_pid=""
  if [[ -f "$RUN_LOCK_DIR/pid" ]]; then
    lock_pid="$(cat "$RUN_LOCK_DIR/pid" 2>/dev/null || true)"
  fi
  if [[ -n "$lock_pid" ]] && kill -0 "$lock_pid" 2>/dev/null; then
    log "[SKIP] TRUE Link Optical Auto lock is active (pid=$lock_pid). Skip this scheduled run."
    exit 0
  fi
  log "[WARN] Removing stale TRUE Link Optical Auto lock: $RUN_LOCK_DIR"
  rm -rf "$RUN_LOCK_DIR"
  mkdir "$RUN_LOCK_DIR"
fi
echo "$$" > "$RUN_LOCK_DIR/pid"

RUN_MARKER="$LOG_DIR/.true-linkoptical-run-marker-$$"
touch "$RUN_MARKER"

cleanup() {
  rm -f "$RUN_MARKER"
  if [[ -f "$RUN_LOCK_DIR/pid" ]] && [[ "$(cat "$RUN_LOCK_DIR/pid" 2>/dev/null || true)" == "$$" ]]; then
    rm -rf "$RUN_LOCK_DIR"
  fi
}
trap cleanup EXIT

copy_latest() {
  local pattern="$1"
  local count="$2"
  local found=0
  while IFS= read -r src; do
    [[ -n "$src" ]] || continue
    found=$((found + 1))
    local base
    base="$(basename "$src")"
    local tmp="$MAPVIEWER_INPUT_DIR/.$base.tmp.$$"
    cp -f "$src" "$tmp"
    mv -f "$tmp" "$MAPVIEWER_INPUT_DIR/$base"
    log "[COPY] $base -> $MAPVIEWER_INPUT_DIR/$base"
  done < <(find "$BOT_DIST_DIR/_output/LLDP_Neighbor" -maxdepth 1 -type f -name "$pattern" -newer "$RUN_MARKER" -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr \
    | head -n "$count" \
    | cut -d' ' -f2-)
  [[ "$found" -gt 0 ]]
}

latest_file() {
  local dir="$1"
  local pattern="$2"
  find "$dir" -maxdepth 1 -type f -name "$pattern" -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr \
    | head -n 1 \
    | cut -d' ' -f2-
}

cleanup_new_lldp_files_except() {
  local keep="$1"
  [[ -n "$keep" && -f "$keep" ]] || return 0
  while IFS= read -r src; do
    [[ -n "$src" ]] || continue
    [[ "$src" == "$keep" ]] && continue
    rm -f "$src"
    log "[CLEAN] Removed staged LLDP CSV $(basename "$src")"
  done < <(find "$MAPVIEWER_INPUT_DIR" -maxdepth 1 -type f -name "DataLLDP_Neighbor_*.csv" -newer "$RUN_MARKER" -printf '%p\n' 2>/dev/null)
}

jar_export_args=("--link-optical-export-mode=$JAR_EXPORT_MODE")
if [[ "$JAR_EXPORT_MODE" == "prescan" ]]; then
  jar_export_args+=("--link-optical-export-since-file=$RUN_MARKER")
fi
if [[ -n "$NEW_SITE_QUEUE_FILE" ]]; then
  if [[ "$JAR_EXPORT_MODE" != "prescan" ]]; then
    log "[ERROR] BOT_NEW_SITE_QUEUE_FILE requires BOT_LINKOPTICAL_JAR_EXPORT_MODE=prescan."
    exit 2
  fi
  jar_export_args+=("--link-optical-new-site-queue-file=$NEW_SITE_QUEUE_FILE")
fi

selection_args=("--link-optical-all")
queue_site_count=0
if [[ -n "$SITE_QUEUE_FILE" ]]; then
  if [[ ! -s "$SITE_QUEUE_FILE" ]]; then
    log "[ERROR] Selected-site queue is missing or empty: $SITE_QUEUE_FILE"
    exit 2
  fi
  declare -A queue_site_seen=()
  queue_sites=()
  while IFS= read -r queue_site || [[ -n "$queue_site" ]]; do
    queue_site="${queue_site%$'\r'}"
    [[ -n "$queue_site" ]] || continue
    if [[ "$queue_site" == *,* ]]; then
      log "[ERROR] Site name in queue contains an unsupported comma: $queue_site"
      exit 2
    fi
    if [[ -z "${queue_site_seen[$queue_site]+x}" ]]; then
      queue_site_seen[$queue_site]=1
      queue_sites+=("$queue_site")
    fi
  done < "$SITE_QUEUE_FILE"
  if [[ "${#queue_sites[@]}" -eq 0 ]]; then
    log "[ERROR] Selected-site queue contains no usable site names: $SITE_QUEUE_FILE"
    exit 2
  fi
  queue_site_count="${#queue_sites[@]}"
  queue_site_csv="$(IFS=,; echo "${queue_sites[*]}")"
  selection_args=("--link-optical-sites=$queue_site_csv")
fi

log "Starting TRUE Link Optical Auto All Site."
log "[EXPORT] JAR export mode=$JAR_EXPORT_MODE"
if [[ -n "$SITE_QUEUE_FILE" ]]; then
  log "[QUEUE] Selection mode=NEW-SITES count=$queue_site_count input=$SITE_QUEUE_FILE"
else
  log "[QUEUE] Selection mode=ALL"
fi
if [[ -n "$NEW_SITE_QUEUE_FILE" ]]; then
  log "[QUEUE] Next-generation output=$NEW_SITE_QUEUE_FILE"
fi
log "BOT_DIST_DIR=$BOT_DIST_DIR"
log "MAPVIEWER_INPUT_DIR=$MAPVIEWER_INPUT_DIR"
log "LLDP_DATA_QUALITY_FILE=$LLDP_DATA_QUALITY_FILE"
log "DTAC_LLDPMAP_MANUAL_DIR=$DTAC_LLDPMAP_MANUAL_DIR"
clean_total_log_before_run
sync_nodes_to_user_input "pre-run"

# A stale queue must never be mistaken for successful discovery. The JVM
# recreates this file atomically after a successful pre-scan, including an
# empty file when the recursive queue has reached closure.
if [[ -n "$NEW_SITE_QUEUE_FILE" ]]; then
  rm -f "$NEW_SITE_QUEUE_FILE"
fi

cd "$BOT_DIST_DIR"
"$JAVA_BIN" \
  -Djava.awt.headless=true \
  -Xms256m -Xmx2048m -XX:+UseG1GC \
  -jar "$JAR_PATH" \
  --auto-link-optical "${selection_args[@]}" --skip-clls-validation \
  --lldp-data-quality-file="$LLDP_DATA_QUALITY_FILE" \
  "${jar_export_args[@]}" \
  "$@" 2>&1 | tee -a "$RUN_LOG"

java_status="${PIPESTATUS[0]}"
if [[ "$java_status" -ne 0 ]]; then
  log "[ERROR] TRUE Link Optical Auto failed."
  exit "$java_status"
fi
if [[ -n "$NEW_SITE_QUEUE_FILE" && ! -f "$NEW_SITE_QUEUE_FILE" ]]; then
  log "[ERROR] Pre-scan did not produce the next-generation queue: $NEW_SITE_QUEUE_FILE"
  exit 1
fi

if is_truthy "${BOT_DEFER_MAPVIEWER_PUBLISH:-0}"; then
  log "[DEFER] Collection pass finished. All-completed export and MapViewer merge are deferred until checkpoint retry finishes."
  log "Finished TRUE Link Optical Auto All Site collection pass (publication deferred)."
  exit 0
fi

if [[ ! -x "$FINAL_PUBLISH_SCRIPT" ]]; then
  log "[ERROR] Final Link Optical publish script not found or not executable: $FINAL_PUBLISH_SCRIPT"
  exit 1
fi

log "TRUE Link Optical Auto finished. Publishing final all-completed snapshot to MapViewer."
set +e
BOT_DIST_DIR="$BOT_DIST_DIR" \
MAPVIEWER_INPUT_DIR="$MAPVIEWER_INPUT_DIR" \
MAPVIEWER_DIR="$MAPVIEWER_DIR" \
DTAC_LLDPMAP_MANUAL_DIR="$DTAC_LLDPMAP_MANUAL_DIR" \
LINKOPTICAL_MERGE_SCRIPT="$LINKOPTICAL_MERGE_SCRIPT" \
LINKOPTICAL_ALL_COMPLETED_EXPORT_SCRIPT="$LINKOPTICAL_ALL_COMPLETED_EXPORT_SCRIPT" \
GRD_USERINPUT_SYNC_SCRIPT="$GRD_USERINPUT_SYNC_SCRIPT" \
  "$FINAL_PUBLISH_SCRIPT" 2>&1 | tee -a "$RUN_LOG"
publish_status="${PIPESTATUS[0]}"
set -e
if [[ "$publish_status" -ne 0 ]]; then
  log "[ERROR] Final Link Optical publication failed with status=$publish_status."
  exit "$publish_status"
fi

log "Finished TRUE Link Optical Auto All Site workflow."
