#!/bin/sh
set -eu

read_secret() {
  variable_name="$1"
  file_path="${2:-}"
  if [ -n "$file_path" ]; then
    [ -r "$file_path" ] || { printf 'Secret mediateur illisible: %s\n' "$file_path" >&2; exit 1; }
    value="$(tr -d '\r\n' < "$file_path")"
    export "$variable_name=$value"
  fi
}

read_secret OPENHIM_PASSWORD "${OPENHIM_PASSWORD_FILE:-}"
read_secret IOL_INBOUND_CLIENT_PASSWORD "${IOL_INBOUND_CLIENT_PASSWORD_FILE:-}"

exec node /app/src/index.js
