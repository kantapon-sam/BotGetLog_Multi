#!/usr/bin/env bash
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BOT_DIST_DIR="${BOT_DIST_DIR:-$APP_DIR/dist}"
JAVA_BIN="${JAVA_BIN:-/home/transportsftp/java8/bin/java}"
JAR_PATH="${JAR_PATH:-$BOT_DIST_DIR/BotGetLog_TrueCorp.jar}"

if [[ ! -f "$JAR_PATH" ]]; then
  echo "[ERROR] BotGetLog_TrueCorp.jar not found: $JAR_PATH" >&2
  exit 1
fi

username="${TRUE_CLLS_USERNAME:-}"
password="${TRUE_CLLS_PASSWORD:-}"

if [[ -z "$username" ]]; then
  read -r -p "TRUE CLLS username: " username
fi
if [[ -z "$password" ]]; then
  read -r -s -p "TRUE CLLS password: " password
  echo
fi

if [[ -z "$username" || -z "$password" ]]; then
  echo "[ERROR] username/password are required." >&2
  exit 2
fi

cd "$BOT_DIST_DIR"
printf '%s\n%s\n' "$username" "$password" \
  | "$JAVA_BIN" -Djava.awt.headless=true -cp "$JAR_PATH" \
      com.java.botgetlog.truecorp.TrueCllsCredentialCli --stdin
