#!/usr/bin/env bash
set -Eeuo pipefail

KCADM="${KCADM:-/opt/keycloak/bin/kcadm.sh}"
SERVER="${KEYCLOAK_ADMIN_BASE_URL:-https://keycloak-1:8443/auth}"
REALM="${KEYCLOAK_REALM:-iol}"

read_secret() {
  local path="$1"
  [[ -f "${path}" ]] || { printf 'Secret file missing: %s\n' "${path}" >&2; exit 1; }
  tr -d '\r\n' < "${path}"
}

json_escape() {
  local value="$1"
  value="${value//\\/\\\\}"
  value="${value//\"/\\\"}"
  value="${value//$'\n'/\\n}"
  value="${value//$'\r'/\\r}"
  printf '%s' "${value}"
}

if [[ -n "${KEYCLOAK_ADMIN_USERNAME_FILE:-}" ]]; then
  ADMIN_USER="$(read_secret "${KEYCLOAK_ADMIN_USERNAME_FILE}")"
else
  ADMIN_USER="${KEYCLOAK_ADMIN_USERNAME:-iol-bootstrap-admin}"
fi
ADMIN_PASSWORD="$(read_secret "${KEYCLOAK_ADMIN_PASSWORD_FILE:-/run/secrets/keycloak-admin-password}")"

if [[ -n "${IOL_TLS_STORE_PASSWORD_FILE:-}" ]]; then
  TLS_STORE_PASSWORD="$(read_secret "${IOL_TLS_STORE_PASSWORD_FILE}")"
  JAVA_OPTS_APPEND="${JAVA_OPTS_APPEND:-} -Djavax.net.ssl.keyStore=/run/tls/keycloak.p12"
  JAVA_OPTS_APPEND+=" -Djavax.net.ssl.keyStoreType=PKCS12"
  JAVA_OPTS_APPEND+=" -Djavax.net.ssl.keyStorePassword=${TLS_STORE_PASSWORD}"
  JAVA_OPTS_APPEND+=" -Djavax.net.ssl.trustStore=/run/tls/truststore.p12"
  JAVA_OPTS_APPEND+=" -Djavax.net.ssl.trustStoreType=PKCS12"
  JAVA_OPTS_APPEND+=" -Djavax.net.ssl.trustStorePassword=${TLS_STORE_PASSWORD}"
  export JAVA_OPTS_APPEND
fi

for _ in $(seq 1 90); do
  if "${KCADM}" config credentials --server "${SERVER}" --realm master \
      --user "${ADMIN_USER}" --password "${ADMIN_PASSWORD}" >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
"${KCADM}" get "realms/${REALM}" >/dev/null

set_client_secret() {
  local client_id="$1" secret_file="$2" role="$3"
  local internal_id service_user_id secret
  secret="$(read_secret "${secret_file}")"
  internal_id="$("${KCADM}" get clients -r "${REALM}" -q clientId="${client_id}" --fields id --format csv --noquotes | head -n1)"
  [[ -n "${internal_id}" ]] || { printf 'Client not found: %s\n' "${client_id}" >&2; exit 1; }
  "${KCADM}" update "clients/${internal_id}" -r "${REALM}" -s "secret=${secret}" >/dev/null
  service_user_id="$("${KCADM}" get "clients/${internal_id}/service-account-user" -r "${REALM}" --fields id --format csv --noquotes)"
  "${KCADM}" add-roles -r "${REALM}" --uid "${service_user_id}" --rolename "${role}" >/dev/null
}

set_client_secret iol-pipeline-consumer \
  "${PIPELINE_CLIENT_SECRET_FILE:-/run/secrets/pipeline-client-secret}" SERVICE_PIPELINE
set_client_secret iol-mediator \
  "${MEDIATOR_CLIENT_SECRET_FILE:-/run/secrets/mediator-client-secret}" SERVICE_MEDIATOR

ADMIN_CLIENT_ID="$("${KCADM}" get clients -r "${REALM}" -q clientId=iol-api-admin --fields id --format csv --noquotes | head -n1)"
ADMIN_CLIENT_SECRET="$(read_secret "${API_ADMIN_CLIENT_SECRET_FILE:-/run/secrets/api-admin-client-secret}")"
"${KCADM}" update "clients/${ADMIN_CLIENT_ID}" -r "${REALM}" -s "secret=${ADMIN_CLIENT_SECRET}" >/dev/null
ADMIN_SERVICE_USER="$("${KCADM}" get "clients/${ADMIN_CLIENT_ID}/service-account-user" -r "${REALM}" --fields id --format csv --noquotes)"
REALM_MGMT_ID="$("${KCADM}" get clients -r "${REALM}" -q clientId=realm-management --fields id --format csv --noquotes | head -n1)"
for role in view-users query-users manage-users view-realm; do
  "${KCADM}" add-roles -r "${REALM}" --uid "${ADMIN_SERVICE_USER}" \
    --cclientid realm-management --rolename "${role}" >/dev/null
done

OPENHIM_CLIENT_ID="$("${KCADM}" get clients -r "${REALM}" -q clientId=openhim-console --fields id --format csv --noquotes | head -n1)"
OPENHIM_CLIENT_SECRET="$(read_secret "${OPENHIM_CLIENT_SECRET_FILE:-/run/secrets/openhim-client-secret}")"
"${KCADM}" update "clients/${OPENHIM_CLIENT_ID}" -r "${REALM}" \
  -s "secret=${OPENHIM_CLIENT_SECRET}" >/dev/null

: "${IOL_INITIAL_ADMIN_USERNAME:?IOL_INITIAL_ADMIN_USERNAME is required}"
: "${IOL_INITIAL_ADMIN_EMAIL:?IOL_INITIAL_ADMIN_EMAIL is required}"
: "${IOL_INITIAL_ADMIN_PASSWORD_FILE:?IOL_INITIAL_ADMIN_PASSWORD_FILE is required}"
if ! "${KCADM}" get users -r "${REALM}" -q username="${IOL_INITIAL_ADMIN_USERNAME}" --fields id \
    --format csv --noquotes | grep -q .; then
  "${KCADM}" create users -r "${REALM}" \
    -s "username=${IOL_INITIAL_ADMIN_USERNAME}" \
    -s "email=${IOL_INITIAL_ADMIN_EMAIL}" \
    -s enabled=true -s emailVerified=true >/dev/null
fi
USER_ID="$("${KCADM}" get users -r "${REALM}" -q username="${IOL_INITIAL_ADMIN_USERNAME}" \
  --fields id --format csv --noquotes | head -n1)"
"${KCADM}" set-password -r "${REALM}" --userid "${USER_ID}" \
  --new-password "$(read_secret "${IOL_INITIAL_ADMIN_PASSWORD_FILE}")" --temporary >/dev/null
"${KCADM}" add-roles -r "${REALM}" --uid "${USER_ID}" --rolename ADMIN >/dev/null
"${KCADM}" update "users/${USER_ID}" -r "${REALM}" \
  -s 'attributes.organization_id=["iol-default"]' >/dev/null

: "${KEYCLOAK_SMTP_HOST:?KEYCLOAK_SMTP_HOST is required}"
: "${KEYCLOAK_SMTP_PORT:?KEYCLOAK_SMTP_PORT is required}"
: "${KEYCLOAK_SMTP_USERNAME:?KEYCLOAK_SMTP_USERNAME is required}"
: "${KEYCLOAK_SMTP_FROM:?KEYCLOAK_SMTP_FROM is required}"
SMTP_PASSWORD="$(read_secret "${KEYCLOAK_SMTP_PASSWORD_FILE:-/run/secrets/smtp-password}")"
SMTP_JSON="$(printf \
  '{"host":"%s","port":"%s","from":"%s","fromDisplayName":"IOL ETL Platform","auth":"true","user":"%s","password":"%s","starttls":"%s","ssl":"%s"}' \
  "$(json_escape "${KEYCLOAK_SMTP_HOST}")" \
  "$(json_escape "${KEYCLOAK_SMTP_PORT}")" \
  "$(json_escape "${KEYCLOAK_SMTP_FROM}")" \
  "$(json_escape "${KEYCLOAK_SMTP_USERNAME}")" \
  "$(json_escape "${SMTP_PASSWORD}")" \
  "${KEYCLOAK_SMTP_STARTTLS:-true}" \
  "${KEYCLOAK_SMTP_SSL:-false}")"
"${KCADM}" update "realms/${REALM}" -s "smtpServer=${SMTP_JSON}" >/dev/null
unset SMTP_PASSWORD SMTP_JSON

if [[ "${KEYCLOAK_REMOVE_BOOTSTRAP_ADMIN:-true}" == "true" ]]; then
  MASTER_ADMIN_ID="$("${KCADM}" get users -r master -q username="${ADMIN_USER}" \
    --fields id --format csv --noquotes | head -n1)"
  [[ -n "${MASTER_ADMIN_ID}" ]] || { printf 'Bootstrap admin introuvable.\n' >&2; exit 1; }
  "${KCADM}" delete "users/${MASTER_ADMIN_ID}" -r master >/dev/null
fi

printf 'Keycloak realm %s bootstrapped; temporary master admin removed.\n' "${REALM}"
