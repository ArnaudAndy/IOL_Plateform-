#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
ROOT_DIR="$(cd -- "${BACKEND_DIR}/.." && pwd)"
ENV_FILE="${IOL_ENV_FILE:-${IOL_PRODUCTION_ENV_FILE:-${BACKEND_DIR}/.env.production}}"
PYTHON_BIN="${PYTHON_BIN:-python3}"

fail() {
  printf 'PREFLIGHT REFUSE: %s\n' "$1" >&2
  exit 1
}

[[ -s "${ENV_FILE}" ]] || fail "fichier absent: ${ENV_FILE}"
set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

case "${IOL_DEPLOYMENT_ENV:-}" in
  preproduction|production) ;;
  *) fail 'IOL_DEPLOYMENT_ENV doit valoir preproduction ou production' ;;
esac
[[ "${IOL_RELEASE_TAG:-}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{2,127}$ ]] \
  || fail 'IOL_RELEASE_TAG doit etre un tag immuable explicite'
[[ "${IOL_RELEASE_TAG}" != *latest* && "${IOL_RELEASE_TAG}" != *REPLACE* ]] \
  || fail 'tag mutable ou factice interdit'
[[ "${IOL_PUBLIC_URL:-}" == https://* ]] || fail 'IOL_PUBLIC_URL doit utiliser HTTPS'
[[ "${IOL_PUBLIC_URL}" == "https://${IOL_PUBLIC_HOSTNAME:-}" ]] \
  || fail 'IOL_PUBLIC_URL doit correspondre exactement a https://IOL_PUBLIC_HOSTNAME'
for network_variable in IOL_DOCKER_NETWORK IOL_EGRESS_DOCKER_NETWORK; do
  network_value="${!network_variable:-}"
  [[ "${network_value}" =~ ^[A-Za-z0-9][A-Za-z0-9_.-]{1,127}$ ]] \
    || fail "${network_variable} est obligatoire et doit etre un nom Docker valide"
done
[[ "${VAULT_ADDR:-}" == https://* ]] || fail 'VAULT_ADDR doit utiliser HTTPS'
[[ "${RUSTFS_VAULT_ADDR:-}" == https://* ]] || fail 'RUSTFS_VAULT_ADDR doit utiliser HTTPS'
[[ -n "${IOL_INITIAL_ADMIN_USERNAME:-}" && -n "${IOL_INITIAL_ADMIN_EMAIL:-}" ]] \
  || fail 'un administrateur IOL initial est obligatoire'

if grep -Ev '^[[:space:]]*[A-Z0-9_]+_FILE=' "${ENV_FILE}" \
  | grep -Eiq '^[A-Z0-9_]*(PASSWORD|SECRET|TOKEN|API_KEY)[A-Z0-9_]*=.+$'; then
  fail 'un secret semble etre stocke en clair dans le fichier d environnement; utilisez une reference *_FILE'
fi

required_secrets=(
  postgres-password mongodb-root-password mongodb-app-password mongodb-openhim-password
  mongodb-gateway-password mongodb-pipeline-password
  mongodb-keyfile rustfs-root-access-key rustfs-root-secret-key rustfs-app-access-key
  rustfs-app-secret-key tls-store-password kafka-keystore-password kafka-key-password
  kafka-truststore-password spark-auth-secret keycloak-db-password
  keycloak-bootstrap-admin-password keycloak-api-admin-client-secret
  keycloak-pipeline-client-secret keycloak-mediator-client-secret
  keycloak-openhim-client-secret iol-initial-admin-password
  openhim-mediator-password openhim-inbound-client-password
  smtp-password iol-restic-password
  backup-s3-access-key backup-s3-secret-key
)
for secret_name in "${required_secrets[@]}"; do
  [[ -s "${BACKEND_DIR}/secrets/${secret_name}" ]] \
    || fail "secret absent ou vide: secrets/${secret_name}"
done
for secret_name in vault-api-role-id vault-api-secret-id \
  vault-source-gateway-role-id vault-source-gateway-secret-id rustfs-vault-token \
  vault-backup-role-id vault-backup-secret-id; do
  [[ -s "${BACKEND_DIR}/secrets/vault-generated/${secret_name}" ]] \
    || fail "secret Vault absent: secrets/vault-generated/${secret_name}"
done

if [[ -e "${BACKEND_DIR}/secrets/vault-bootstrap-root-token" ]]; then
  fail 'le jeton root de bootstrap Vault doit etre supprime apres initialisation'
fi
[[ -s "${BACKEND_DIR}/vault/config/seal.hcl" ]] \
  || fail 'configuration auto-unseal KMS/HSM absente: vault/config/seal.hcl'
grep -Eq '^[[:space:]]*seal[[:space:]]+"' "${BACKEND_DIR}/vault/config/seal.hcl" \
  || fail 'aucune stanza seal KMS/HSM active'

certificates=(
  nginx/nginx.crt api-core/api-core.crt source-gateway/source-gateway.crt \
  pipeline-consumer/pipeline-consumer.crt iol-mediator/iol-mediator.crt \
  iol-fhir-mediator/iol-fhir-mediator.crt \
  iol-iso20022-mediator/iol-iso20022-mediator.crt \
  iol-edfi-mediator/iol-edfi-mediator.crt openhim/openhim.crt
  postgres/postgres.crt mongodb/mongodb.crt kafka-1/kafka.crt
  rustfs/rustfs.crt rustfs-lb/rustfs-lb.crt keycloak/keycloak.crt \
  spark-master/spark-master.crt spark-worker/spark-worker.crt \
  vault-renewer/vault-renewer.crt
)
for certificate in "${certificates[@]}"; do
  certificate_path="${BACKEND_DIR}/secrets/runtime-tls/${certificate}"
  [[ -s "${certificate_path}" ]] || fail "certificat absent: ${certificate}"
  openssl x509 -checkend 2592000 -noout -in "${certificate_path}" >/dev/null \
    || fail "certificat expire dans moins de 30 jours: ${certificate}"
done

hop_project_dir="${ROOT_DIR}/hop-project"
[[ -f "${hop_project_dir}/project-config.json" ]] \
  || fail 'projet Hop absent: hop-project/project-config.json'
hop_workflow="${HOP_WORKFLOW_FILE:-wf_main_ingestion.hwf}"
[[ -f "${hop_project_dir}/Projet ETL/Global_Config/${hop_workflow}" ]] \
  || fail "workflow Hop absent de la release: ${hop_workflow}"
[[ -f "${hop_project_dir}/Projet ETL/Global_Config/read_config.hpl" ]] \
  || fail 'pipeline Hop obligatoire absent: read_config.hpl'

network_name="${IOL_VAULT_DOCKER_NETWORK:-iol-vault-client}"
docker network inspect "${network_name}" >/dev/null 2>&1 \
  || fail "reseau Vault absent; executez prepare-host.sh"
[[ "$(docker network inspect --format '{{.Internal}}' "${network_name}")" == "true" ]] \
  || fail 'le reseau Vault partage doit etre interne'

docker compose --env-file "${ENV_FILE}" \
  -f "${BACKEND_DIR}/docker-compose.yml" \
  -f "${BACKEND_DIR}/docker-compose.production.yml" config --quiet
docker compose --profile bootstrap --env-file "${ENV_FILE}" \
  -f "${BACKEND_DIR}/docker-compose.yml" \
  -f "${BACKEND_DIR}/docker-compose.production.yml" config --quiet
docker compose --env-file "${ENV_FILE}" \
  -f "${BACKEND_DIR}/openhim/docker-compose.openhim.yml" \
  -f "${BACKEND_DIR}/openhim/docker-compose.openhim.production.yml" config --quiet
docker compose --env-file "${ENV_FILE}" \
  -f "${BACKEND_DIR}/vault/docker-compose.vault-ha.yml" config --quiet

"${PYTHON_BIN}" "${ROOT_DIR}/scripts/validate_production_security.py"
printf 'PREFLIGHT VALIDE. Le deploiement peut entrer en repetition preproduction.\n'
