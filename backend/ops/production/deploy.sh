#!/usr/bin/env bash
set -Eeuo pipefail

# Point d'entree unique pour une preproduction ou une production IOL.
# Il ne construit pas d'image de developpement : les images doivent porter le
# tag immuable declare dans le fichier d'environnement selectionne.

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
ENVIRONMENT=""
BOOTSTRAP=false

usage() {
  cat <<'EOF'
Usage: deploy.sh --environment preproduction|production [--bootstrap]

  --environment  selectionne backend/.env.preproduction ou backend/.env.production
  --bootstrap    initialise Keycloak une seule fois, avant le premier demarrage
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --environment)
      [[ $# -ge 2 ]] || { usage >&2; exit 2; }
      ENVIRONMENT="$2"
      shift 2
      ;;
    --bootstrap)
      BOOTSTRAP=true
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
done

case "${ENVIRONMENT}" in
  preproduction|production) ;;
  *) usage >&2; exit 2 ;;
esac

ENV_FILE="${BACKEND_DIR}/.env.${ENVIRONMENT}"
[[ -s "${ENV_FILE}" ]] || {
  printf 'Fichier d environnement absent: %s\n' "${ENV_FILE}" >&2
  exit 1
}

export IOL_ENV_FILE="${ENV_FILE}"
bash "${SCRIPT_DIR}/preflight.sh"

main_compose=(
  docker compose --env-file "${ENV_FILE}"
  -f "${BACKEND_DIR}/docker-compose.yml"
  -f "${BACKEND_DIR}/docker-compose.production.yml"
)
openhim_compose=(
  docker compose --env-file "${ENV_FILE}"
  -f "${BACKEND_DIR}/openhim/docker-compose.openhim.yml"
  -f "${BACKEND_DIR}/openhim/docker-compose.openhim.production.yml"
)

if [[ "${BOOTSTRAP}" == true ]]; then
  printf 'Initialisation unique de Keycloak...\n'
  "${main_compose[@]}" --profile bootstrap up --abort-on-container-exit keycloak-bootstrap
fi

# La stack principale cree les reseaux nommes. OpenHIM les rejoint ensuite en
# tant que stack distincte, mais avec le meme fichier d'environnement.
printf 'Demarrage de la stack principale...\n'
"${main_compose[@]}" up -d
printf 'Demarrage de la stack OpenHIM...\n'
"${openhim_compose[@]}" up -d

printf 'Deploiement demande. Consultez docs/GUIDE_DEPLOIEMENT.md pour les controles de disponibilite.\n'
