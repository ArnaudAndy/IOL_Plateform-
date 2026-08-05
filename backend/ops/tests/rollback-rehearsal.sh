#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
: "${CURRENT_RELEASE_ENV:?CURRENT_RELEASE_ENV est obligatoire}"
: "${PREVIOUS_RELEASE_ENV:?PREVIOUS_RELEASE_ENV est obligatoire}"

for env_file in "${CURRENT_RELEASE_ENV}" "${PREVIOUS_RELEASE_ENV}"; do
  if [[ ! -s "${env_file}" ]]; then
    printf 'Fichier de release absent: %s\n' "${env_file}" >&2
    exit 1
  fi
  if grep -Eiq '(^|[=:])latest([[:space:]]|$)' "${env_file}"; then
    printf 'Tag mutable latest interdit: %s\n' "${env_file}" >&2
    exit 1
  fi
done

compose_for() {
  local env_file="$1"
  shift
  docker compose --env-file "${env_file}" \
    -f "${BACKEND_DIR}/docker-compose.yml" \
    -f "${BACKEND_DIR}/docker-compose.production.yml" "$@"
}

if [[ "${ROLLBACK_DRY_RUN:-true}" == "true" ]]; then
  printf 'Images courantes:\n'
  compose_for "${CURRENT_RELEASE_ENV}" config --images | sort
  printf 'Images de rollback:\n'
  compose_for "${PREVIOUS_RELEASE_ENV}" config --images | sort
  printf 'DRY RUN REUSSI. Aucun conteneur n a ete modifie.\n'
  exit 0
fi

if [[ "${CONFIRM_ROLLBACK:-}" != "IOL-ROLLBACK-REHEARSAL" ]]; then
  printf 'Refus: exportez CONFIRM_ROLLBACK=IOL-ROLLBACK-REHEARSAL.\n' >&2
  exit 2
fi
: "${PRE_ROLLBACK_BACKUP_DIR:?Une sauvegarde restauree et verifiee est obligatoire}"
(
  cd "${PRE_ROLLBACK_BACKUP_DIR}"
  sha256sum --check SHA256SUMS >/dev/null
)

application_services=(nginx api-core pipeline-consumer keycloak-1 keycloak-2)
restore_current() {
  compose_for "${CURRENT_RELEASE_ENV}" up -d --no-build "${application_services[@]}" >/dev/null 2>&1 || true
}
trap restore_current EXIT

compose_for "${PREVIOUS_RELEASE_ENV}" pull "${application_services[@]}"
compose_for "${PREVIOUS_RELEASE_ENV}" up -d --no-build "${application_services[@]}"

public_url="$(grep -E '^IOL_PUBLIC_URL=' "${PREVIOUS_RELEASE_ENV}" | tail -1 | cut -d= -f2-)"
for _ in $(seq 1 60); do
  if curl --fail --silent --show-error "${public_url}/health/ready" | grep -q '"UP"'; then
    printf 'Version precedente saine; rollback technique valide.\n'
    restore_current
    trap - EXIT
    printf 'Version courante restauree apres repetition.\n'
    exit 0
  fi
  sleep 5
done
printf 'La version precedente n est pas devenue saine dans le delai imparti.\n' >&2
exit 1
