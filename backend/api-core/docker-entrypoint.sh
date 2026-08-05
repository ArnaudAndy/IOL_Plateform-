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

# Docker/Kubernetes secrets stay in files until process startup. The values are
# exported only inside this container and are never written to an image layer.
read_secret POSTGRES_PASSWORD "${POSTGRES_PASSWORD_FILE:-}"
read_secret MONGODB_PASSWORD "${MONGODB_PASSWORD_FILE:-}"
read_secret OBJECT_STORAGE_ACCESS_KEY "${OBJECT_STORAGE_ACCESS_KEY_FILE:-}"
read_secret OBJECT_STORAGE_SECRET_KEY "${OBJECT_STORAGE_SECRET_KEY_FILE:-}"
read_secret GEMINI_API_KEY "${GEMINI_API_KEY_FILE:-}"
read_secret GROQ_API_KEY "${GROQ_API_KEY_FILE:-}"
read_secret MAIL_PASSWORD "${MAIL_PASSWORD_FILE:-}"
read_secret API_TLS_KEYSTORE_PASSWORD "${API_TLS_KEYSTORE_PASSWORD_FILE:-}"
read_secret IOL_TLS_TRUSTSTORE_PASSWORD "${IOL_TLS_TRUSTSTORE_PASSWORD_FILE:-}"
read_secret KAFKA_SSL_KEYSTORE_PASSWORD "${KAFKA_SSL_KEYSTORE_PASSWORD_FILE:-}"
read_secret KAFKA_SSL_KEY_PASSWORD "${KAFKA_SSL_KEY_PASSWORD_FILE:-}"
read_secret KAFKA_SSL_TRUSTSTORE_PASSWORD "${KAFKA_SSL_TRUSTSTORE_PASSWORD_FILE:-}"

if [ -n "${IOL_TLS_KEYSTORE_PATH:-}" ] && [ -n "${API_TLS_KEYSTORE_PASSWORD:-}" ]; then
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.keyStore=${IOL_TLS_KEYSTORE_PATH}"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.keyStoreType=PKCS12"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.keyStorePassword=${API_TLS_KEYSTORE_PASSWORD}"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.trustStore=${IOL_TLS_TRUSTSTORE_PATH:-/run/tls/truststore.p12}"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.trustStoreType=PKCS12"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.trustStorePassword=${IOL_TLS_TRUSTSTORE_PASSWORD}"
  export JAVA_TOOL_OPTIONS
fi

exec java -jar /app/app.jar
