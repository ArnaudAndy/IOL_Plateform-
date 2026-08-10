#!/bin/sh
set -eu

read_secret() {
  variable_name="$1"
  file_path="${2:-}"
  if [ -n "$file_path" ]; then
    if [ ! -r "$file_path" ]; then
      printf 'Secret file is not readable: %s\n' "$file_path" >&2
      exit 1
    fi
    value="$(cat "$file_path")"
    export "$variable_name=$value"
  fi
}

read_secret MONGODB_PASSWORD "${MONGODB_PASSWORD_FILE:-}"
read_secret OBJECT_STORAGE_ACCESS_KEY "${OBJECT_STORAGE_ACCESS_KEY_FILE:-}"
read_secret OBJECT_STORAGE_SECRET_KEY "${OBJECT_STORAGE_SECRET_KEY_FILE:-}"
read_secret KAFKA_SSL_KEYSTORE_PASSWORD "${KAFKA_SSL_KEYSTORE_PASSWORD_FILE:-}"
read_secret KAFKA_SSL_KEY_PASSWORD "${KAFKA_SSL_KEY_PASSWORD_FILE:-}"
read_secret KAFKA_SSL_TRUSTSTORE_PASSWORD "${KAFKA_SSL_TRUSTSTORE_PASSWORD_FILE:-}"
read_secret GATEWAY_TLS_KEYSTORE_PASSWORD "${GATEWAY_TLS_KEYSTORE_PASSWORD_FILE:-}"
read_secret IOL_TLS_TRUSTSTORE_PASSWORD "${IOL_TLS_TRUSTSTORE_PASSWORD_FILE:-}"

if [ -n "${IOL_TLS_KEYSTORE_PATH:-}" ] && [ -n "${GATEWAY_TLS_KEYSTORE_PASSWORD:-}" ]; then
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.keyStore=${IOL_TLS_KEYSTORE_PATH}"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.keyStoreType=PKCS12"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.keyStorePassword=${GATEWAY_TLS_KEYSTORE_PASSWORD}"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.trustStore=${IOL_TLS_TRUSTSTORE_PATH:-/run/tls/truststore.p12}"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.trustStoreType=PKCS12"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.trustStorePassword=${IOL_TLS_TRUSTSTORE_PASSWORD}"
  export JAVA_TOOL_OPTIONS
fi

exec java -jar /app/app.jar
