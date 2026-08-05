#!/bin/sh
set -eu

KEYCLOAK_DB_PASSWORD="$(cat /run/secrets/keycloak-db-password)"

psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres \
  --set=keycloak_password="${KEYCLOAK_DB_PASSWORD}" <<'EOSQL'
SELECT format('CREATE ROLE keycloak LOGIN PASSWORD %L', :'keycloak_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'keycloak') \gexec

SELECT 'CREATE DATABASE keycloak OWNER keycloak'
WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = 'keycloak') \gexec
EOSQL
