'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')

const {
  assertSafeDeliveryUrl,
  deliverOutboundCommand,
  handleOutboundMessage,
  processOutboundDelivery,
  targetUrl
} = require('../src/deliveryWorker')

test('targetUrl uses direct endpointUrl first', () => {
  assert.equal(
    targetUrl({endpointUrl: 'https://partner.example/receive'}, {}),
    'https://partner.example/receive'
  )
})

test('targetUrl resolves OpenHIM channel against egress base URL', () => {
  assert.equal(
    targetUrl({openhimChannel: 'channels/outbound-client'}, {openhimEgressBaseUrl: 'http://openhim:5001/'}),
    'http://openhim:5001/channels/outbound-client'
  )
})

test('processOutboundDelivery denormalizes, serializes and posts to target endpoint', async () => {
  const calls = []
  const result = await processOutboundDelivery({
    eventType: 'OUTBOUND_DELIVERY_REQUESTED',
    workflowId: 'wf_out',
    execLogId: 'log_out',
    correlationId: 'corr-out',
    targetStandardId: 'std_partner',
    targetSystem: 'partner-system',
    targetAdapter: 'generic-json',
    outboundConfig: {
      destination: {
        endpointUrl: 'https://partner.example/receive',
        auth: {type: 'BEARER', secretRef: 'partner-token'}
      }
    },
    pivotRows: [{client_id: 'C001'}]
  }, {
    config: {
      deliveryTimeoutMs: 1000,
      outboundAuthSecrets: {'partner-token': 'actual-token'},
      outboundAllowedHosts: ['partner.example']
    },
    apiClient: {
      async denormalizeFromPivot(standardId, request) {
        calls.push({type: 'denormalize', standardId, request})
        return {rows: [{ClientID: 'C001'}]}
      }
    },
    async fetchImpl(url, options) {
      calls.push({type: 'fetch', url, options})
      return {ok: true, status: 200}
    },
    logger: {info() {}, warn() {}, error() {}}
  })

  assert.equal(result.status, 200)
  assert.equal(result.adapter, 'generic-json')
  assert.equal(calls[0].standardId, 'std_partner')
  assert.deepEqual(calls[0].request, {
    targetSystem: 'partner-system',
    pivotRows: [{client_id: 'C001'}]
  })
  assert.equal(calls[1].url, 'https://partner.example/receive')
  assert.equal(calls[1].options.headers.Authorization, 'Bearer actual-token')
  assert.deepEqual(JSON.parse(calls[1].options.body), [{ClientID: 'C001'}])
})

test('processOutboundDelivery posts through OpenHIM egress channel when configured', async () => {
  const calls = []
  const checkedChannels = []
  await processOutboundDelivery({
    eventType: 'OUTBOUND_DELIVERY_REQUESTED',
    workflowId: 'wf_out',
    execLogId: 'log_out',
    correlationId: 'corr-openhim',
    outboundConfig: {
      targetStandardId: 'std_partner',
      targetAdapter: 'fhir-basic',
      destination: {openhimChannel: 'outbound-client'}
    },
    pivotRows: [{patient_id: 'P001'}]
  }, {
    config: {
      openhimEgressBaseUrl: 'http://openhim-core:5001',
      deliveryTimeoutMs: 1000,
      outboundAllowedHosts: ['openhim-core']
    },
    apiClient: {
      async denormalizeFromPivot() {
        return {rows: [{patient_id: 'P001', family_name: 'Doe'}]}
      }
    },
    async fetchImpl(url, options) {
      calls.push({url, options})
      return {ok: true, status: 201}
    },
    openhimChannelPolicy: {
      async assertNonReplayable(url) {
        checkedChannels.push(url)
      }
    },
    logger: {info() {}, warn() {}, error() {}}
  })

  assert.equal(calls[0].url, 'http://openhim-core:5001/outbound-client')
  assert.deepEqual(checkedChannels, ['http://openhim-core:5001/outbound-client'])
  assert.equal(calls[0].options.headers['Idempotency-Key'], 'log_out')
  assert.equal(JSON.parse(calls[0].options.body).resourceType, 'Basic')
})

test('processOutboundDelivery fails fast when delivery target is missing', async () => {
  await assert.rejects(
    () => processOutboundDelivery({
      eventType: 'OUTBOUND_DELIVERY_REQUESTED',
      targetStandardId: 'std_partner',
      outboundConfig: {},
      pivotRows: []
    }, {
      config: {},
      apiClient: {
        async denormalizeFromPivot() {
          return {rows: []}
        }
      },
      async fetchImpl() {
        throw new Error('should not be called')
      }
    }),
    /openhimChannel or endpointUrl/
  )
})

test('deliverOutboundCommand publishes DELIVERED status after successful delivery', async () => {
  const statuses = []
  const result = await deliverOutboundCommand({
    eventType: 'OUTBOUND_DELIVERY_REQUESTED',
    workflowId: 'wf_out',
    execLogId: 'log_out',
    correlationId: 'corr-delivered',
    targetStandardId: 'std_partner',
    targetAdapter: 'generic-json',
    outboundConfig: {
      destination: {endpointUrl: 'https://partner.example/receive'}
    },
    pivotRows: [{client_id: 'C001'}]
  }, {
    config: {deliveryTimeoutMs: 1000, outboundMaxRetries: 0, outboundRetryBackoffMs: 0, outboundAllowedHosts: ['partner.example']},
    apiClient: {
      async denormalizeFromPivot() {
        return {rows: [{ClientID: 'C001'}]}
      }
    },
    async fetchImpl() {
      return {ok: true, status: 204}
    },
    statusPublisher: {
      async publishDeliveryStatus(message) {
        statuses.push(message)
      }
    },
    logger: {info() {}, warn() {}, error() {}}
  })

  assert.equal(result.status, 204)
  assert.equal(statuses.length, 1)
  assert.equal(statuses[0].eventType, 'OUTBOUND_DELIVERY_STATUS')
  assert.equal(statuses[0].status, 'DELIVERED')
  assert.equal(statuses[0].attempts, 1)
})

test('deliverOutboundCommand retries and sends FAILED status plus DLQ after final failure', async () => {
  const statuses = []
  const dlqMessages = []
  let attempts = 0

  const result = await deliverOutboundCommand({
    eventType: 'OUTBOUND_DELIVERY_REQUESTED',
    workflowId: 'wf_out',
    execLogId: 'log_failed',
    correlationId: 'corr-failed',
    targetStandardId: 'std_partner',
    targetAdapter: 'generic-json',
    outboundConfig: {
      destination: {endpointUrl: 'https://partner.example/receive'}
    },
    pivotRows: [{client_id: 'C001'}]
  }, {
    config: {deliveryTimeoutMs: 1000, outboundMaxRetries: 1, outboundRetryBackoffMs: 0, outboundAllowedHosts: ['partner.example']},
    apiClient: {
      async denormalizeFromPivot() {
        return {rows: [{ClientID: 'C001'}]}
      }
    },
    async fetchImpl() {
      attempts += 1
      return {
        ok: false,
        status: 503,
        async text() {
          return 'service unavailable'
        }
      }
    },
    statusPublisher: {
      async publishDeliveryStatus(message) {
        statuses.push(message)
      }
    },
    dlqPublisher: {
      async publishRejection(message) {
        dlqMessages.push(message)
      }
    },
    logger: {info() {}, warn() {}, error() {}}
  })

  assert.equal(result.failed, true)
  assert.equal(result.attempts, 2)
  assert.equal(attempts, 2)
  assert.equal(statuses[0].status, 'FAILED')
  assert.equal(statuses[0].attempts, 2)
  assert.equal(dlqMessages.length, 1)
  assert.equal(dlqMessages[0].error_context.step, 'IOL_MEDIATOR_OUTBOUND_DELIVERY')
  assert.equal(dlqMessages[0].error_context.attempts, 2)
})

test('deliverOutboundCommand skips an already delivered execution in the local worker process', async () => {
  const delivered = new Set(['log_once'])
  let fetchCalls = 0

  const result = await deliverOutboundCommand({
    eventType: 'OUTBOUND_DELIVERY_REQUESTED',
    workflowId: 'wf_out',
    execLogId: 'log_once',
    correlationId: 'corr-once',
    targetStandardId: 'std_partner',
    outboundConfig: {
      destination: {endpointUrl: 'https://partner.example/receive'}
    },
    pivotRows: []
  }, {
    config: {deliveryTimeoutMs: 1000, outboundMaxRetries: 0, outboundRetryBackoffMs: 0, outboundAllowedHosts: ['partner.example']},
    apiClient: {
      async denormalizeFromPivot() {
        return {rows: []}
      }
    },
    async fetchImpl() {
      fetchCalls += 1
      return {ok: true, status: 200}
    },
    idempotencyStore: delivered,
    logger: {info() {}, warn() {}, error() {}}
  })

  assert.equal(result.skipped, true)
  assert.equal(fetchCalls, 0)
})

test('durable idempotency skips the same delivery after a worker restart', async () => {
  const ledger = new Map()
  let fetchCalls = 0
  const command = {
    eventType: 'OUTBOUND_DELIVERY_REQUESTED',
    workflowId: 'wf_restart',
    execLogId: 'log_restart',
    correlationId: 'corr-restart',
    targetStandardId: 'std_partner',
    outboundConfig: {destination: {endpointUrl: 'https://partner.example/receive'}},
    pivotRows: []
  }
  const newStoreInstance = owner => ({
    async claim(key) {
      if (ledger.get(key) === 'DELIVERED') return {result: 'ALREADY_DELIVERED'}
      ledger.set(key, `IN_PROGRESS:${owner}`)
      return {result: 'CLAIMED'}
    },
    async complete(key) {
      ledger.set(key, 'DELIVERED')
    },
    async fail(key) {
      ledger.set(key, 'FAILED')
    }
  })
  const dependencies = idempotencyStore => ({
    config: {deliveryTimeoutMs: 1000, outboundMaxRetries: 0, outboundAllowedHosts: ['partner.example']},
    apiClient: {async denormalizeFromPivot() { return {rows: []} }},
    async fetchImpl() {
      fetchCalls += 1
      return {ok: true, status: 204}
    },
    idempotencyStore,
    logger: {info() {}, warn() {}, error() {}}
  })

  await deliverOutboundCommand(command, dependencies(newStoreInstance('worker-a')))
  const replay = await deliverOutboundCommand(command, dependencies(newStoreInstance('worker-b')))

  assert.equal(fetchCalls, 1)
  assert.equal(replay.skipped, true)
  assert.equal(replay.reason, 'already_delivered')
})

test('invalid JSON is sent to DLQ without throwing and blocking the Kafka partition', async () => {
  const rejected = []
  const result = await handleOutboundMessage('{invalid-json', {
    dlqPublisher: {async publishRejection(message) { rejected.push(message) }},
    logger: {info() {}, warn() {}, error() {}}
  })

  assert.equal(result.rejected, true)
  assert.equal(result.reason, 'invalid_json')
  assert.equal(rejected.length, 1)
  assert.equal(rejected[0].reason, 'INVALID_JSON')
})

test('SSRF guard rejects private addresses and hosts outside the allow-list', async () => {
  await assert.rejects(
    () => assertSafeDeliveryUrl('http://127.0.0.1/admin', {}),
    /private or local address/
  )
  await assert.rejects(
    () => assertSafeDeliveryUrl('https://unexpected.example/hook', {outboundAllowedHosts: ['partner.example']}),
    /not allow-listed/
  )
  await assert.rejects(
    () => assertSafeDeliveryUrl('https://partner.example/hook', {}, async () => [{address: '10.0.0.5'}]),
    /private or unavailable address/
  )
})
