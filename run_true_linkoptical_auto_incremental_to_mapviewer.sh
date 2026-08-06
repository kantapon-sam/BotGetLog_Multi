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
INCREMENTAL_MERGE_SCRIPT="${INCREMENTAL_MERGE_SCRIPT:-$APP_DIR/scripts/merge_linkoptical_incremental.py}"
JAVA_BIN="${JAVA_BIN:-/home/transportsftp/java8/bin/java}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
JAR_PATH="${JAR_PATH:-$BOT_DIST_DIR/BotGetLog_TrueCorp.jar}"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "[ERROR] BotGetLog_TrueCorp.jar not found: $JAR_PATH" >&2
  exit 1
fi
if [[ ! -d "$MAPVIEWER_INPUT_DIR" ]]; then
  echo "[ERROR] MapViewer input folder not found: $MAPVIEWER_INPUT_DIR" >&2
  exit 1
fi
if [[ ! -f "$INCREMENTAL_MERGE_SCRIPT" ]]; then
  echo "[ERROR] Incremental merge script not found: $INCREMENTAL_MERGE_SCRIPT" >&2
  exit 1
fi

LOG_DIR="$BOT_DIST_DIR/_output/System_Log"
mkdir -p "$LOG_DIR"
RUN_LOG="$LOG_DIR/true-linkoptical-incremental-$(date +%Y%m%d-%H%M%S).log"
RUN_LOCK_DIR="$LOG_DIR/.true-linkoptical-auto.lock"
RUN_MARKER=""

log() {
  echo "$(date '+%Y-%m-%d %H:%M:%S') $*" | tee -a "$RUN_LOG"
}

find_running_auto_pid() {
  pgrep -f "^$JAVA_BIN .*BotGetLog_TrueCorp.jar.*--auto-link-optical" | head -n 1 || true
}

latest_file() {
  local dir="$1"
  local pattern="$2"
  find "$dir" -maxdepth 1 -type f -name "$pattern" -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr \
    | head -n 1 \
    | cut -d' ' -f2-
}

latest_newer_file() {
  local dir="$1"
  local pattern="$2"
  local marker="$3"
  find "$dir" -maxdepth 1 -type f -name "$pattern" -newer "$marker" -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr \
    | head -n 1 \
    | cut -d' ' -f2-
}

latest_newer_full_lldp_file() {
  local dir="$1"
  local marker="$2"
  local fallback=""
  while IFS= read -r src; do
    [[ -n "$src" ]] || continue
    if [[ -z "$fallback" ]]; then
      fallback="$src"
    fi
    if ! head -n 1 "$src" | grep -q 'NeighborDes'; then
      echo "$src"
      return 0
    fi
  done < <(find "$dir" -maxdepth 1 -type f -name "DataLLDP_Neighbor_*.csv" -newer "$marker" -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr \
    | cut -d' ' -f2-)
  if [[ -n "$fallback" ]]; then
    echo "$fallback"
  fi
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

running_pid="$(find_running_auto_pid)"
if [[ -n "$running_pid" ]]; then
  log "[SKIP] TRUE Link Optical Auto is already running (pid=$running_pid). Skip this scheduled incremental run."
  exit 0
fi

if ! mkdir "$RUN_LOCK_DIR" 2>/dev/null; then
  lock_pid=""
  if [[ -f "$RUN_LOCK_DIR/pid" ]]; then
    lock_pid="$(cat "$RUN_LOCK_DIR/pid" 2>/dev/null || true)"
  fi
  if [[ -n "$lock_pid" ]] && kill -0 "$lock_pid" 2>/dev/null; then
    log "[SKIP] TRUE Link Optical Auto lock is active (pid=$lock_pid). Skip this scheduled incremental run."
    exit 0
  fi
  log "[WARN] Removing stale TRUE Link Optical Auto lock: $RUN_LOCK_DIR"
  rm -rf "$RUN_LOCK_DIR"
  mkdir "$RUN_LOCK_DIR"
fi
echo "$$" > "$RUN_LOCK_DIR/pid"

RUN_MARKER="$LOG_DIR/.true-linkoptical-incremental-marker-$$"
touch "$RUN_MARKER"

cleanup() {
  rm -f "$RUN_MARKER"
  if [[ -f "$RUN_LOCK_DIR/pid" ]] && [[ "$(cat "$RUN_LOCK_DIR/pid" 2>/dev/null || true)" == "$$" ]]; then
    rm -rf "$RUN_LOCK_DIR"
  fi
}
trap cleanup EXIT

BASELINE_LLDP_FILE="$(latest_file "$MAPVIEWER_INPUT_DIR" "DataLLDP_Neighbor_*.csv")"
BASELINE_PORT_FILE="$(latest_file "$MAPVIEWER_INPUT_DIR" "DataPort_*.csv")"
BASELINE_DESCRIPTION_FILE="$(latest_file "$MAPVIEWER_INPUT_DIR" "DataDescription_MB_*.csv")"

if [[ -z "$BASELINE_LLDP_FILE" ]]; then
  log "[ERROR] No baseline DataLLDP_Neighbor CSV found in $MAPVIEWER_INPUT_DIR. Run all-site first."
  exit 1
fi

log "Starting TRUE Link Optical Incremental Down workflow."
log "BOT_DIST_DIR=$BOT_DIST_DIR"
log "MAPVIEWER_INPUT_DIR=$MAPVIEWER_INPUT_DIR"
log "BASELINE_LLDP_FILE=$BASELINE_LLDP_FILE"
log "LLDP_DATA_QUALITY_FILE=$LLDP_DATA_QUALITY_FILE"
log "DTAC_LLDPMAP_MANUAL_DIR=$DTAC_LLDPMAP_MANUAL_DIR"

cd "$BOT_DIST_DIR"
"$JAVA_BIN" \
  -Djava.awt.headless=true \
  -Xms256m -Xmx2048m -XX:+UseG1GC \
  -jar "$JAR_PATH" \
  --auto-link-optical --link-optical-incremental \
  --link-optical-neighbor-file="$BASELINE_LLDP_FILE" \
  --skip-clls-validation \
  --lldp-data-quality-file="$LLDP_DATA_QUALITY_FILE" 2>&1 | tee -a "$RUN_LOG"

if [[ "${PIPESTATUS[0]}" -ne 0 ]]; then
  log "[ERROR] TRUE Link Optical Incremental failed."
  exit 1
fi

NEW_LLDP_FILE="$(latest_newer_full_lldp_file "$BOT_DIST_DIR/_output/LLDP_Neighbor" "$RUN_MARKER")"
NEW_PORT_FILE="$(latest_newer_file "$BOT_DIST_DIR/_output/LLDP_Neighbor" "DataPort_*.csv" "$RUN_MARKER")"
NEW_DESCRIPTION_FILE="$(latest_newer_file "$BOT_DIST_DIR/_output/LLDP_Neighbor" "DataDescription_MB_*.csv" "$RUN_MARKER")"

if [[ -z "$NEW_LLDP_FILE" ]]; then
  log "[INFO] No new incremental DataLLDP_Neighbor CSV generated. Keep existing MapViewer input."
  exit 0
fi

log "Merging incremental CSV output into MapViewer baseline."
"$PYTHON_BIN" "$INCREMENTAL_MERGE_SCRIPT" \
  --output-dir "$MAPVIEWER_INPUT_DIR" \
  --baseline-lldp "$BASELINE_LLDP_FILE" \
  --incremental-lldp "$NEW_LLDP_FILE" \
  --baseline-port "${BASELINE_PORT_FILE:-}" \
  --incremental-port "${NEW_PORT_FILE:-}" \
  --baseline-description "${BASELINE_DESCRIPTION_FILE:-}" \
  --incremental-description "${NEW_DESCRIPTION_FILE:-}" 2>&1 | tee -a "$RUN_LOG"

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

log "Finished TRUE Link Optical Incremental Down workflow."
