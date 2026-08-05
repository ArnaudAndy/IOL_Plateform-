'use strict'

function intFromEnv(env, name, fallback) {
  const raw = env[name]
  if (raw == null || raw === '') {
    return fallback
  }

  const parsed = Number.parseInt(raw, 10)
  return Number.isFinite(parsed) ? parsed : fallback
}

function boolFromEnv(env, name, fallback) {
  const raw = env[name]
  if (raw == null || raw === '') {
    return fallback
  }

  return ['1', 'true', 'yes', 'on'].includes(String(raw).toLowerCase())
}

function jsonObjectFromEnv(env, name) {
  const raw = env[name]
  if (!raw) return {}
  try {
    const value = JSON.parse(raw)
    return value && typeof value === 'object' && !Array.isArray(value) ? value : {}
  } catch (_error) {
    throw new Error(`${name} must be a JSON object`)
  }
}

function listFromEnv(env, name) {
  return String(env[name] || '').split(',').map(value => value.trim().toLowerCase()).filter(Boolean)
}

function stringListFromEnv(env, name, fallback = []) {
  const raw = env[name]
  if (raw == null || raw === '') return [...fallback]
  return String(raw).split(',').map(value => value.trim()).filter(Boolean)
}

function loadConfig(env = process.env) {
  return {
    port: intFromEnv(env, 'PORT', 3000),
    serverTlsEnabled: boolFromEnv(env, 'IOL_SERVER_TLS_ENABLED', false),
    serverTlsCertFile: env.IOL_SERVER_TLS_CERT_FILE || '',
    serverTlsKeyFile: env.IOL_SERVER_TLS_KEY_FILE || '',
    serverTlsCaFile: env.IOL_SERVER_TLS_CA_FILE || '',
    serverTlsRequireClientCert: boolFromEnv(env, 'IOL_SERVER_TLS_REQUIRE_CLIENT_CERT', false),
    mediatorHost: env.MEDIATOR_HOST || 'iol-mediator',
    mediatorPath: env.MEDIATOR_PATH || '/',
    mediatorUrn: env.MEDIATOR_URN || 'urn:mediator:iol-generic',
    mediatorName: env.MEDIATOR_NAME || 'IOL Generic Interop Mediator',
    mediatorVersion: env.MEDIATOR_VERSION || '0.2.0',
    openhimApiUrl: env.OPENHIM_API_URL || 'https://openhim-core:8080',
    openhimUsername: env.OPENHIM_USERNAME || '',
    openhimPassword: env.OPENHIM_PASSWORD || '',
    trustSelfSigned: boolFromEnv(env, 'OPENHIM_TRUST_SELF_SIGNED', true),
    heartbeatIntervalMs: intFromEnv(env, 'MEDIATOR_HEARTBEAT_INTERVAL_MS', 10000),
    registrationRetryMs: intFromEnv(env, 'MEDIATOR_REGISTRATION_RETRY_MS', 15000),
    iolApiBaseUrl: env.IOL_API_BASE_URL || 'http://api-core:8084',
    iolInternalSecret: env.IOL_INTERNAL_SECRET || '',
    iolOauthTokenUrl: env.IOL_OAUTH_TOKEN_URL || '',
    iolOauthClientId: env.IOL_OAUTH_CLIENT_ID || 'iol-mediator',
    iolOauthClientSecret: env.IOL_OAUTH_CLIENT_SECRET || '',
    iolOauthClientSecretFile: env.IOL_OAUTH_CLIENT_SECRET_FILE || '',
    iolMtlsEnabled: boolFromEnv(env, 'IOL_MTLS_ENABLED', false),
    iolTlsCaFile: env.IOL_TLS_CA_FILE || '',
    iolTlsCertFile: env.IOL_TLS_CERT_FILE || '',
    iolTlsKeyFile: env.IOL_TLS_KEY_FILE || '',
    defaultStandardId: env.IOL_DEFAULT_STANDARD_ID || '',
    defaultWorkflowId: env.IOL_DEFAULT_WORKFLOW_ID || '',
    defaultSourceSystem: env.IOL_DEFAULT_SOURCE_SYSTEM || 'generic-json',
    defaultAdapter: env.IOL_DEFAULT_ADAPTER || 'generic-json',
    inboundAuthType: env.IOL_INBOUND_AUTH_TYPE || 'private',
    inboundAllowedRoles: stringListFromEnv(
      env,
      'IOL_INBOUND_ALLOWED_ROLES',
      ['iol-inbound']
    ),
    inboundClientId: env.IOL_INBOUND_CLIENT_ID || '',
    inboundClientName: env.IOL_INBOUND_CLIENT_NAME || 'IOL inbound system',
    inboundClientPassword: env.IOL_INBOUND_CLIENT_PASSWORD || '',
    maxInboundBytes: intFromEnv(env, 'IOL_MAX_INBOUND_BYTES', 256 * 1024 * 1024),
    maxStreamBytes: intFromEnv(env, 'IOL_MAX_STREAM_BYTES', 10 * 1024 * 1024 * 1024),
    maxNdjsonLineBytes: intFromEnv(env, 'IOL_MAX_NDJSON_LINE_BYTES', 128 * 1024 * 1024),
    streamBatchRows: intFromEnv(env, 'IOL_STREAM_BATCH_ROWS', 500),
    validationBatchSize: intFromEnv(env, 'IOL_VALIDATION_BATCH_SIZE', 2000),
    kafkaBootstrapServers: env.KAFKA_BOOTSTRAP_SERVERS || '',
    kafkaTlsEnabled: boolFromEnv(env, 'KAFKA_TLS_ENABLED', false),
    kafkaTlsCaFile: env.KAFKA_TLS_CA_FILE || '',
    kafkaTlsCertFile: env.KAFKA_TLS_CERT_FILE || '',
    kafkaTlsKeyFile: env.KAFKA_TLS_KEY_FILE || '',
    requireTls: boolFromEnv(env, 'IOL_REQUIRE_TLS', false),
    dlqTopic: env.APP_KAFKA_DLQ_TOPIC || 'iol.pipeline.commands.dlq',
    outboundDeliveryTopic: env.APP_KAFKA_OUTBOUND_TOPIC || 'iol.outbound.delivery',
    outboundStatusTopic: env.APP_KAFKA_OUTBOUND_STATUS_TOPIC || 'iol.outbound.status',
    outboundConsumerGroup: env.APP_KAFKA_OUTBOUND_CONSUMER_GROUP || 'iol-mediator-outbound',
    openhimEgressBaseUrl: env.OPENHIM_EGRESS_BASE_URL || '',
    deliveryTimeoutMs: intFromEnv(env, 'OUTBOUND_DELIVERY_TIMEOUT_MS', 30000),
    outboundMaxRetries: intFromEnv(env, 'OUTBOUND_MAX_RETRIES', 3),
    outboundRetryBackoffMs: intFromEnv(env, 'OUTBOUND_RETRY_BACKOFF_MS', 1000),
    outboundAuthSecrets: jsonObjectFromEnv(env, 'OUTBOUND_AUTH_SECRETS_JSON'),
    outboundAllowedHosts: listFromEnv(env, 'OUTBOUND_ALLOWED_HOSTS'),
    outboundAllowPrivateNetworks: boolFromEnv(env, 'OUTBOUND_ALLOW_PRIVATE_NETWORKS', false),
    outboundDeliveryLeaseSeconds: intFromEnv(env, 'OUTBOUND_DELIVERY_LEASE_SECONDS', 300),
    outboundWorkerId: env.OUTBOUND_WORKER_ID || `${env.HOSTNAME || 'iol-mediator'}-${process.pid}`
  }
}

module.exports = {
  loadConfig
}
