#!/bin/sh
set -eu

NODE_NAME="${MONGO_TLS_NAME:?MONGO_TLS_NAME is required}"
TLS_DIR=/var/lib/mongodb/iol-tls
mkdir -p "${TLS_DIR}"
cat "/run/tls-source/${NODE_NAME}.key" "/run/tls-source/${NODE_NAME}.crt" > "${TLS_DIR}/server.pem"
cp /run/tls-source/ca.pem "${TLS_DIR}/ca.pem"
cp /run/secrets/mongodb-keyfile "${TLS_DIR}/mongodb-keyfile"
chown -R mongodb:mongodb "${TLS_DIR}"
chmod 0600 "${TLS_DIR}/server.pem" "${TLS_DIR}/mongodb-keyfile"
chmod 0644 "${TLS_DIR}/ca.pem"

exec /usr/local/bin/docker-entrypoint.sh "$@"
