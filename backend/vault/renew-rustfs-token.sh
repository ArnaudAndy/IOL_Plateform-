#!/bin/sh
set -eu

: "${VAULT_ADDR:?VAULT_ADDR is required}"
: "${VAULT_CACERT:?VAULT_CACERT is required}"
: "${VAULT_CLIENT_CERT:?VAULT_CLIENT_CERT is required}"
: "${VAULT_CLIENT_KEY:?VAULT_CLIENT_KEY is required}"
: "${VAULT_TOKEN_FILE:?VAULT_TOKEN_FILE is required}"

export VAULT_TOKEN
VAULT_TOKEN="$(tr -d '\r\n' < "${VAULT_TOKEN_FILE}")"
if [ -z "${VAULT_TOKEN}" ]; then
  printf 'Le jeton periodique RustFS est vide.\n' >&2
  exit 1
fi

renew_interval="${VAULT_RENEW_INTERVAL_SECONDS:-28800}"
case "${renew_interval}" in
  ''|*[!0-9]*) printf 'VAULT_RENEW_INTERVAL_SECONDS doit etre numerique.\n' >&2; exit 1 ;;
esac

while true; do
  vault token lookup -format=json >/dev/null
  vault token renew -increment=24h -format=json >/dev/null
  date -u +%Y-%m-%dT%H:%M:%SZ > /tmp/iol-vault-token-renewed
  sleep "${renew_interval}"
done
