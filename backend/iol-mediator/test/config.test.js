'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')

const {loadConfig} = require('../src/config')

test('loadConfig reads runtime values from the provided env object', () => {
  const config = loadConfig({
    PORT: '3100',
    MEDIATOR_HOST: 'mediator.local',
    MEDIATOR_PATH: '/inbound',
    MEDIATOR_URN: 'urn:test',
    OPENHIM_API_URL: 'https://core:8080',
    OPENHIM_USERNAME: 'admin',
    OPENHIM_PASSWORD: 'secret',
    OPENHIM_TRUST_SELF_SIGNED: 'false',
    MEDIATOR_HEARTBEAT_INTERVAL_MS: '15000',
    IOL_API_BASE_URL: 'http://api-core:8084',
    IOL_INTERNAL_SECRET: 'internal',
    IOL_DEFAULT_STANDARD_ID: 'std_1',
    IOL_DEFAULT_WORKFLOW_ID: 'wf_1',
    IOL_DEFAULT_SOURCE_SYSTEM: 'external',
    IOL_DEFAULT_ADAPTER: 'fhir-basic',
    IOL_INBOUND_AUTH_TYPE: 'private',
    IOL_INBOUND_ALLOWED_ROLES: 'iol-inbound,partner-a',
    IOL_INBOUND_CLIENT_ID: 'partner-system',
    IOL_INBOUND_CLIENT_NAME: 'Partner system',
    IOL_INBOUND_CLIENT_PASSWORD: 'client-secret',
    IOL_MAX_STREAM_BYTES: '999999',
    IOL_MAX_NDJSON_LINE_BYTES: '12345',
    IOL_STREAM_BATCH_ROWS: '321',
    KAFKA_BOOTSTRAP_SERVERS: 'kafka:9092',
    APP_KAFKA_DLQ_TOPIC: 'iol.pipeline.commands.dlq',
    APP_KAFKA_OUTBOUND_TOPIC: 'iol.outbound.delivery',
    APP_KAFKA_OUTBOUND_STATUS_TOPIC: 'iol.outbound.status',
    APP_KAFKA_OUTBOUND_CONSUMER_GROUP: 'iol-mediator-outbound-test',
    OPENHIM_EGRESS_BASE_URL: 'http://openhim-core:5001',
    OUTBOUND_DELIVERY_TIMEOUT_MS: '12000',
    OUTBOUND_MAX_RETRIES: '5',
    OUTBOUND_RETRY_BACKOFF_MS: '25',
    OUTBOUND_ALLOWED_HOSTS: 'partner.example, *.hospital.example',
    OUTBOUND_ALLOW_PRIVATE_NETWORKS: 'true',
    OUTBOUND_DELIVERY_LEASE_SECONDS: '450',
    OUTBOUND_WORKER_ID: 'worker-test'
  })

  assert.equal(config.port, 3100)
  assert.equal(config.mediatorHost, 'mediator.local')
  assert.equal(config.mediatorPath, '/inbound')
  assert.equal(config.mediatorUrn, 'urn:test')
  assert.equal(config.openhimApiUrl, 'https://core:8080')
  assert.equal(config.openhimUsername, 'admin')
  assert.equal(config.openhimPassword, 'secret')
  assert.equal(config.trustSelfSigned, false)
  assert.equal(config.heartbeatIntervalMs, 15000)
  assert.equal(config.iolInternalSecret, 'internal')
  assert.equal(config.defaultStandardId, 'std_1')
  assert.equal(config.defaultWorkflowId, 'wf_1')
  assert.equal(config.defaultSourceSystem, 'external')
  assert.equal(config.defaultAdapter, 'fhir-basic')
  assert.equal(config.inboundAuthType, 'private')
  assert.deepEqual(config.inboundAllowedRoles, ['iol-inbound', 'partner-a'])
  assert.equal(config.inboundClientId, 'partner-system')
  assert.equal(config.inboundClientName, 'Partner system')
  assert.equal(config.inboundClientPassword, 'client-secret')
  assert.equal(config.maxStreamBytes, 999999)
  assert.equal(config.maxNdjsonLineBytes, 12345)
  assert.equal(config.streamBatchRows, 321)
  assert.equal(config.kafkaBootstrapServers, 'kafka:9092')
  assert.equal(config.dlqTopic, 'iol.pipeline.commands.dlq')
  assert.equal(config.outboundDeliveryTopic, 'iol.outbound.delivery')
  assert.equal(config.outboundStatusTopic, 'iol.outbound.status')
  assert.equal(config.outboundConsumerGroup, 'iol-mediator-outbound-test')
  assert.equal(config.openhimEgressBaseUrl, 'http://openhim-core:5001')
  assert.equal(config.deliveryTimeoutMs, 12000)
  assert.equal(config.outboundMaxRetries, 5)
  assert.equal(config.outboundRetryBackoffMs, 25)
  assert.deepEqual(config.outboundAllowedHosts, ['partner.example', '*.hospital.example'])
  assert.equal(config.outboundAllowPrivateNetworks, true)
  assert.equal(config.outboundDeliveryLeaseSeconds, 450)
  assert.equal(config.outboundWorkerId, 'worker-test')
})

test('loadConfig falls back to safe defaults', () => {
  const config = loadConfig({})

  assert.equal(config.port, 3000)
  assert.equal(config.mediatorHost, 'iol-mediator')
  assert.equal(config.mediatorPath, '/')
  assert.equal(config.mediatorUrn, 'urn:mediator:iol-generic')
  assert.equal(config.mediatorVersion, '0.2.0')
  assert.equal(config.openhimPassword, '')
  assert.equal(config.trustSelfSigned, true)
  assert.equal(config.iolApiBaseUrl, 'http://api-core:8084')
  assert.equal(config.defaultStandardId, '')
  assert.equal(config.defaultAdapter, 'generic-json')
  assert.equal(config.inboundAuthType, 'private')
  assert.deepEqual(config.inboundAllowedRoles, ['iol-inbound'])
  assert.equal(config.inboundClientId, '')
  assert.equal(config.inboundClientPassword, '')
  assert.equal(config.maxStreamBytes, 10 * 1024 * 1024 * 1024)
  assert.equal(config.maxNdjsonLineBytes, 128 * 1024 * 1024)
  assert.equal(config.streamBatchRows, 500)
  assert.equal(config.kafkaBootstrapServers, '')
  assert.equal(config.outboundDeliveryTopic, 'iol.outbound.delivery')
  assert.equal(config.outboundStatusTopic, 'iol.outbound.status')
  assert.equal(config.outboundConsumerGroup, 'iol-mediator-outbound')
  assert.equal(config.openhimEgressBaseUrl, '')
  assert.equal(config.deliveryTimeoutMs, 30000)
  assert.equal(config.outboundMaxRetries, 3)
  assert.equal(config.outboundRetryBackoffMs, 1000)
  assert.deepEqual(config.outboundAllowedHosts, [])
  assert.equal(config.outboundAllowPrivateNetworks, false)
  assert.equal(config.outboundDeliveryLeaseSeconds, 300)
})
