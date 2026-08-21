#!/usr/bin/env bash
set -Eeuo pipefail

: "${VAULT_ADDR:?VAULT_ADDR is required}"
: "${VAULT_CACERT:?VAULT_CACERT is required}"
: "${VAULT_TOKEN_FILE:?VAULT_TOKEN_FILE is required for the one-time bootstrap}"

export VAULT_TOKEN
VAULT_TOKEN="$(tr -d '\r\n' < "${VAULT_TOKEN_FILE}")"
TRANSIT_MOUNT="${VAULT_TRANSIT_MOUNT:-transit}"
KEY_NAME="${VAULT_TRANSIT_KEY:-iol-business-credentials}"
RUSTFS_KEY_NAME="${RUSTFS_KMS_KEY_ID:-iol-rustfs}"
OUTPUT_DIR="${VAULT_BOOTSTRAP_OUTPUT_DIR:-./secrets}"

if ! vault secrets list -format=json | grep -Fq "\"${TRANSIT_MOUNT}/\""; then
  vault secrets enable -path="${TRANSIT_MOUNT}" transit
fi

if ! vault read "${TRANSIT_MOUNT}/keys/${RUSTFS_KEY_NAME}" >/dev/null 2>&1; then
  vault write "${TRANSIT_MOUNT}/keys/${RUSTFS_KEY_NAME}" \
    type=aes256-gcm96 derived=false exportable=false allow_plaintext_backup=false
fi

if ! vault read "${TRANSIT_MOUNT}/keys/${KEY_NAME}" >/dev/null 2>&1; then
  vault write "${TRANSIT_MOUNT}/keys/${KEY_NAME}" \
    type=aes256-gcm96 derived=true exportable=false allow_plaintext_backup=false
fi

vault policy write iol-api-core /vault/policies/iol-api-core.hcl
if ! vault auth list -format=json | grep -Fq '"approle/"'; then
  vault auth enable approle
fi
vault write auth/approle/role/iol-api-core \
  token_policies=iol-api-core \
  token_ttl=15m token_max_ttl=1h \
  secret_id_ttl=0 secret_id_num_uses=0

mkdir -p "${OUTPUT_DIR}"
chmod 700 "${OUTPUT_DIR}"
vault read -field=role_id auth/approle/role/iol-api-core/role-id > "${OUTPUT_DIR}/vault-api-role-id"
vault write -f -field=secret_id auth/approle/role/iol-api-core/secret-id > "${OUTPUT_DIR}/vault-api-secret-id"

# Identite distincte pour le source-gateway. Sa politique ne permet QUE le
# dechiffrement: il ouvre des sources, il n'en enregistre jamais. Une identite
# partagee avec api-core annulerait tout le benefice du confinement.
vault policy write iol-source-gateway /vault/policies/iol-source-gateway.hcl
vault write auth/approle/role/iol-source-gateway \
  token_policies=iol-source-gateway \
  token_ttl=15m token_max_ttl=1h \
  secret_id_ttl=0 secret_id_num_uses=0
vault read -field=role_id auth/approle/role/iol-source-gateway/role-id \
  > "${OUTPUT_DIR}/vault-source-gateway-role-id"
vault write -f -field=secret_id auth/approle/role/iol-source-gateway/secret-id \
  > "${OUTPUT_DIR}/vault-source-gateway-secret-id"

vault policy write iol-backup /vault/policies/iol-backup.hcl
vault write auth/approle/role/iol-backup \
  token_policies=iol-backup \
  token_ttl=15m token_max_ttl=30m \
  secret_id_ttl=720h secret_id_num_uses=40
vault read -field=role_id auth/approle/role/iol-backup/role-id \
  > "${OUTPUT_DIR}/vault-backup-role-id"
vault write -f -field=secret_id auth/approle/role/iol-backup/secret-id \
  > "${OUTPUT_DIR}/vault-backup-secret-id"

vault policy write iol-rustfs /vault/policies/iol-rustfs.hcl

# L'audit doit etre disponible avant de creer le jeton periodique RustFS.
# Ainsi, un echec de montage/permissions reste rejouable sans creer un second
# jeton periodique actif mais non reference par le fichier de sortie.
if ! vault audit list -format=json | grep -Fq '"file/"'; then
  vault audit enable file file_path=/vault/audit/audit.log mode=0600
fi

vault token create -orphan -period=24h -policy=iol-rustfs \
  -display-name=iol-rustfs-kms -field=token \
  > "${OUTPUT_DIR}/rustfs-vault-token"
chmod 444 "${OUTPUT_DIR}/vault-api-role-id" "${OUTPUT_DIR}/vault-api-secret-id" \
  "${OUTPUT_DIR}/rustfs-vault-token"

chmod 444 "${OUTPUT_DIR}/vault-backup-role-id" "${OUTPUT_DIR}/vault-backup-secret-id"

vault token revoke -self >/dev/null
printf 'Vault Transit initialized and bootstrap token revoked. Delete its local token file now.\n'
