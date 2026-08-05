#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${IOL_PRODUCTION_ENV_FILE:-${BACKEND_DIR}/.env.production}"

if [[ ! -s "${ENV_FILE}" ]]; then
  printf 'Fichier de production absent: %s\n' "${ENV_FILE}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

network_name="${IOL_VAULT_DOCKER_NETWORK:-iol-vault-client}"
if docker network inspect "${network_name}" >/dev/null 2>&1; then
  internal="$(docker network inspect --format '{{.Internal}}' "${network_name}")"
  if [[ "${internal}" != "true" ]]; then
    printf 'Le reseau existant %s n est pas interne. Refus.\n' "${network_name}" >&2
    exit 1
  fi
  printf 'Reseau Vault interne deja present: %s\n' "${network_name}"
  exit 0
fi

docker network create \
  --driver bridge \
  --internal \
  --label iol.security.scope=vault \
  "${network_name}" >/dev/null
printf 'Reseau Vault interne cree: %s\n' "${network_name}"
