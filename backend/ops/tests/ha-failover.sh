#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${IOL_PRODUCTION_ENV_FILE:-${BACKEND_DIR}/.env.production}"

if [[ "${CONFIRM_CHAOS:-}" != "IOL-HA-FAILOVER" ]]; then
  printf 'Refus: exportez CONFIRM_CHAOS=IOL-HA-FAILOVER dans une fenetre de maintenance.\n' >&2
  exit 2
fi
if [[ ! -s "${ENV_FILE}" ]]; then
  printf 'Fichier de production absent: %s\n' "${ENV_FILE}" >&2
  exit 1
fi

compose=(docker compose --env-file "${ENV_FILE}" -f "${BACKEND_DIR}/docker-compose.yml" -f "${BACKEND_DIR}/docker-compose.production.yml")
resolved_services="$("${compose[@]}" config --services)"
for expected in kafka kafka-2 kafka-3 mongodb mongodb-2 mongodb-3 rustfs rustfs-2 rustfs-3 rustfs-4; do
  if ! grep -Fxq "${expected}" <<< "${resolved_services}"; then
    printf 'Topologie HA incomplete, service absent: %s\n' "${expected}" >&2
    exit 1
  fi
done

stopped=()
cleanup() {
  local service
  for service in "${stopped[@]}"; do
    "${compose[@]}" start "${service}" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

wait_healthy() {
  local service="$1"
  local container_id
  container_id="$("${compose[@]}" ps -q "${service}")"
  for _ in $(seq 1 60); do
    if [[ "$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_id}")" == "healthy" ]]; then
      return 0
    fi
    sleep 2
  done
  printf 'Service non sain apres redemarrage: %s\n' "${service}" >&2
  return 1
}

stop_one() {
  local service="$1"
  printf 'Injection de panne: %s\n' "${service}"
  "${compose[@]}" stop -t 30 "${service}"
  stopped+=("${service}")
}

recover_one() {
  local service="$1"
  "${compose[@]}" start "${service}"
  wait_healthy "${service}"
  stopped=("${stopped[@]/$service}")
  printf 'Recuperation validee: %s\n' "${service}"
}

stop_one kafka-3
"${compose[@]}" run --rm --no-deps -T --entrypoint /bin/bash kafka-init \
  -ec 'OUTPUT_DIR=/tmp/kafka-proof /opt/iol/export-metadata.sh >/dev/null; grep -q "CurrentVoters" /tmp/kafka-proof/quorum.txt'
recover_one kafka-3

stop_one mongodb-3
"${compose[@]}" exec -T mongodb sh -ec '
  password="$(cat /run/secrets/mongodb-root-password)"
  mongosh --quiet --tls \
    --tlsCAFile /var/lib/mongodb/iol-tls/ca.pem \
    --tlsCertificateKeyFile /var/lib/mongodb/iol-tls/server.pem \
    --host mongodb --username iol_root --password "$password" \
    --authenticationDatabase admin \
    --eval "const s=rs.status(); if (s.members.filter(m => m.health === 1).length < 2) quit(2);"
'
recover_one mongodb-3

stop_one rustfs-4
"${compose[@]}" exec -T rustfs-lb wget \
  --ca-certificate=/run/tls/ca.pem -qO- \
  https://rustfs-lb:9000/minio/health/ready >/dev/null
recover_one rustfs-4

printf 'TEST DE PANNE HA REUSSI: Kafka, MongoDB et RustFS tolerent la perte d un noeud.\n'
