#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s <repertoire-de-sauvegarde>\n' "$0" >&2
  exit 2
fi

BACKUP_DIR="$(cd -- "$1" && pwd)"
TEST_ID="$(date -u +%Y%m%d%H%M%S)-$$"
PG_CONTAINER="iol-restore-pg-${TEST_ID}"
MONGO_CONTAINER="iol-restore-mongo-${TEST_ID}"
OPENHIM_CONTAINER="iol-restore-openhim-${TEST_ID}"
OBJECT_CONTAINER="iol-restore-object-${TEST_ID}"
MC_IMAGE="${MC_IMAGE:-minio/mc:RELEASE.2025-05-21T01-59-54Z}"
MINIO_IMAGE="${MINIO_IMAGE:-minio/minio:RELEASE.2025-09-07T16-13-09Z}"

docker_host_path() {
  case "$(uname -s)" in
    MINGW*|MSYS*) cygpath -w "$1" ;;
    *) printf '%s\n' "$1" ;;
  esac
}

case "$(uname -s)" in
  MINGW*|MSYS*) export MSYS_NO_PATHCONV=1 ;;
esac

cleanup() {
  docker rm -f "${PG_CONTAINER}" "${MONGO_CONTAINER}" "${OPENHIM_CONTAINER}" \
    "${OBJECT_CONTAINER}" \
    >/dev/null 2>&1 || true
}
trap cleanup EXIT

required_files=(
  manifest.env
  SHA256SUMS
  postgres.dump
  postgres-globals.sql
  mongodb-iol.archive.gz
  uploads.tar.gz
  quarantine.tar.gz
  rustfs/.backup-complete
  kafka/topics.list
  kafka/topics.describe
  kafka/quorum.txt
)
for file in "${required_files[@]}"; do
  if [[ ! -f "${BACKUP_DIR}/${file}" ]]; then
    printf 'Fichier de sauvegarde manquant: %s\n' "${file}" >&2
    exit 1
  fi
done
if [[ ! -s "${BACKUP_DIR}/vault.snap" && ! -f "${BACKUP_DIR}/vault.NOT_CONFIGURED" ]]; then
  printf 'Snapshot Vault ou marqueur vault.NOT_CONFIGURED manquant.\n' >&2
  exit 1
fi

printf 'Verification SHA-256...\n'
(
  cd "${BACKUP_DIR}"
  sha256sum --check SHA256SUMS
)

printf 'Restauration PostgreSQL isolee...\n'
docker run -d --rm \
  --name "${PG_CONTAINER}" \
  -e POSTGRES_USER=restore_user \
  -e POSTGRES_PASSWORD=restore_password \
  -e POSTGRES_DB=lakehouse \
  postgres:16 >/dev/null

for _ in $(seq 1 60); do
  if docker exec "${PG_CONTAINER}" pg_isready -U restore_user -d lakehouse >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
docker exec "${PG_CONTAINER}" pg_isready -U restore_user -d lakehouse >/dev/null
docker exec -i "${PG_CONTAINER}" \
  pg_restore --exit-on-error --no-owner --no-privileges -U restore_user -d lakehouse \
  < "${BACKUP_DIR}/postgres.dump"
docker exec "${PG_CONTAINER}" psql -v ON_ERROR_STOP=1 -U restore_user -d lakehouse \
  -c 'SELECT current_database(), count(*) AS tables FROM information_schema.tables;' >/dev/null

if [[ -s "${BACKUP_DIR}/postgres-keycloak.dump" ]]; then
  printf 'Restauration PostgreSQL Keycloak isolee...\n'
  docker exec "${PG_CONTAINER}" createdb -U restore_user keycloak
  docker exec -i "${PG_CONTAINER}" \
    pg_restore --exit-on-error --no-owner --no-privileges -U restore_user -d keycloak \
    < "${BACKUP_DIR}/postgres-keycloak.dump"
  docker exec "${PG_CONTAINER}" psql -v ON_ERROR_STOP=1 -U restore_user -d keycloak \
    -c 'SELECT current_database(), count(*) AS tables FROM information_schema.tables;' >/dev/null
fi

printf 'Restauration MongoDB IOL isolee...\n'
docker run -d --rm --name "${MONGO_CONTAINER}" mongo:7 >/dev/null
for _ in $(seq 1 60); do
  if docker exec "${MONGO_CONTAINER}" mongosh --quiet --eval 'db.adminCommand({ping:1}).ok' \
      | grep -Fxq 1; then
    break
  fi
  sleep 1
done
docker exec -i "${MONGO_CONTAINER}" \
  mongorestore --archive --gzip --drop --nsInclude 'iol_metadata.*' \
  < "${BACKUP_DIR}/mongodb-iol.archive.gz"
docker exec "${MONGO_CONTAINER}" mongosh iol_metadata --quiet \
  --eval 'if (db.getName() !== "iol_metadata") quit(2); db.stats().ok' \
  | grep -Fxq 1

if [[ -s "${BACKUP_DIR}/mongodb-openhim.archive.gz" ]]; then
  printf 'Restauration MongoDB OpenHIM isolee...\n'
  docker run -d --rm --name "${OPENHIM_CONTAINER}" mongo:4.4 >/dev/null
  for _ in $(seq 1 60); do
    if docker exec "${OPENHIM_CONTAINER}" mongo --quiet \
        --eval 'db.adminCommand({ping:1}).ok' | grep -Fxq 1; then
      break
    fi
    sleep 1
  done
  docker exec -i "${OPENHIM_CONTAINER}" \
    mongorestore --archive --gzip --drop --nsInclude 'openhim.*' \
    < "${BACKUP_DIR}/mongodb-openhim.archive.gz"
  docker exec "${OPENHIM_CONTAINER}" mongo openhim --quiet \
    --eval 'db.stats().ok' | grep -Fxq 1
fi

printf 'Verification des archives fichiers...\n'
tar -tzf "${BACKUP_DIR}/uploads.tar.gz" >/dev/null
tar -tzf "${BACKUP_DIR}/quarantine.tar.gz" >/dev/null

printf 'Verification des metadonnees Kafka...\n'
grep -Eq '^iol\.' "${BACKUP_DIR}/kafka/topics.list"
grep -Eq 'Topic: iol\.' "${BACKUP_DIR}/kafka/topics.describe"
grep -Eq 'ClusterId|LeaderId|CurrentVoters' "${BACKUP_DIR}/kafka/quorum.txt"

if [[ -s "${BACKUP_DIR}/vault.snap" ]]; then
  printf 'Inspection hors ligne du snapshot Vault...\n'
  docker run --rm \
    --volume "$(docker_host_path "${BACKUP_DIR}"):/backup:ro" \
    --entrypoint /bin/sh \
    hashicorp/vault:1.21.7 \
    -ec 'vault operator raft snapshot inspect /backup/vault.snap' >/dev/null
fi

printf 'Restauration S3 isolee du contenu RustFS...\n'
docker run -d --rm \
  --name "${OBJECT_CONTAINER}" \
  -e MINIO_ROOT_USER=restore-access \
  -e MINIO_ROOT_PASSWORD=restore-secret-password \
  "${MINIO_IMAGE}" server /data >/dev/null
sleep 3
docker run --rm \
  --network "container:${OBJECT_CONTAINER}" \
  --volume "$(docker_host_path "${BACKUP_DIR}/rustfs"):/backup:ro" \
  --entrypoint /bin/sh \
  "${MC_IMAGE}" \
  -c 'mc alias set target http://127.0.0.1:9000 restore-access restore-secret-password >/dev/null &&
      mc ready target >/dev/null &&
      mc mb --ignore-existing target/restore-test >/dev/null &&
      mc mirror --overwrite /backup target/restore-test >/dev/null &&
      mc stat target/restore-test/.backup-complete >/dev/null'

printf 'TEST DE RESTAURATION REUSSI: %s\n' "${BACKUP_DIR}"
