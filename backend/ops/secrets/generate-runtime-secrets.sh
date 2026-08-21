#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BACKEND_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
SECRETS_DIR="${IOL_SECRETS_DIR:-${BACKEND_DIR}/secrets}"

mkdir -p "${SECRETS_DIR}"
chmod 700 "${SECRETS_DIR}"

create_secret() {
  local name="$1"
  local format="${2:-base64}"
  local path="${SECRETS_DIR}/${name}"
  if [[ -s "${path}" ]]; then
    # Docker Compose bind-mount les secrets definis avec `file:`. Les services
    # tournent sous des UID non-root et doivent pouvoir les lire. Le dossier
    # parent reste en 0700, donc les autres comptes du serveur ne peuvent pas
    # y acceder.
    chmod 0444 "${path}"
    printf 'Conserve: %s\n' "${path}"
    return
  fi
  if [[ "${format}" == "hex" ]]; then
    openssl rand -hex 32 > "${path}"
  else
    openssl rand -base64 48 | tr -d '\n' > "${path}"
  fi
  # Les secrets sont montes en lecture seule dans les conteneurs. Le mode 0444
  # est necessaire avec Docker Compose local, qui conserve le mode du fichier
  # source lors du bind mount.
  chmod 0444 "${path}"
  printf 'Cree: %s\n' "${path}"
}

# Les fournisseurs IA sont facultatifs. Docker Compose exige cependant que le
# fichier declare comme secret existe, meme lorsque le fournisseur est desactive.
# Un fichier vide conserve donc l'assistant IA desactive sans bloquer le
# demarrage de la plateforme; l'operateur peut y injecter une cle reelle plus
# tard via son gestionnaire de secrets.
create_optional_secret_file() {
  local name="$1"
  local path="${SECRETS_DIR}/${name}"
  if [[ ! -e "${path}" ]]; then
    : > "${path}"
    printf 'Cree vide (optionnel): %s\n' "${path}"
  else
    printf 'Conserve (optionnel): %s\n' "${path}"
  fi
  chmod 0444 "${path}"
}

create_secret postgres-password
create_secret mongodb-root-password
create_secret mongodb-app-password
create_secret mongodb-openhim-password
create_secret mongodb-gateway-password hex
create_secret mongodb-pipeline-password hex
create_secret rustfs-root-access-key hex
create_secret rustfs-root-secret-key
create_secret rustfs-app-access-key hex
create_secret rustfs-app-secret-key
create_secret keycloak-db-password
create_secret keycloak-bootstrap-admin-password
create_secret keycloak-api-admin-client-secret
create_secret keycloak-pipeline-client-secret
create_secret keycloak-mediator-client-secret
create_secret keycloak-openhim-client-secret
create_secret openhim-mediator-password
create_secret openhim-inbound-client-password
create_secret iol-restic-password
create_secret backup-s3-access-key hex
create_secret backup-s3-secret-key
create_secret smtp-password
create_secret iol-initial-admin-password
create_optional_secret_file gemini-api-key
create_optional_secret_file groq-api-key

printf '\nSecrets d infrastructure generes.\n'
printf 'Provisionnez backup-s3-access-key sur le stockage de sauvegarde avec un droit d ecriture limite au prefixe IOL.\n'
printf 'Les cles IA et les identifiants Vault doivent etre injectes separement, jamais saisis dans Git.\n'
