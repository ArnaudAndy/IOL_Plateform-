#!/usr/bin/env bash
set -Eeuo pipefail

OUTPUT_DIR="${OUTPUT_DIR:-/backup}"
BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092,kafka-2:9092,kafka-3:9092}"
STORE_PASSWORD="$(tr -d '\r\n' < /run/secrets/kafka-truststore-password)"
KEYSTORE_PASSWORD="$(tr -d '\r\n' < /run/secrets/kafka-keystore-password)"
KEY_PASSWORD="$(tr -d '\r\n' < /run/secrets/kafka-key-password)"
ADMIN_CONFIG=/tmp/iol-kafka-backup.properties

mkdir -p "${OUTPUT_DIR}"
cat > "${ADMIN_CONFIG}" <<EOF
security.protocol=SSL
ssl.truststore.location=/run/tls/truststore.p12
ssl.truststore.password=${STORE_PASSWORD}
ssl.truststore.type=PKCS12
ssl.keystore.location=/run/tls/kafka-admin.p12
ssl.keystore.password=${KEYSTORE_PASSWORD}
ssl.key.password=${KEY_PASSWORD}
ssl.keystore.type=PKCS12
ssl.endpoint.identification.algorithm=https
EOF
chmod 600 "${ADMIN_CONFIG}"

capture_with_retry() {
  local output="$1"
  shift
  local attempt
  for attempt in 1 2 3; do
    if "$@" > "${output}.tmp"; then
      mv "${output}.tmp" "${output}"
      return 0
    fi
    rm -f "${output}.tmp"
    sleep $((attempt * 2))
  done
  return 1
}

capture_with_retry "${OUTPUT_DIR}/topics.list" \
  kafka-topics --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --command-config "${ADMIN_CONFIG}" --list
sort -o "${OUTPUT_DIR}/topics.list" "${OUTPUT_DIR}/topics.list"
capture_with_retry "${OUTPUT_DIR}/topics.describe" \
  kafka-topics --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --command-config "${ADMIN_CONFIG}" --describe
capture_with_retry "${OUTPUT_DIR}/topics.configs" \
  kafka-configs --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --command-config "${ADMIN_CONFIG}" --entity-type topics --describe --all
capture_with_retry "${OUTPUT_DIR}/acls.txt" \
  kafka-acls --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --command-config "${ADMIN_CONFIG}" --list
capture_with_retry "${OUTPUT_DIR}/quorum.txt" \
  kafka-metadata-quorum --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --command-config "${ADMIN_CONFIG}" describe --status

test -s "${OUTPUT_DIR}/topics.list"
test -s "${OUTPUT_DIR}/topics.describe"
test -s "${OUTPUT_DIR}/quorum.txt"
printf 'Metadonnees Kafka exportees dans %s.\n' "${OUTPUT_DIR}"
