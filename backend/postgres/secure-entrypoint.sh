#!/bin/sh
set -eu

TLS_DIR=/var/lib/postgresql/iol-tls
mkdir -p "${TLS_DIR}"
install -o postgres -g postgres -m 0600 /run/tls-source/postgres.key "${TLS_DIR}/server.key"
install -o postgres -g postgres -m 0644 /run/tls-source/postgres.crt "${TLS_DIR}/server.crt"
install -o postgres -g postgres -m 0644 /run/tls-source/ca.pem "${TLS_DIR}/ca.pem"

exec /usr/local/bin/docker-entrypoint.sh "$@"
