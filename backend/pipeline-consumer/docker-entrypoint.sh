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

read_secret EXECUTION_LOCK_PASSWORD "${EXECUTION_LOCK_PASSWORD_FILE:-}"
read_secret MONGODB_PASSWORD "${MONGODB_PASSWORD_FILE:-}"
read_secret OBJECT_STORAGE_ACCESS_KEY "${OBJECT_STORAGE_ACCESS_KEY_FILE:-}"
read_secret OBJECT_STORAGE_SECRET_KEY "${OBJECT_STORAGE_SECRET_KEY_FILE:-}"
read_secret KAFKA_SSL_KEYSTORE_PASSWORD "${KAFKA_SSL_KEYSTORE_PASSWORD_FILE:-}"
read_secret KAFKA_SSL_KEY_PASSWORD "${KAFKA_SSL_KEY_PASSWORD_FILE:-}"
read_secret KAFKA_SSL_TRUSTSTORE_PASSWORD "${KAFKA_SSL_TRUSTSTORE_PASSWORD_FILE:-}"
read_secret IOL_TLS_TRUSTSTORE_PASSWORD "${IOL_TLS_TRUSTSTORE_PASSWORD_FILE:-}"
read_secret PIPELINE_TLS_KEYSTORE_PASSWORD "${PIPELINE_TLS_KEYSTORE_PASSWORD_FILE:-}"
read_secret SPARK_AUTH_SECRET "${SPARK_AUTH_SECRET_FILE:-}"

if [ -n "${IOL_TLS_KEYSTORE_PATH:-}" ] && [ -n "${PIPELINE_TLS_KEYSTORE_PASSWORD:-}" ]; then
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djavax.net.ssl.keyStore=${IOL_TLS_KEYSTORE_PATH}"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.keyStoreType=PKCS12"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.keyStorePassword=${PIPELINE_TLS_KEYSTORE_PASSWORD}"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.trustStore=${IOL_TLS_TRUSTSTORE_PATH:-/run/tls/truststore.p12}"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.trustStoreType=PKCS12"
  JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS} -Djavax.net.ssl.trustStorePassword=${IOL_TLS_TRUSTSTORE_PASSWORD}"
  export JAVA_TOOL_OPTIONS
fi

if [ -n "${SPARK_AUTH_SECRET_FILE:-}" ] && [ -n "${SPARK_TLS_KEYSTORE_PATH:-}" ]; then
  SPARK_DEFAULTS_FILE="${SPARK_CONF_DIR:-/tmp/iol/spark-conf}/spark-defaults.conf"
  mkdir -p "$(dirname "$SPARK_DEFAULTS_FILE")"
  cat > "$SPARK_DEFAULTS_FILE" <<EOF
spark.authenticate true
spark.authenticate.secret.file ${SPARK_AUTH_SECRET_FILE}
spark.network.crypto.enabled true
spark.network.crypto.authEngineVersion 2
spark.network.crypto.cipher AES/GCM/NoPadding
spark.network.crypto.saslFallback false
spark.ssl.standalone.enabled true
spark.ssl.standalone.protocol TLSv1.2
spark.ssl.standalone.needClientAuth true
spark.ssl.standalone.keyStore ${SPARK_TLS_KEYSTORE_PATH}
spark.ssl.standalone.keyStoreType PKCS12
spark.ssl.standalone.keyStorePassword ${PIPELINE_TLS_KEYSTORE_PASSWORD}
spark.ssl.standalone.keyPassword ${PIPELINE_TLS_KEYSTORE_PASSWORD}
spark.ssl.standalone.trustStore ${IOL_TLS_TRUSTSTORE_PATH:-/run/tls/truststore.p12}
spark.ssl.standalone.trustStoreType PKCS12
spark.ssl.standalone.trustStorePassword ${IOL_TLS_TRUSTSTORE_PASSWORD}
EOF
  chmod 600 "$SPARK_DEFAULTS_FILE"
  export SPARK_CONF_DIR="$(dirname "$SPARK_DEFAULTS_FILE")"
fi

SOURCE_CONFIG="${HOP_HOME:-/opt/hop}/config"
RUNTIME_CONFIG="${HOP_CONFIG_FOLDER:-/tmp/iol/hop-config}"
PROJECT_NAME="${HOP_PROJECT_NAME:-Test ETL}"
PROJECT_HOME_VALUE="${HOP_PROJECT_HOME:-/opt/iol/project}"

mkdir -p "$RUNTIME_CONFIG" "${HOP_TEMP_DIR:-/tmp/iol}/audit/executions"
cp -R "$SOURCE_CONFIG"/. "$RUNTIME_CONFIG"/

python3 - "$RUNTIME_CONFIG/hop-config.json" "$PROJECT_NAME" "$PROJECT_HOME_VALUE" <<'PY'
import json
import sys

path, project_name, project_home = sys.argv[1:]
with open(path, encoding="utf-8") as handle:
    config = json.load(handle)

projects = config.get("projectsConfig", {}).get("projectConfigurations", [])
for project in projects:
    if project.get("projectName") == project_name:
        project["projectHome"] = project_home
        break
else:
    projects.append({
        "projectName": project_name,
        "projectHome": project_home,
        "configFilename": "project-config.json",
    })

with open(path, "w", encoding="utf-8") as handle:
    json.dump(config, handle, ensure_ascii=False, indent=2)
PY

exec java -jar /app/pipeline-consumer.jar
