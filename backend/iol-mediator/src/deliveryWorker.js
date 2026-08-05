'use strict'

/*
 * OUTBOUND data-plane worker.
 *
 * A Kafka command is claimed in the persistent API Core ledger before any
 * network side effect. The target is allow-listed, DNS-checked, optionally
 * verified against OpenHIM's no-body policy, then called with Idempotency-Key.
 */
const dns = require('node:dns').promises
const {safeErrorSummary} = require('./logSafety')
const net = require('node:net')
const {serializeOutboundPayload} = require('./adapters')
const {resolveAuthHeaders} = require('./authResolver')
const {kafkaClientOptions} = require('./kafkaClientConfig')

const EVENT_TYPE = 'OUTBOUND_DELIVERY_REQUESTED'
const STATUS_EVENT_TYPE = 'OUTBOUND_DELIVERY_STATUS'

function requiredString(value, message) {
  if (value == null || String(value).trim() === '') {
    throw new Error(message)
  }
  return String(value).trim()
}

function rowsFromCommand(command) {
  if (Array.isArray(command.pivotRows)) return command.pivotRows
  if (Array.isArray(command.rows)) return command.rows
  return []
}

function targetUrl(destination = {}, config = {}) {
  let raw
  if (destination.endpointUrl) {
    raw = String(destination.endpointUrl)
  } else {
    if (!destination.openhimChannel) {
      throw new Error('outboundConfig.destination.openhimChannel or endpointUrl is required')
    }

    const channel = String(destination.openhimChannel)
    if (/^https?:\/\//i.test(channel)) {
      raw = channel
    } else {
      const base = requiredString(
        config.openhimEgressBaseUrl,
        'OPENHIM_EGRESS_BASE_URL is required when openhimChannel is not a URL'
      ).replace(/\/$/, '')
      const path = channel.replace(/^\//, '')
      raw = `${base}/${path}`
    }
  }

  let parsed
  try {
    parsed = new URL(raw)
  } catch (_error) {
    throw new Error('OUTBOUND destination URL is invalid')
  }
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    throw new Error('OUTBOUND destination must use HTTP or HTTPS')
  }
  if (config.requireTls && parsed.protocol !== 'https:') {
    throw new Error('OUTBOUND destination must use HTTPS in secure mode')
  }
  if (parsed.username || parsed.password) {
    throw new Error('OUTBOUND destination URL must not contain credentials')
  }
  return parsed.toString()
}

function hostMatches(hostname, pattern) {
  if (pattern.startsWith('*.')) {
    const suffix = pattern.slice(1)
    return hostname.endsWith(suffix) && hostname.length > suffix.length
  }
  return hostname === pattern
}

function isPrivateAddress(address) {
  const value = String(address || '').toLowerCase()
  if (net.isIPv4(value)) {
    const [a, b] = value.split('.').map(Number)
    return a === 0 || a === 10 || a === 127 ||
      (a === 100 && b >= 64 && b <= 127) ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      (a === 198 && (b === 18 || b === 19)) ||
      a >= 224
  }
  if (net.isIPv6(value)) {
    if (value.startsWith('::ffff:')) return isPrivateAddress(value.slice(7))
    return value === '::' || value === '::1' || value.startsWith('fc') ||
      value.startsWith('fd') || /^fe[89ab]/.test(value) || value.startsWith('ff')
  }
  return false
}

async function assertSafeDeliveryUrl(url, config = {}, dnsLookup = dns.lookup) {
  const parsed = new URL(url)
  const hostname = parsed.hostname.toLowerCase().replace(/\.$/, '')
  const allowedHosts = Array.isArray(config.outboundAllowedHosts)
    ? config.outboundAllowedHosts.map(value => String(value).toLowerCase())
    : []
  const explicitlyAllowed = allowedHosts.some(pattern => hostMatches(hostname, pattern))

  if (allowedHosts.length > 0 && !explicitlyAllowed) {
    throw new Error(`OUTBOUND destination host is not allow-listed: ${hostname}`)
  }
  if (explicitlyAllowed || config.outboundAllowPrivateNetworks) return
  if (hostname === 'localhost' || hostname.endsWith('.localhost') ||
      hostname.endsWith('.local') || hostname.endsWith('.internal') || isPrivateAddress(hostname)) {
    throw new Error(`OUTBOUND destination resolves to a private or local address: ${hostname}`)
  }

  const resolved = await dnsLookup(hostname, {all: true, verbatim: true})
  const addresses = Array.isArray(resolved) ? resolved : [resolved]
  if (addresses.length === 0 || addresses.some(entry => isPrivateAddress(entry.address || entry))) {
    throw new Error(`OUTBOUND destination resolves to a private or unavailable address: ${hostname}`)
  }
}

function timeoutSignal(timeoutMs) {
  if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.timeout === 'function') {
    return AbortSignal.timeout(timeoutMs)
  }
  return undefined
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

async function processOutboundDelivery(command, {
  config,
  apiClient,
  fetchImpl = globalThis.fetch,
  logger = console,
  dnsLookup = dns.lookup,
  openhimChannelPolicy
} = {}) {
  if (!config) throw new Error('config is required')
  if (!apiClient || typeof apiClient.denormalizeFromPivot !== 'function') {
    throw new Error('apiClient.denormalizeFromPivot is required for OUTBOUND delivery')
  }
  if (!fetchImpl) throw new Error('fetch is required for OUTBOUND delivery')
  if (!command || command.eventType !== EVENT_TYPE) {
    throw new Error(`Unsupported outbound eventType: ${command && command.eventType}`)
  }

  const outboundConfig = command.outboundConfig || {}
  const targetStandardId = requiredString(
    command.targetStandardId || outboundConfig.targetStandardId || outboundConfig.target_standard_id,
    'targetStandardId is required for OUTBOUND delivery'
  )
  const adapter = command.targetAdapter || outboundConfig.targetAdapter || outboundConfig.target_adapter || 'generic-json'
  const targetSystem = command.targetSystem || outboundConfig.targetSystem || outboundConfig.target_system || adapter
  const destination = outboundConfig.destination || {}
  const url = targetUrl(destination, config)
  if (destination.openhimChannel) {
    if (!openhimChannelPolicy
        || typeof openhimChannelPolicy.assertNonReplayable !== 'function') {
      throw new Error('OpenHIM OUTBOUND channel policy is not configured')
    }
    await openhimChannelPolicy.assertNonReplayable(url)
  }
  await assertSafeDeliveryUrl(url, config, dnsLookup)
  const pivotRows = rowsFromCommand(command)

  const denormalized = await apiClient.denormalizeFromPivot(targetStandardId, {
    targetSystem,
    pivotRows
  })
  const serialized = serializeOutboundPayload(denormalized.rows || denormalized, adapter)
  const authHeaders = await resolveAuthHeaders(destination, {config, fetchImpl})

  const response = await fetchImpl(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'X-Correlation-Id': command.correlationId || '',
      'X-IOL-Workflow-Id': command.workflowId || '',
      'X-IOL-Execution-Log-Id': command.execLogId || '',
      'X-IOL-Target-Standard-Id': targetStandardId,
      'X-IOL-Target-System': targetSystem,
      'Idempotency-Key': idempotencyKey(command),
      ...authHeaders
    },
    body: JSON.stringify(serialized.payload),
    signal: timeoutSignal(config.deliveryTimeoutMs || 30000),
    redirect: 'manual'
  })

  if (!response.ok) {
    const body = typeof response.text === 'function' ? await response.text() : ''
    throw new Error(`OUTBOUND delivery failed: HTTP ${response.status} ${body}`.trim())
  }

  logger.info('OUTBOUND delivery completed.', {
    workflowId: command.workflowId,
    execLogId: command.execLogId,
    correlationId: command.correlationId,
    adapter: serialized.adapter,
    rows: pivotRows.length,
    status: response.status
  })

  return {
    workflowId: command.workflowId,
    execLogId: command.execLogId,
    correlationId: command.correlationId,
    adapter: serialized.adapter,
    rows: pivotRows.length,
    status: response.status,
    url
  }
}

function idempotencyKey(command) {
  return command.idempotencyKey
    || command.execLogId
    || command.correlationId
    || `${command.workflowId}:${command.requestedAt || ''}`
}

function deliveryStatusPayload(command, status, details = {}) {
  const outboundConfig = command.outboundConfig || {}
  const destination = outboundConfig.destination || {}
  return {
    eventType: STATUS_EVENT_TYPE,
    workflowId: command.workflowId,
    execLogId: command.execLogId,
    correlationId: command.correlationId,
    status,
    targetStandardId: command.targetStandardId || outboundConfig.targetStandardId || outboundConfig.target_standard_id || null,
    targetAdapter: command.targetAdapter || outboundConfig.targetAdapter || outboundConfig.target_adapter || null,
    destination: destination.openhimChannel || destination.endpointUrl || null,
    attempts: details.attempts || 1,
    httpStatus: details.httpStatus || null,
    errorMessage: details.errorMessage || null,
    deliveredAt: status === 'DELIVERED' ? new Date().toISOString() : null,
    failedAt: status === 'FAILED' ? new Date().toISOString() : null
  }
}

function deliveryDlqMessage(command, error, attempts) {
  const outboundConfig = command.outboundConfig || {}
  return {
    log_id: command.execLogId || command.correlationId || `outbound-${Date.now()}`,
    source_id: 'iol-outbound',
    standard_id: command.targetStandardId || outboundConfig.targetStandardId || outboundConfig.target_standard_id || null,
    correlation_id: command.correlationId || null,
    error_context: {
      step: 'IOL_MEDIATOR_OUTBOUND_DELIVERY',
      message: error.message,
      severity: 'ERROR',
      attempts
    },
    original_data: command,
    timestamp: new Date().toISOString()
  }
}

async function publishDeliveryStatus(statusPublisher, message, logger) {
  if (!statusPublisher || typeof statusPublisher.publishDeliveryStatus !== 'function') {
    return
  }
  try {
    await statusPublisher.publishDeliveryStatus(message)
  } catch (error) {
    logger.error('Unable to publish OUTBOUND delivery status.', safeErrorSummary(error))
  }
}

async function publishDeliveryDlq(dlqPublisher, message, logger) {
  if (!dlqPublisher || typeof dlqPublisher.publishRejection !== 'function') {
    return
  }
  try {
    await dlqPublisher.publishRejection(message)
  } catch (error) {
    logger.error(
      'Unable to publish OUTBOUND delivery failure to DLQ.',
      safeErrorSummary(error)
    )
  }
}

async function claimDelivery(idempotencyStore, key, command) {
  if (!idempotencyStore || !key) return {result: 'CLAIMED', attempts: 0}
  if (typeof idempotencyStore.claim === 'function') {
    return idempotencyStore.claim(key, command)
  }
  if (typeof idempotencyStore.has === 'function' && idempotencyStore.has(key)) {
    return {result: 'ALREADY_DELIVERED', attempts: 0}
  }
  return {result: 'CLAIMED', attempts: 0}
}

async function completeDelivery(idempotencyStore, key, command) {
  if (!idempotencyStore || !key) return
  if (typeof idempotencyStore.complete === 'function') {
    await idempotencyStore.complete(key, command)
  } else if (typeof idempotencyStore.add === 'function') {
    idempotencyStore.add(key)
  }
}

async function failDelivery(idempotencyStore, key, command, error) {
  if (idempotencyStore && key && typeof idempotencyStore.fail === 'function') {
    await idempotencyStore.fail(key, command, error)
  }
}

function durableIdempotencyStore(apiClient, config) {
  const requiredMethods = ['claimOutboundDelivery', 'completeOutboundDelivery', 'failOutboundDelivery']
  if (!apiClient || requiredMethods.some(method => typeof apiClient[method] !== 'function')) {
    throw new Error('Durable OUTBOUND idempotency API is not configured')
  }
  const owner = requiredString(config.outboundWorkerId, 'OUTBOUND_WORKER_ID is required')
  const leaseSeconds = Math.max(30, config.outboundDeliveryLeaseSeconds || 300)
  return {
    claim: key => apiClient.claimOutboundDelivery({idempotencyKey: key, owner, leaseSeconds}),
    complete: key => apiClient.completeOutboundDelivery({idempotencyKey: key, owner}),
    fail: (key, _command, error) => apiClient.failOutboundDelivery({
      idempotencyKey: key,
      owner,
      errorMessage: error && error.message ? error.message : String(error || '')
    })
  }
}

async function deliverOutboundCommand(command, {
  config,
  apiClient,
  fetchImpl = globalThis.fetch,
  logger = console,
  statusPublisher,
  dlqPublisher,
  idempotencyStore,
  dnsLookup = dns.lookup,
  openhimChannelPolicy
} = {}) {
  const key = idempotencyKey(command)
  const claim = await claimDelivery(idempotencyStore, key, command)
  if (claim.result === 'ALREADY_DELIVERED') {
    logger.warn('OUTBOUND delivery skipped because correlationId was already delivered.', {
      correlationId: command.correlationId,
      execLogId: command.execLogId
    })
    return {skipped: true, reason: 'already_delivered', correlationId: command.correlationId}
  }
  if (claim.result !== 'CLAIMED') {
    logger.warn('OUTBOUND delivery skipped because another worker owns the active lease.', {
      correlationId: command.correlationId,
      execLogId: command.execLogId
    })
    return {skipped: true, reason: 'delivery_in_progress', correlationId: command.correlationId}
  }

  const maxAttempts = Math.max(1, (config.outboundMaxRetries || 0) + 1)
  const backoffMs = Math.max(0, config.outboundRetryBackoffMs || 0)
  let lastError

  for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
    try {
      const result = await processOutboundDelivery(command, {
        config,
        apiClient,
        fetchImpl,
        logger,
        dnsLookup,
        openhimChannelPolicy
      })
      try {
        await completeDelivery(idempotencyStore, key, command)
      } catch (ledgerError) {
        const confirmationError = new Error(`Delivery succeeded but durable confirmation failed: ${ledgerError.message}`)
        logger.error(
          'OUTBOUND delivery durable confirmation failed.',
          safeErrorSummary(confirmationError)
        )
        await publishDeliveryStatus(
          statusPublisher,
          deliveryStatusPayload(command, 'FAILED', {attempts: attempt, errorMessage: confirmationError.message}),
          logger
        )
        await publishDeliveryDlq(dlqPublisher, deliveryDlqMessage(command, confirmationError, attempt), logger)
        return {failed: true, delivered: true, attempts: attempt, error: confirmationError.message}
      }
      await publishDeliveryStatus(
        statusPublisher,
        deliveryStatusPayload(command, 'DELIVERED', {attempts: attempt, httpStatus: result.status}),
        logger
      )
      return {...result, attempts: attempt}
    } catch (error) {
      lastError = error
      logger.warn('OUTBOUND delivery attempt failed.', {
        workflowId: command.workflowId,
        execLogId: command.execLogId,
        attempt,
        maxAttempts,
        error: safeErrorSummary(error)
      })
      if (attempt < maxAttempts && backoffMs > 0) {
        await sleep(backoffMs * attempt)
      }
    }
  }

  try {
    await failDelivery(idempotencyStore, key, command, lastError)
  } catch (ledgerError) {
    logger.error(
      'Unable to release durable OUTBOUND delivery lease.',
      safeErrorSummary(ledgerError)
    )
  }

  await publishDeliveryStatus(
    statusPublisher,
    deliveryStatusPayload(command, 'FAILED', {attempts: maxAttempts, errorMessage: lastError.message}),
    logger
  )
  await publishDeliveryDlq(
    dlqPublisher,
    deliveryDlqMessage(command, lastError, maxAttempts),
    logger
  )
  return {failed: true, attempts: maxAttempts, error: lastError.message}
}

async function handleOutboundMessage(raw, dependencies) {
  const {logger = console, dlqPublisher, statusPublisher} = dependencies || {}
  let command
  try {
    command = JSON.parse(raw)
    if (!command || typeof command !== 'object' || Array.isArray(command)) {
      throw new Error('OUTBOUND message must contain a JSON object')
    }
  } catch (error) {
    logger.error('Message OUTBOUND illisible — envoyé en DLQ.', safeErrorSummary(error))
    await publishDeliveryDlq(dlqPublisher, {
      reason: 'INVALID_JSON',
      error: error.message,
      rawPayload: String(raw || '').slice(0, 64_000),
      timestamp: new Date().toISOString()
    }, logger)
    return {rejected: true, reason: 'invalid_json'}
  }

  try {
    return await deliverOutboundCommand(command, dependencies)
  } catch (error) {
    logger.error(
      'Commande OUTBOUND rejetée avant livraison.',
      safeErrorSummary(error)
    )
    await publishDeliveryStatus(
      statusPublisher,
      deliveryStatusPayload(command, 'FAILED', {errorMessage: error.message}),
      logger
    )
    await publishDeliveryDlq(dlqPublisher, deliveryDlqMessage(command, error, 0), logger)
    return {failed: true, attempts: 0, error: error.message}
  }
}

function createNoopOutboundDeliveryWorker(logger = console) {
  return {
    async start() {
      logger.warn('OUTBOUND delivery worker disabled; Kafka bootstrap servers are not configured.')
    },
    async stop() {}
  }
}

function createOutboundDeliveryWorker({
  config,
  apiClient,
  fetchImpl = globalThis.fetch,
  logger = console,
  statusPublisher,
  dlqPublisher,
  openhimChannelPolicy
}) {
  if (!config.kafkaBootstrapServers) {
    return createNoopOutboundDeliveryWorker(logger)
  }

  const {Kafka} = require('kafkajs')
  const kafka = new Kafka(kafkaClientOptions(config, 'iol-mediator-outbound-delivery'))
  const consumer = kafka.consumer({groupId: config.outboundConsumerGroup})
  let running = false
  const idempotencyStore = durableIdempotencyStore(apiClient, config)

  return {
    async start() {
      if (running) return
      await consumer.connect()
      await consumer.subscribe({topic: config.outboundDeliveryTopic, fromBeginning: false})
      running = true
      await consumer.run({
        eachMessage: async ({message}) => {
          const raw = message.value ? message.value.toString('utf8') : ''
          await handleOutboundMessage(raw, {
            config,
            apiClient,
            fetchImpl,
            logger,
            statusPublisher,
            dlqPublisher,
            idempotencyStore,
            openhimChannelPolicy
          })
        }
      })
      logger.info('OUTBOUND delivery worker listening.', {
        topic: config.outboundDeliveryTopic,
        groupId: config.outboundConsumerGroup
      })
    },
    async stop() {
      if (!running) return
      running = false
      await consumer.disconnect()
    }
  }
}

module.exports = {
  deliverOutboundCommand,
  deliveryDlqMessage,
  deliveryStatusPayload,
  createOutboundDeliveryWorker,
  handleOutboundMessage,
  processOutboundDelivery,
  targetUrl,
  assertSafeDeliveryUrl,
  isPrivateAddress
}
