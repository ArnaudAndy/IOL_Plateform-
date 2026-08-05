#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s <fichier-snapshot-sortie>\n' "$0" >&2
  exit 2
fi

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
VAULT_COMPOSE_FILE="${VAULT_COMPOSE_FILE:-${BACKEND_DIR}/vault/docker-compose.vault-ha.yml}"
ROLE_ID_FILE="${VAULT_BACKUP_ROLE_ID_FILE:-${BACKEND_DIR}/secrets/vault-generated/vault-backup-role-id}"
SECRET_ID_FILE="${VAULT_BACKUP_SECRET_ID_FILE:-${BACKEND_DIR}/secrets/vault-generated/vault-backup-secret-id}"
OUTPUT_FILE="$1"
OUTPUT_DIR="$(cd -- "$(dirname -- "${OUTPUT_FILE}")" && pwd)"
OUTPUT_NAME="$(basename -- "${OUTPUT_FILE}")"
CONTAINER_SNAPSHOT=/tmp/iol-vault-backup.snap

docker_host_path() {
  case "$(uname -s)" in
    MINGW*|MSYS*) cygpath -w "$1" ;;
    *) printf '%s\n' "$1" ;;
  esac
}

case "$(uname -s)" in
  MINGW*|MSYS*) export MSYS_NO_PATHCONV=1 ;;
esac

for required in "${VAULT_COMPOSE_FILE}" "${ROLE_ID_FILE}" "${SECRET_ID_FILE}"; do
  if [[ ! -s "${required}" ]]; then
    printf 'Fichier Vault requis absent ou vide: %s\n' "${required}" >&2
    exit 1
  fi
done

vault_compose() {
  docker compose -f "$(docker_host_path "${VAULT_COMPOSE_FILE}")" "$@"
}

cleanup() {
  vault_compose exec -T vault-1 rm -f "${CONTAINER_SNAPSHOT}" >/dev/null 2>&1 || true
}
trap cleanup EXIT

ROLE_ID="$(tr -d '\r\n' < "${ROLE_ID_FILE}")"
SECRET_ID="$(tr -d '\r\n' < "${SECRET_ID_FILE}")"
VAULT_TOKEN="$(vault_compose exec -T \
  -e IOL_BACKUP_ROLE_ID="${ROLE_ID}" \
  -e IOL_BACKUP_SECRET_ID="${SECRET_ID}" \
  vault-1 sh -ec '
    export VAULT_ADDR=https://vault-1:8200
    export VAULT_CACERT=/vault/tls/ca.pem
    export VAULT_CLIENT_CERT=/vault/tls/vault.crt
    export VAULT_CLIENT_KEY=/vault/tls/vault.key
    vault write -field=token auth/approle/login \
      role_id="$IOL_BACKUP_ROLE_ID" secret_id="$IOL_BACKUP_SECRET_ID"
  ' | tr -d '\r\n')"

if [[ -z "${VAULT_TOKEN}" ]]; then
  printf 'Vault n a pas delivre de token de sauvegarde.\n' >&2
  exit 1
fi

vault_compose exec -T -e VAULT_TOKEN="${VAULT_TOKEN}" vault-1 sh -ec '
  export VAULT_ADDR=https://vault-1:8200
  export VAULT_CACERT=/vault/tls/ca.pem
  export VAULT_CLIENT_CERT=/vault/tls/vault.crt
  export VAULT_CLIENT_KEY=/vault/tls/vault.key
  export VAULT_CLIENT_TIMEOUT=10m
  vault operator raft snapshot save /tmp/iol-vault-backup.snap
  vault operator raft list-peers
' > "${OUTPUT_FILE}.peers.txt"

vault_compose cp "vault-1:${CONTAINER_SNAPSHOT}" "$(docker_host_path "${OUTPUT_FILE}")" >/dev/null
test -s "${OUTPUT_FILE}"

docker run --rm \
  --volume "$(docker_host_path "${OUTPUT_DIR}"):/backup:ro" \
  --entrypoint /bin/sh \
  hashicorp/vault:1.21.7 \
  -ec "vault operator raft snapshot inspect '/backup/${OUTPUT_NAME}'" \
  > "${OUTPUT_FILE}.inspect.txt"
test -s "${OUTPUT_FILE}.inspect.txt"
printf 'Snapshot Vault Raft sauvegarde et inspecte: %s\n' "${OUTPUT_FILE}"
