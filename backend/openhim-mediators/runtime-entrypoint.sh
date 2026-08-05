#!/bin/sh
set -eu

STORE_PASSWORD="$(tr -d '\r\n' < "${TLS_STORE_PASSWORD_FILE:-/run/secrets/tls-store-password}")"
if [ -n "${OPENHIM_PASSWORD_FILE:-}" ]; then
  export OPENHIM_PASSWORD="$(tr -d '\r\n' < "${OPENHIM_PASSWORD_FILE}")"
fi

export SERVER_SSL_KEY_STORE="file:${MEDIATOR_TLS_KEYSTORE_PATH}"
export SERVER_SSL_KEY_STORE_TYPE=PKCS12
export SERVER_SSL_KEY_STORE_PASSWORD="${STORE_PASSWORD}"
export SERVER_SSL_ENABLED=true
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.keyStore=${MEDIATOR_TLS_KEYSTORE_PATH} -Djavax.net.ssl.keyStoreType=PKCS12 -Djavax.net.ssl.keyStorePassword=${STORE_PASSWORD} -Djavax.net.ssl.trustStore=/run/tls/truststore.p12 -Djavax.net.ssl.trustStoreType=PKCS12 -Djavax.net.ssl.trustStorePassword=${STORE_PASSWORD}"

exec java -jar /app/app.jar
