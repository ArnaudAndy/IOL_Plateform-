#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
TEMPLATE="${SCRIPT_DIR}/config/vault.hcl.tpl"
OUTPUT_DIR="${SCRIPT_DIR}/generated"
SEAL_CONFIG_FILE="${VAULT_SEAL_CONFIG_FILE:-}"

if [[ -z "${SEAL_CONFIG_FILE}" || ! -s "${SEAL_CONFIG_FILE}" ]]; then
  printf 'VAULT_SEAL_CONFIG_FILE doit pointer vers une configuration KMS/HSM non vide.\n' >&2
  exit 1
fi
if grep -Eq '(^|[[:space:]])(access_key|secret_key|client_secret|token)[[:space:]]*=' \
    "${SEAL_CONFIG_FILE}"; then
  printf 'Le fichier seal ne doit pas contenir de credentials statiques. Utilisez une identite de workload.\n' >&2
  exit 1
fi

mkdir -p "${OUTPUT_DIR}"
chmod 700 "${OUTPUT_DIR}"
for node in vault-1 vault-2 vault-3; do
  awk -v node="${node}" -v seal_file="${SEAL_CONFIG_FILE}" '
    /__NODE_NAME__/ { gsub(/__NODE_NAME__/, node) }
    /__SEAL_CONFIG__/ {
      while ((getline line < seal_file) > 0) print line
      close(seal_file)
      next
    }
    { print }
  ' "${TEMPLATE}" > "${OUTPUT_DIR}/${node}.hcl"
  chmod 600 "${OUTPUT_DIR}/${node}.hcl"
done
printf 'Configurations Vault HA generees dans %s\n' "${OUTPUT_DIR}"
