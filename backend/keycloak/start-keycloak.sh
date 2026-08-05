#!/usr/bin/env bash
set -Eeuo pipefail

read_secret() {
  local variable_name="$1"
  local path="$2"
  [[ -r "${path}" ]] || { printf 'Secret Keycloak illisible: %s\n' "${path}" >&2; exit 1; }
  printf -v "${variable_name}" '%s' "$(tr -d '\r\n' < "${path}")"
  export "${variable_name}"
}

read_secret KC_DB_PASSWORD "${KC_DB_PASSWORD_FILE:-/run/secrets/keycloak-db-password}"
read_secret KC_BOOTSTRAP_ADMIN_PASSWORD \
  "${KC_BOOTSTRAP_ADMIN_PASSWORD_FILE:-/run/secrets/keycloak-bootstrap-admin-password}"

export KC_BOOTSTRAP_ADMIN_USERNAME="${KC_BOOTSTRAP_ADMIN_USERNAME:-iol-bootstrap-admin}"

exec /opt/keycloak/bin/kc.sh start --optimized --import-realm --server-async-bootstrap=false
