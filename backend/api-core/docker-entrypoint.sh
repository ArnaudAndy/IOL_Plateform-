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

# Le mot de passe MongoDB est produit comme un secret aleatoire : il peut
# contenir +, / ou =. Il doit donc etre encode dans une URI. Compose conserve
# volontairement le marqueur ${MONGODB_PASSWORD} jusqu'au demarrage pour que
# le mot de passe ne quitte jamais le fichier secret.
if [ -n "${MONGODB_URI:-}" ] && [ -n "${MONGODB_PASSWORD:-}" ]; then
  mongodb_password_encoded="$(printf '%s' "${MONGODB_PASSWORD}" | od -An -tx1 | tr -d ' \n' | sed 's/../%&/g')"
  MONGODB_URI="$(printf '%s\n' "${MONGODB_URI}" | awk -v encoded="${mongodb_password_encoded}" '{ gsub(/\$\{MONGODB_PASSWORD\}/, encoded); print }')"
  export MONGODB_URI
fi

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
