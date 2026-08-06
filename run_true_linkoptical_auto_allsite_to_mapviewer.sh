#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOT_DIST_DIR="${BOT_DIST_DIR:-$APP_DIR/dist}"
MAPVIEWER_INPUT_DIR="${MAPVIEWER_INPUT_DIR:-/home/transportsftp/LLDP_MapViewer/_input}"
MAPVIEWER_LOG_DIR="${MAPVIEWER_LOG_DIR:-$(cd "$(dirname "$MAPVIEWER_INPUT_DIR")" && pwd)/_logs}"
LLDP_DATA_QUALITY_FILE="${LLDP_DATA_QUALITY_FILE:-$MAPVIEWER_LOG_DIR/lldp_data_quality_latest.csv}"
MAPVIEWER_DIR="${MAPVIEWER_DIR:-$(cd "$(dirname "$MAPVIEWER_INPUT_DIR")" && pwd)}"
DTAC_LLDPMAP_MANUAL_DIR="${DTAC_LLDPMAP_MANUAL_DIR:-$MAPVIEWER_DIR/_manual_dtac_lldp}"
LLDP_MERGE_SCRIPT="${LLDP_MERGE_SCRIPT:-$MAPVIEWER_DIR/scripts/merge_lldp_neighbor_manual.sh}"
JAVA_BIN="${JAVA_BIN:-/home/transportsftp/java8/bin/java}"
JAR_PATH="${JAR_PATH:-$BOT_DIST_DIR/BotGetLog_TrueCorp.jar}"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "[ERROR] BotGetLog_TrueCorp.jar not found: $JAR_PATH" >&2
  exit 1
fi
if [[ ! -d "$MAPVIEWER_INPUT_DIR" ]]; then
  echo "[ERROR] MapViewer input folder not found: $MAPVIEWER_INPUT_DIR" >&2
  exit 1
fi

LOG_DIR="$BOT_DIST_DIR/_output/System_Log"
mkdir -p "$LOG_DIR"
RUN_LOG="$LOG_DIR/true-linkoptical-auto-$(date +%Y%m%d-%H%M%S).log"
RUN_LOCK_DIR="$LOG_DIR/.true-linkoptical-auto.lock"
RUN_MARKER=""

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$RUN_LOG"
}

clean_total_log_before_run() {
  local total_log_dir="$BOT_DIST_DIR/_output/Total_Log"
  if [[ "${BOT_KEEP_TOTAL_LOG_HISTORY:-0}" == "1" ]]; then
    log "[INFO] Keeping existing Total_Log files because BOT_KEEP_TOTAL_LOG_HISTORY=1."
    return 0
  fi
  if [[ "${BOT_CLEAN_TOTAL_LOG_BEFORE_RUN:-0}" != "1" ]]; then
    log "[INFO] Total_Log full cleanup disabled for this run. Existing complete logs can be used as checkpoint."
    return 0
  fi

  mkdir -p "$total_log_dir"
  local count
  count="$(find "$total_log_dir" -maxdepth 1 -type f -name '*.txt' 2>/dev/null | wc -l | tr -d ' ')"
  if [[ "$count" == "0" ]]; then
    log "[CLEAN] Total_Log already empty."
    return 0
  fi

  find "$total_log_dir" -maxdepth 1 -type f -name '*.txt' -delete
  log "[CLEAN] Removed $count existing Total_Log file(s) before all-site run."
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

log "Starting TRUE Link Optical Auto All Site."
log "BOT_DIST_DIR=$BOT_DIST_DIR"
log "MAPVIEWER_INPUT_DIR=$MAPVIEWER_INPUT_DIR"
log "LLDP_DATA_QUALITY_FILE=$LLDP_DATA_QUALITY_FILE"
log "DTAC_LLDPMAP_MANUAL_DIR=$DTAC_LLDPMAP_MANUAL_DIR"
clean_total_log_before_run

cd "$BOT_DIST_DIR"
"$JAVA_BIN" \
  -Djava.awt.headless=true \
  -Xms256m -Xmx2048m -XX:+UseG1GC \
  -jar "$JAR_PATH" \
  --auto-link-optical --link-optical-all --skip-clls-validation \
  --lldp-data-quality-file="$LLDP_DATA_QUALITY_FILE" 2>&1 | tee -a "$RUN_LOG"

if [[ "${PIPESTATUS[0]}" -ne 0 ]]; then
  log "[ERROR] TRUE Link Optical Auto failed."
  exit 1
fi

log "TRUE Link Optical Auto finished. Copying CSV files to MapViewer input."
copy_latest "DataLLDP_Neighbor_*.csv" 1 || {
  log "[ERROR] No DataLLDP_Neighbor CSV files found."
  exit 1
}
if [[ -x "$LLDP_MERGE_SCRIPT" ]]; then
  log "Merging manual DTAC LLDP snapshot if available."
  before_dtac_lldp="$(latest_file "$MAPVIEWER_INPUT_DIR" "DataLLDP_Neighbor_*.csv")"
  MAPVIEWER_INPUT_DIR="$MAPVIEWER_INPUT_DIR" \
    DTAC_LLDPMAP_MANUAL_DIR="$DTAC_LLDPMAP_MANUAL_DIR" \
    "$LLDP_MERGE_SCRIPT" 2>&1 | tee -a "$RUN_LOG"
  after_dtac_lldp="$(latest_file "$MAPVIEWER_INPUT_DIR" "DataLLDP_Neighbor_*.csv")"
  if [[ -n "$after_dtac_lldp" && "$after_dtac_lldp" != "$before_dtac_lldp" ]]; then
    cleanup_new_lldp_files_except "$after_dtac_lldp"
  fi
else
  log "[WARN] LLDP merge script not found or not executable: $LLDP_MERGE_SCRIPT"
fi
copy_latest "DataPort_*.csv" 1 || log "[WARN] No DataPort CSV found."
copy_latest "DataDescription_MB_*.csv" 1 || log "[WARN] No DataDescription_MB CSV found."

log "Finished TRUE Link Optical Auto All Site workflow."
