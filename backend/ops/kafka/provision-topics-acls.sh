#!/usr/bin/env bash
set -Eeuo pipefail

BOOTSTRAP_SERVERS="${KAFKA_BOOTSTRAP_SERVERS:-kafka:9092,kafka-2:9092,kafka-3:9092}"
STORE_PASSWORD="$(cat /run/secrets/kafka-truststore-password)"
KEYSTORE_PASSWORD="$(cat /run/secrets/kafka-keystore-password)"
KEY_PASSWORD="$(cat /run/secrets/kafka-key-password)"
ADMIN_CONFIG=/tmp/iol-kafka-admin.properties

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

until kafka-broker-api-versions --bootstrap-server "${BOOTSTRAP_SERVERS}" \
    --command-config "${ADMIN_CONFIG}" >/dev/null 2>&1; do
  sleep 3
done

create_topic() {
  local topic="$1"
  local partitions="$2"
  local retention_ms="$3"
  kafka-topics --bootstrap-server "${BOOTSTRAP_SERVERS}" \
    --command-config "${ADMIN_CONFIG}" --create --if-not-exists \
    --topic "${topic}" --partitions "${partitions}" --replication-factor 3 \
    --config min.insync.replicas=2 --config "retention.ms=${retention_ms}"
}

create_topic iol.pipeline.high 12 604800000
create_topic iol.pipeline.commands 12 604800000
create_topic iol.pipeline.low 6 604800000
create_topic iol.pipeline.status 12 604800000
create_topic iol.pipeline.commands.dlq 3 2592000000
create_topic iol.transport.requests 12 604800000
create_topic iol.transport.requests.dlq 3 2592000000
create_topic iol.outbound.delivery 12 604800000
create_topic iol.outbound.status 12 604800000
create_topic iol.outbound.delivery.dlq 3 2592000000

grant_topic() {
  local principal="$1"
  local topic="$2"
  local operation="$3"
  kafka-acls --bootstrap-server "${BOOTSTRAP_SERVERS}" \
    --command-config "${ADMIN_CONFIG}" --add \
    --allow-principal "User:${principal}" --operation "${operation}" --topic "${topic}"
}

grant_group_prefix() {
  local principal="$1"
  local prefix="$2"
  kafka-acls --bootstrap-server "${BOOTSTRAP_SERVERS}" \
    --command-config "${ADMIN_CONFIG}" --add \
    --allow-principal "User:${principal}" --operation Read \
    --group "${prefix}" --resource-pattern-type prefixed
}

for topic in iol.pipeline.high iol.pipeline.commands iol.pipeline.low iol.outbound.delivery; do
  grant_topic api-core "${topic}" Write
done
grant_topic api-core iol.transport.requests Write
for topic in iol.pipeline.status iol.outbound.status; do
  grant_topic api-core "${topic}" Read
done
grant_group_prefix api-core iol-api-

for topic in iol.pipeline.high iol.pipeline.commands iol.pipeline.low; do
  grant_topic pipeline-consumer "${topic}" Read
done
for topic in iol.pipeline.status iol.pipeline.commands.dlq; do
  grant_topic pipeline-consumer "${topic}" Write
done
grant_group_prefix pipeline-consumer iol-pipeline-

grant_topic source-gateway iol.transport.requests Read
grant_group_prefix source-gateway source-gateway-
for topic in iol.pipeline.high iol.pipeline.commands iol.pipeline.low \
  iol.pipeline.status iol.transport.requests.dlq; do
  grant_topic source-gateway "${topic}" Write
done

for topic in iol.pipeline.high iol.pipeline.commands iol.pipeline.low iol.pipeline.status; do
  grant_topic iol-mediator "${topic}" Write
done
for topic in iol.outbound.delivery; do
  grant_topic iol-mediator "${topic}" Read
done
for topic in iol.outbound.status iol.outbound.delivery.dlq; do
  grant_topic iol-mediator "${topic}" Write
done
grant_group_prefix iol-mediator iol-mediator-

for principal in api-core source-gateway pipeline-consumer iol-mediator; do
  kafka-acls --bootstrap-server "${BOOTSTRAP_SERVERS}" \
    --command-config "${ADMIN_CONFIG}" --add \
    --allow-principal "User:${principal}" --operation IdempotentWrite --cluster
  kafka-acls --bootstrap-server "${BOOTSTRAP_SERVERS}" \
    --command-config "${ADMIN_CONFIG}" --add \
    --allow-principal "User:${principal}" --operation Describe --cluster
done

kafka-acls --bootstrap-server "${BOOTSTRAP_SERVERS}" \
  --command-config "${ADMIN_CONFIG}" --list
printf 'Topics et ACL Kafka provisionnes.\n'
