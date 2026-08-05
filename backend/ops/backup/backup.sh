#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
BACKUP_ROOT="${BACKUP_ROOT:-${BACKEND_DIR}/backups}"
BACKUP_ID="${BACKUP_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
BACKUP_DIR="${BACKUP_ROOT}/${BACKUP_ID}"
OPENHIM_ENV_FILE="${OPENHIM_ENV_FILE:-${BACKEND_DIR}/openhim/.env}"
MC_IMAGE="${MC_IMAGE:-minio/mc:RELEASE.2025-05-21T01-59-54Z}"

docker_host_path() {
  case "$(uname -s)" in
    MINGW*|MSYS*) cygpath -w "$1" ;;
    *) printf '%s\n' "$1" ;;
  esac
}

# Git Bash rewrites Linux paths found inside Docker command arguments. Disable
# that implicit behavior and convert only the host paths mounted by Docker.
case "$(uname -s)" in
  MINGW*|MSYS*) export MSYS_NO_PATHCONV=1 ;;
esac

mkdir -p "${BACKUP_DIR}/rustfs" "${BACKUP_DIR}/kafka"
chmod 700 "${BACKUP_DIR}"
cd "${BACKEND_DIR}"

require_service() {
  local service="$1"
  if ! docker compose ps --status running --services | grep -Fxq "${service}"; then
    printf 'Service requis non demarre: %s\n' "${service}" >&2
    exit 1
  fi
}

require_service postgres
require_service mongodb
require_service rustfs
require_service api-core

printf 'Sauvegarde PostgreSQL...\n'
docker compose exec -T postgres sh -c \
  'pg_dump --format=custom --compress=6 --no-owner --no-privileges -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
  > "${BACKUP_DIR}/postgres.dump"
docker compose exec -T postgres sh -c \
  'pg_dumpall --globals-only --no-role-passwords -U "$POSTGRES_USER"' \
  > "${BACKUP_DIR}/postgres-globals.sql"

if docker compose exec -T postgres sh -c \
    'test "$(psql -U "$POSTGRES_USER" -d postgres -tAc "SELECT 1 FROM pg_database WHERE datname='\''keycloak'\''")" = 1'; then
  printf 'Sauvegarde PostgreSQL Keycloak...\n'
  docker compose exec -T postgres sh -c \
    'pg_dump --format=custom --compress=6 --no-owner --no-privileges -U "$POSTGRES_USER" -d keycloak' \
    > "${BACKUP_DIR}/postgres-keycloak.dump"
else
  : > "${BACKUP_DIR}/postgres-keycloak.NOT_PRESENT"
fi

printf 'Sauvegarde MongoDB IOL...\n'
docker compose exec -T mongodb \
  mongodump --host 127.0.0.1 --db iol_metadata --archive --gzip \
  > "${BACKUP_DIR}/mongodb-iol.archive.gz"

if [[ -f "${OPENHIM_ENV_FILE}" ]] \
    && docker compose --env-file "$(docker_host_path "${OPENHIM_ENV_FILE}")" \
      -f openhim/docker-compose.openhim.yml ps --status running --services \
      | grep -Fxq openhim-mongo; then
  printf 'Sauvegarde MongoDB OpenHIM...\n'
  docker compose --env-file "$(docker_host_path "${OPENHIM_ENV_FILE}")" \
    -f openhim/docker-compose.openhim.yml exec -T openhim-mongo \
    mongodump --host 127.0.0.1 --db openhim --archive --gzip \
    > "${BACKUP_DIR}/mongodb-openhim.archive.gz"
else
  printf 'OpenHIM non demarre: sauvegarde OpenHIM marquee absente.\n'
  : > "${BACKUP_DIR}/mongodb-openhim.NOT_RUNNING"
fi

printf 'Sauvegarde des fichiers approuves et de la quarantaine...\n'
docker compose exec -T api-core tar -C /data/iol -czf - uploads \
  > "${BACKUP_DIR}/uploads.tar.gz"
docker compose exec -T api-core tar -C /data/iol -czf - quarantine \
  > "${BACKUP_DIR}/quarantine.tar.gz"

printf 'Sauvegarde logique RustFS...\n'
RUSTFS_ACCESS_KEY_VALUE="$(
  docker compose exec -T rustfs sh -c 'printf "%s" "$RUSTFS_ACCESS_KEY"' | tr -d '\r\n'
)"
RUSTFS_SECRET_KEY_VALUE="$(
  docker compose exec -T rustfs sh -c 'printf "%s" "$RUSTFS_SECRET_KEY"' | tr -d '\r\n'
)"
RUSTFS_BUCKET_VALUE="${OBJECT_STORAGE_BUCKET:-iol-source-data}"
RUSTFS_ENV_FILE="${BACKUP_DIR}/.rustfs-backup.env"
printf 'RUSTFS_ACCESS_KEY=%s\nRUSTFS_SECRET_KEY=%s\nRUSTFS_BUCKET=%s\n' \
  "${RUSTFS_ACCESS_KEY_VALUE}" "${RUSTFS_SECRET_KEY_VALUE}" "${RUSTFS_BUCKET_VALUE}" \
  > "${RUSTFS_ENV_FILE}"
chmod 600 "${RUSTFS_ENV_FILE}"

docker run --rm \
  --network container:iol-rustfs \
  --env-file "$(docker_host_path "${RUSTFS_ENV_FILE}")" \
  --volume "$(docker_host_path "${BACKUP_DIR}/rustfs"):/backup" \
  --entrypoint /bin/sh \
  "${MC_IMAGE}" \
  -c 'mc alias set source http://127.0.0.1:9000 "$RUSTFS_ACCESS_KEY" "$RUSTFS_SECRET_KEY" >/dev/null &&
      mc stat "source/$RUSTFS_BUCKET" >/dev/null &&
      mc ls --recursive --json "source/$RUSTFS_BUCKET" > /backup/.source-inventory.jsonl &&
      if test -s /backup/.source-inventory.jsonl; then
        mc cp --recursive "source/$RUSTFS_BUCKET/" /backup/
      fi &&
      test -f /backup/.source-inventory.jsonl'
rm -f "${RUSTFS_ENV_FILE}"
touch "${BACKUP_DIR}/rustfs/.backup-complete"

printf 'Export de la topologie, des topics et des ACL Kafka...\n'
if docker compose config --services | grep -Fxq kafka-init; then
  docker compose run --rm --no-deps -T \
    --volume "$(docker_host_path "${BACKUP_DIR}/kafka"):/backup" \
    --entrypoint /bin/bash \
    kafka-init /opt/iol/export-metadata.sh
else
  docker compose exec -T kafka kafka-topics --bootstrap-server kafka:9092 --list \
    | tr -d '\r' | sort > "${BACKUP_DIR}/kafka/topics.list"
  docker compose exec -T kafka kafka-topics --bootstrap-server kafka:9092 --describe \
    | tr -d '\r' > "${BACKUP_DIR}/kafka/topics.describe"
  docker compose exec -T kafka kafka-configs --bootstrap-server kafka:9092 \
    --entity-type topics --describe --all | tr -d '\r' \
    > "${BACKUP_DIR}/kafka/topics.configs"
  if docker compose exec -T kafka kafka-acls --bootstrap-server kafka:9092 --list \
      > "${BACKUP_DIR}/kafka/acls.tmp" 2>&1; then
    tr -d '\r' < "${BACKUP_DIR}/kafka/acls.tmp" > "${BACKUP_DIR}/kafka/acls.txt"
  elif grep -Fq 'No Authorizer is configured' "${BACKUP_DIR}/kafka/acls.tmp"; then
    printf 'DEVELOPMENT_ONLY: Kafka ACL authorizer is disabled.\n' \
      > "${BACKUP_DIR}/kafka/acls.txt"
  else
    cat "${BACKUP_DIR}/kafka/acls.tmp" >&2
    exit 1
  fi
  rm -f "${BACKUP_DIR}/kafka/acls.tmp"
  kafka_quorum_exported=false
  for attempt in 1 2 3; do
    if docker compose exec -T kafka kafka-metadata-quorum --bootstrap-server kafka:9092 \
        describe --status > "${BACKUP_DIR}/kafka/quorum.tmp"; then
      tr -d '\r' < "${BACKUP_DIR}/kafka/quorum.tmp" \
        > "${BACKUP_DIR}/kafka/quorum.txt"
      rm -f "${BACKUP_DIR}/kafka/quorum.tmp"
      kafka_quorum_exported=true
      break
    fi
    sleep $((attempt * 2))
  done
  if [[ "${kafka_quorum_exported}" != "true" ]]; then
    printf 'Export du quorum Kafka impossible apres trois tentatives.\n' >&2
    exit 1
  fi
fi
test -s "${BACKUP_DIR}/kafka/topics.list"
test -s "${BACKUP_DIR}/kafka/topics.describe"
test -s "${BACKUP_DIR}/kafka/quorum.txt"

if [[ "${VAULT_BACKUP_ENABLED:-false}" == "true" ]]; then
  printf 'Sauvegarde du cluster Vault Raft...\n'
  bash "${SCRIPT_DIR}/vault-snapshot.sh" "${BACKUP_DIR}/vault.snap"
else
  : > "${BACKUP_DIR}/vault.NOT_CONFIGURED"
fi

cat > "${BACKUP_DIR}/manifest.env" <<EOF
IOL_BACKUP_FORMAT_VERSION=2
IOL_BACKUP_ID=${BACKUP_ID}
IOL_BACKUP_CREATED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
IOL_POSTGRES_DATABASE=lakehouse
IOL_MONGODB_DATABASE=iol_metadata
IOL_OPENHIM_DATABASE=openhim
IOL_RUSTFS_BUCKET=${RUSTFS_BUCKET_VALUE}
IOL_KAFKA_METADATA_EXPORTED=true
IOL_VAULT_SNAPSHOT_INCLUDED=$([[ -s "${BACKUP_DIR}/vault.snap" ]] && printf true || printf false)
EOF

(
  cd "${BACKUP_DIR}"
  find . -type f \
    ! -name SHA256SUMS \
    ! -name '.rustfs-backup.env' \
    -print0 \
    | sort -z \
    | xargs -0 sha256sum > SHA256SUMS
)

printf 'Verification immediate des empreintes...\n'
(
  cd "${BACKUP_DIR}"
  sha256sum --check SHA256SUMS
)

printf 'Sauvegarde terminee: %s\n' "${BACKUP_DIR}"
printf 'Etape obligatoire suivante: restore-test.sh "%s"\n' "${BACKUP_DIR}"
