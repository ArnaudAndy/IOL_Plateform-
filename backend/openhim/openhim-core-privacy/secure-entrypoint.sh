#!/bin/sh
set -eu

MONGODB_PASSWORD_FILE="${OPENHIM_MONGODB_PASSWORD_FILE:-/run/secrets/mongodb-openhim-password}"
[ -r "${MONGODB_PASSWORD_FILE}" ] || {
  printf 'Secret MongoDB OpenHIM illisible: %s\n' "${MONGODB_PASSWORD_FILE}" >&2
  exit 1
}

ENCODED_PASSWORD="$(node -e '
  const fs = require("node:fs")
  process.stdout.write(encodeURIComponent(fs.readFileSync(process.argv[1], "utf8").trim()))
' "${MONGODB_PASSWORD_FILE}")"

MONGODB_URI="mongodb://${OPENHIM_MONGODB_USERNAME:-openhim_app}:${ENCODED_PASSWORD}@mongodb:27017,mongodb-2:27017,mongodb-3:27017/openhim?replicaSet=rs-iol&authSource=openhim&tls=true&tlsCAFile=/run/tls/ca.pem&tlsCertificateKeyFile=/run/tls/openhim.pem"
export mongo_url="${MONGODB_URI}"
export mongo_atnaUrl="${MONGODB_URI}"

if [ -n "${OPENHIM_OPENID_CLIENT_SECRET_FILE:-}" ]; then
  export api_openid_clientSecret="$(tr -d '\r\n' < "${OPENHIM_OPENID_CLIENT_SECRET_FILE}")"
fi

exec /usr/local/bin/docker-entrypoint.sh "$@"
