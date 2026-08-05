#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
BACKUP_ROOT="${BACKUP_ROOT:-${BACKEND_DIR}/backups}"
BACKUP_ID="${BACKUP_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
BACKUP_DIR="${BACKUP_ROOT}/${BACKUP_ID}"
RESTORE_TEST_AFTER_BACKUP="${RESTORE_TEST_AFTER_BACKUP:-true}"
REQUIRE_OFFSITE_BACKUP="${REQUIRE_OFFSITE_BACKUP:-true}"

export BACKUP_ID BACKUP_ROOT

bash "${SCRIPT_DIR}/backup.sh"

if [[ "${RESTORE_TEST_AFTER_BACKUP}" == "true" ]]; then
  bash "${SCRIPT_DIR}/restore-test.sh" "${BACKUP_DIR}"
else
  printf 'ATTENTION: test de restauration desactive pour %s.\n' "${BACKUP_DIR}" >&2
fi

if [[ -n "${RESTIC_REPOSITORY:-}" && -n "${RESTIC_PASSWORD_FILE:-}" ]]; then
  bash "${SCRIPT_DIR}/restic-upload.sh" "${BACKUP_DIR}"
elif [[ "${REQUIRE_OFFSITE_BACKUP}" == "true" ]]; then
  printf 'Sauvegarde hors site obligatoire mais Restic n est pas configure.\n' >&2
  exit 1
else
  printf 'ATTENTION: sauvegarde hors site desactivee pour %s.\n' "${BACKUP_DIR}" >&2
fi

printf 'CYCLE DE SAUVEGARDE VALIDE: %s\n' "${BACKUP_DIR}"
