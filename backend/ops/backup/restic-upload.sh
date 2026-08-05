#!/usr/bin/env bash
set -Eeuo pipefail

if [[ $# -ne 1 ]]; then
  printf 'Usage: %s <repertoire-de-sauvegarde-verifie>\n' "$0" >&2
  exit 2
fi

BACKUP_DIR="$(cd -- "$1" && pwd)"
: "${RESTIC_REPOSITORY:?RESTIC_REPOSITORY est obligatoire}"
: "${RESTIC_PASSWORD_FILE:?RESTIC_PASSWORD_FILE est obligatoire}"

if [[ -n "${AWS_ACCESS_KEY_ID_FILE:-}" ]]; then
  AWS_ACCESS_KEY_ID="$(tr -d '\r\n' < "${AWS_ACCESS_KEY_ID_FILE}")"
  export AWS_ACCESS_KEY_ID
fi
if [[ -n "${AWS_SECRET_ACCESS_KEY_FILE:-}" ]]; then
  AWS_SECRET_ACCESS_KEY="$(tr -d '\r\n' < "${AWS_SECRET_ACCESS_KEY_FILE}")"
  export AWS_SECRET_ACCESS_KEY
fi
: "${AWS_ACCESS_KEY_ID:?AWS access key ou AWS_ACCESS_KEY_ID_FILE obligatoire}"
: "${AWS_SECRET_ACCESS_KEY:?AWS secret key ou AWS_SECRET_ACCESS_KEY_FILE obligatoire}"

docker_host_path() {
  case "$(uname -s)" in
    MINGW*|MSYS*) cygpath -w "$1" ;;
    *) printf '%s\n' "$1" ;;
  esac
}

case "$(uname -s)" in
  MINGW*|MSYS*) export MSYS_NO_PATHCONV=1 ;;
esac

if [[ ! -f "${BACKUP_DIR}/SHA256SUMS" ]]; then
  printf 'SHA256SUMS absent; sauvegarde refusee.\n' >&2
  exit 1
fi
(
  cd "${BACKUP_DIR}"
  sha256sum --check SHA256SUMS
)

RESTIC_IMAGE="${RESTIC_IMAGE:-restic/restic:0.18.0}"
PASSWORD_DIR="$(cd -- "$(dirname -- "${RESTIC_PASSWORD_FILE}")" && pwd)"
PASSWORD_NAME="$(basename -- "${RESTIC_PASSWORD_FILE}")"

docker run --rm \
  -e RESTIC_REPOSITORY \
  -e AWS_ACCESS_KEY_ID \
  -e AWS_SECRET_ACCESS_KEY \
  -e AWS_DEFAULT_REGION \
  -v "$(docker_host_path "${BACKUP_DIR}"):/data:ro" \
  -v "$(docker_host_path "${PASSWORD_DIR}"):/run/restic:ro" \
  "${RESTIC_IMAGE}" \
  -r "${RESTIC_REPOSITORY}" \
  --password-file "/run/restic/${PASSWORD_NAME}" \
  backup /data

docker run --rm \
  -e RESTIC_REPOSITORY \
  -e AWS_ACCESS_KEY_ID \
  -e AWS_SECRET_ACCESS_KEY \
  -e AWS_DEFAULT_REGION \
  -v "$(docker_host_path "${PASSWORD_DIR}"):/run/restic:ro" \
  "${RESTIC_IMAGE}" \
  -r "${RESTIC_REPOSITORY}" \
  --password-file "/run/restic/${PASSWORD_NAME}" \
  check --read-data-subset=5%
