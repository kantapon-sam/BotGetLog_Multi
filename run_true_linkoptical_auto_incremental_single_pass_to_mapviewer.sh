#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOT_DIST_DIR="${BOT_DIST_DIR:-$APP_DIR/dist}"
MAPVIEWER_INPUT_DIR="${MAPVIEWER_INPUT_DIR:-/home/transportsftp/LLDP_MapViewer/_input}"
MAPVIEWER_LOG_DIR="${MAPVIEWER_LOG_DIR:-$(cd "$(dirname "$MAPVIEWER_INPUT_DIR")" && pwd)/_logs}"
LLDP_DATA_QUALITY_FILE="${LLDP_DATA_QUALITY_FILE:-$MAPVIEWER_LOG_DIR/lldp_data_quality_latest.csv}"
MAPVIEWER_DIR="${MAPVIEWER_DIR:-$(cd "$(dirname "$MAPVIEWER_INPUT_DIR")" && pwd)}"
DTAC_LLDPMAP_MANUAL_DIR="${DTAC_LLDPMAP_MANUAL_DIR:-$MAPVIEWER_DIR/_manual_dtac_lldp}"
LINKOPTICAL_MERGE_SCRIPT="${LINKOPTICAL_MERGE_SCRIPT:-$MAPVIEWER_DIR/scripts/merge_linkoptical_manual_dtac.sh}"
INCREMENTAL_MERGE_SCRIPT="${INCREMENTAL_MERGE_SCRIPT:-$APP_DIR/scripts/merge_linkoptical_incremental.py}"
GRD_USERINPUT_SYNC_SCRIPT="${GRD_USERINPUT_SYNC_SCRIPT:-$MAPVIEWER_DIR/scripts/sync_grd_nodes_to_user_input.sh}"
JAVA_BIN="${JAVA_BIN:-/home/transportsftp/java8/bin/java}"
PYTHON_BIN="${PYTHON_BIN:-python3}"
JAR_PATH="${JAR_PATH:-$BOT_DIST_DIR/BotGetLog_TrueCorp.jar}"
JAR_EXPORT_MODE="${BOT_LINKOPTICAL_JAR_EXPORT_MODE:-full}"
NEW_SITE_QUEUE_FILE="${BOT_NEW_SITE_QUEUE_FILE:-}"

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
case "$JAR_EXPORT_MODE" in
  full|prescan|skip) ;;
  *)
    echo "[ERROR] BOT_LINKOPTICAL_JAR_EXPORT_MODE must be full, prescan, or skip: $JAR_EXPORT_MODE" >&2
    exit 2
    ;;
esac

LOG_DIR="$BOT_DIST_DIR/_output/System_Log"
mkdir -p "$LOG_DIR"
RUN_LOG="$LOG_DIR/true-linkoptical-incremental-$(date +%Y%m%d-%H%M%S).log"
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

log "Starting TRUE Link Optical Incremental Down workflow."
log "[EXPORT] JAR export mode=$JAR_EXPORT_MODE"
if [[ -n "$NEW_SITE_QUEUE_FILE" ]]; then
  log "[QUEUE] Next-generation output=$NEW_SITE_QUEUE_FILE"
fi
log "BOT_DIST_DIR=$BOT_DIST_DIR"
log "MAPVIEWER_INPUT_DIR=$MAPVIEWER_INPUT_DIR"
log "BASELINE_LLDP_FILE=$BASELINE_LLDP_FILE"
log "LLDP_DATA_QUALITY_FILE=$LLDP_DATA_QUALITY_FILE"
log "DTAC_LLDPMAP_MANUAL_DIR=$DTAC_LLDPMAP_MANUAL_DIR"
sync_nodes_to_user_input "pre-run"

if [[ -n "$NEW_SITE_QUEUE_FILE" ]]; then
  rm -f "$NEW_SITE_QUEUE_FILE"
fi

cd "$BOT_DIST_DIR"
"$JAVA_BIN" \
  -Djava.awt.headless=true \
  -Xms256m -Xmx2048m -XX:+UseG1GC \
  -jar "$JAR_PATH" \
  --auto-link-optical --link-optical-incremental \
  --link-optical-neighbor-file="$BASELINE_LLDP_FILE" \
  --skip-clls-validation \
  --lldp-data-quality-file="$LLDP_DATA_QUALITY_FILE" \
  "${jar_export_args[@]}" 2>&1 | tee -a "$RUN_LOG"

java_status="${PIPESTATUS[0]}"
if [[ "$java_status" -ne 0 ]]; then
  log "[ERROR] TRUE Link Optical Incremental failed."
  exit "$java_status"
fi
if [[ -n "$NEW_SITE_QUEUE_FILE" && ! -f "$NEW_SITE_QUEUE_FILE" ]]; then
  log "[ERROR] Pre-scan did not produce the next-generation queue: $NEW_SITE_QUEUE_FILE"
  exit 1
fi

if is_truthy "${BOT_DEFER_MAPVIEWER_PUBLISH:-0}"; then
  log "[DEFER] Incremental collection pass finished. MapViewer merge is deferred until checkpoint retry finishes."
  log "Finished TRUE Link Optical Incremental Down collection pass (publication deferred)."
  exit 0
fi

NEW_LLDP_FILE="$(latest_newer_full_lldp_file "$BOT_DIST_DIR/_output/LLDP_Neighbor" "$RUN_MARKER")"
NEW_PORT_FILE="$(latest_newer_file "$BOT_DIST_DIR/_output/LLDP_Neighbor" "DataPort_*.csv" "$RUN_MARKER")"
NEW_DESCRIPTION_FILE="$(latest_newer_file "$BOT_DIST_DIR/_output/LLDP_Neighbor" "DataDescription_MB_*.csv" "$RUN_MARKER")"

if [[ -z "$NEW_LLDP_FILE" ]]; then
  log "[INFO] No new incremental DataLLDP_Neighbor CSV generated. Running final TRUE/DTAC merge from existing MapViewer input."
else
  log "Merging incremental CSV output into MapViewer baseline."
  "$PYTHON_BIN" "$INCREMENTAL_MERGE_SCRIPT" \
    --output-dir "$MAPVIEWER_INPUT_DIR" \
    --baseline-lldp "$BASELINE_LLDP_FILE" \
    --incremental-lldp "$NEW_LLDP_FILE" \
    --baseline-port "${BASELINE_PORT_FILE:-}" \
    --incremental-port "${NEW_PORT_FILE:-}" \
    --baseline-description "${BASELINE_DESCRIPTION_FILE:-}" \
    --incremental-description "${NEW_DESCRIPTION_FILE:-}" 2>&1 | tee -a "$RUN_LOG"
fi

if [[ -x "$LINKOPTICAL_MERGE_SCRIPT" ]]; then
  log "Merging TRUE/DTAC Link Optical snapshots for LLDP, DataPort, and Description MB."
  before_dtac_lldp="$(latest_file "$MAPVIEWER_INPUT_DIR" "DataLLDP_Neighbor_*.csv")"
  MAPVIEWER_INPUT_DIR="$MAPVIEWER_INPUT_DIR" \
    DTAC_LLDPMAP_MANUAL_DIR="$DTAC_LLDPMAP_MANUAL_DIR" \
    TRUE_LLDPMAP_OUTPUT_DIR="$MAPVIEWER_INPUT_DIR" \
    "$LINKOPTICAL_MERGE_SCRIPT" 2>&1 | tee -a "$RUN_LOG"
  after_dtac_lldp="$(latest_file "$MAPVIEWER_INPUT_DIR" "DataLLDP_Neighbor_*.csv")"
  if [[ -n "$after_dtac_lldp" && "$after_dtac_lldp" != "$before_dtac_lldp" ]]; then
    cleanup_new_lldp_files_except "$after_dtac_lldp"
  fi
else
  log "[WARN] Link Optical merge script not found or not executable: $LINKOPTICAL_MERGE_SCRIPT"
fi

sync_nodes_to_user_input "post-run-for-next-cycle"

log "Finished TRUE Link Optical Incremental Down workflow."
