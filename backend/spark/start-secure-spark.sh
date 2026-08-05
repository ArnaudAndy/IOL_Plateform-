#!/bin/bash
set -Eeuo pipefail

ROLE="${SPARK_NODE_ROLE:?SPARK_NODE_ROLE must be master or worker}"
STORE_PASSWORD="$(cat /run/secrets/tls-store-password)"
AUTH_SECRET_FILE="${SPARK_AUTH_SECRET_FILE:-/run/secrets/spark-auth-secret}"

case "${ROLE}" in
  master)
    KEYSTORE=/run/tls/spark-master.p12
    MAIN_CLASS=org.apache.spark.deploy.master.Master
    ;;
  worker)
    KEYSTORE=/run/tls/spark-worker.p12
    MAIN_CLASS=org.apache.spark.deploy.worker.Worker
    ;;
  *)
    printf 'Role Spark inconnu: %s\n' "${ROLE}" >&2
    exit 1
    ;;
esac

SECURITY_OPTIONS="-Dspark.authenticate=true"
SECURITY_OPTIONS+=" -Dspark.authenticate.secret.file=${AUTH_SECRET_FILE}"
SECURITY_OPTIONS+=" -Dspark.network.crypto.enabled=true"
SECURITY_OPTIONS+=" -Dspark.network.crypto.authEngineVersion=2"
SECURITY_OPTIONS+=" -Dspark.network.crypto.cipher=AES/GCM/NoPadding"
SECURITY_OPTIONS+=" -Dspark.network.crypto.saslFallback=false"
SECURITY_OPTIONS+=" -Dspark.ssl.standalone.enabled=true"
SECURITY_OPTIONS+=" -Dspark.ssl.standalone.protocol=TLSv1.2"
SECURITY_OPTIONS+=" -Dspark.ssl.standalone.needClientAuth=true"
SECURITY_OPTIONS+=" -Dspark.ssl.standalone.keyStore=${KEYSTORE}"
SECURITY_OPTIONS+=" -Dspark.ssl.standalone.keyStoreType=PKCS12"
SECURITY_OPTIONS+=" -Dspark.ssl.standalone.keyStorePassword=${STORE_PASSWORD}"
SECURITY_OPTIONS+=" -Dspark.ssl.standalone.keyPassword=${STORE_PASSWORD}"
SECURITY_OPTIONS+=" -Dspark.ssl.standalone.trustStore=/run/tls/truststore.p12"
SECURITY_OPTIONS+=" -Dspark.ssl.standalone.trustStoreType=PKCS12"
SECURITY_OPTIONS+=" -Dspark.ssl.standalone.trustStorePassword=${STORE_PASSWORD}"
export SPARK_DAEMON_JAVA_OPTS="${SPARK_DAEMON_JAVA_OPTS:-} ${SECURITY_OPTIONS}"

if [[ "${ROLE}" == "worker" ]]; then
  exec /opt/spark/bin/spark-class "${MAIN_CLASS}" "${SPARK_MASTER_URL}"
fi
exec /opt/spark/bin/spark-class "${MAIN_CLASS}"
