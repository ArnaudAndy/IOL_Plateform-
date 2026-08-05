'use strict'

/*
 * OpenHIM-facing generic mediator.
 *
 * Buffered JSON and progressive NDJSON share the same normalization contract:
 * external fields -> standard pivot -> API Core. NDJSON is implemented with
 * async generators so HTTP backpressure bounds memory throughout the chain.
 */
const crypto = require('node:crypto')
const {safeErrorSummary} = require('./logSafety')
const http = require('http')
const https = require('https')
const fs = require('node:fs')

const {
  buildErrorResponse,
  buildNormalizedResponse,
  buildPassThroughResponse,
  buildValidationFailureResponse,
  getCorrelationId
} = require('./responseBuilder')
const {createDlqMessage} = require('./dlqPublisher')
const {inboundRows, normalizeManyAndValidate} = require('./normalizer')
const {AdapterError, parseInboundPayload} = require('./adapters')

class StreamValidationError extends Error {
  constructor(message, errors) {
    super(message)
    this.name = 'StreamValidationError'
    this.errors = errors
  }
}

class RequestContractError extends Error {
  constructor(message, statusCode) {
    super(message)
    this.name = 'RequestContractError'
    this.statusCode = statusCode
  }
}

function readBody(req, limitBytes = 10 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let received = 0

    req.on('data', chunk => {
      received += chunk.length
      if (received > limitBytes) {
        reject(new Error(`Request body exceeds ${limitBytes} bytes`))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })

    req.on('end', () => resolve(Buffer.concat(chunks)))
    req.on('error', reject)
  })
}

function isNdjson(contentType = '') {
  return String(contentType).toLowerCase().includes('ndjson')
}

function nonNegativeInteger(value) {
  if (value === undefined || value === null || String(value).trim() === '') {
    return undefined
  }
  const parsed = Number.parseInt(String(value), 10)
  return Number.isSafeInteger(parsed) && parsed >= 0 ? parsed : undefined
}

function requestEstimate(req, headerName) {
  return nonNegativeInteger(header(req, headerName))
}

async function* readNdjsonLines(req, {
  maxStreamBytes = 10 * 1024 * 1024 * 1024,
  maxLineBytes = 128 * 1024 * 1024,
  state = {}
} = {}) {
  // Keep only the unfinished line; complete lines leave memory immediately.
  let pending = Buffer.alloc(0)
  let lineNumber = 0
  state.receivedBytes = 0

  for await (const chunkValue of req) {
    const chunk = Buffer.isBuffer(chunkValue) ? chunkValue : Buffer.from(chunkValue)
    state.receivedBytes += chunk.length
    if (state.receivedBytes > maxStreamBytes) {
      throw new StreamValidationError(
        `Le flux NDJSON dépasse ${maxStreamBytes} octets.`,
        [{recordIndex: lineNumber, fieldName: '$', message: 'Taille maximale du flux dépassée'}]
      )
    }

    pending = pending.length === 0 ? chunk : Buffer.concat([pending, chunk])
    let newlineIndex
    while ((newlineIndex = pending.indexOf(0x0a)) >= 0) {
      let line = pending.subarray(0, newlineIndex)
      pending = pending.subarray(newlineIndex + 1)
      if (line.length > 0 && line[line.length - 1] === 0x0d) {
        line = line.subarray(0, line.length - 1)
      }
      if (line.length > maxLineBytes) {
        throw new StreamValidationError(
          `La ligne NDJSON ${lineNumber + 1} dépasse ${maxLineBytes} octets.`,
          [{recordIndex: lineNumber, fieldName: '$', message: 'Ligne NDJSON trop volumineuse'}]
        )
      }
      if (line.toString('utf8').trim()) {
        lineNumber += 1
        yield {line, lineNumber}
      }
    }
    if (pending.length > maxLineBytes) {
      throw new StreamValidationError(
        `La ligne NDJSON ${lineNumber + 1} dépasse ${maxLineBytes} octets.`,
        [{recordIndex: lineNumber, fieldName: '$', message: 'Ligne NDJSON trop volumineuse'}]
      )
    }
  }

  if (pending.length > 0) {
    if (pending[pending.length - 1] === 0x0d) {
      pending = pending.subarray(0, pending.length - 1)
    }
    if (pending.toString('utf8').trim()) {
      lineNumber += 1
      yield {line: pending, lineNumber}
    }
  }
}

async function validateStreamingBatch({
  rows,
  terms,
  state,
  requestConfig,
  config
}) {
  const result = await normalizeManyAndValidate({
    payload: rows,
    standardId: requestConfig.standardId,
    sourceSystem: requestConfig.sourceSystem,
    apiClient: config.apiClient,
    validationBatchSize: config.validationBatchSize,
    terms,
    recordIndexOffset: state.recordCount,
    includeAllTerms: true
  })
  if (!result.valid) {
    state.errors = result.errors
    throw new StreamValidationError('La validation du flux NDJSON a échoué.', result.errors)
  }
  state.recordCount += result.recordCount
  return result.pivots
}

async function* normalizedNdjsonBody({
  req,
  terms,
  state,
  requestConfig,
  config
}) {
  // Every yielded buffer is consumed directly by fetch(), preserving backpressure.
  const maxRows = Math.max(1, Math.min(config.streamBatchRows || 500, 5000))
  const pendingRows = []

  for await (const {line, lineNumber} of readNdjsonLines(req, {
    maxStreamBytes: config.maxStreamBytes,
    maxLineBytes: config.maxNdjsonLineBytes,
    state
  })) {
    let payload
    try {
      payload = JSON.parse(line.toString('utf8'))
    } catch (_error) {
      const errors = [{
        recordIndex: state.recordCount,
        fieldName: '$',
        message: `JSON invalide à la ligne NDJSON ${lineNumber}`
      }]
      state.errors = errors
      throw new StreamValidationError(errors[0].message, errors)
    }

    let adapted
    try {
      adapted = parseInboundPayload(payload, requestConfig.adapter)
    } catch (error) {
      if (!(error instanceof AdapterError)) throw error
      const errors = [{
        recordIndex: state.recordCount,
        fieldName: '$adapter',
        message: `Ligne NDJSON ${lineNumber}: ${error.message}`
      }]
      state.errors = errors
      throw new StreamValidationError(errors[0].message, errors)
    }
    state.adapter = adapted.adapter

    const adaptedRows = inboundRows(adapted.payload)
    if (adaptedRows.length === 0) {
      const errors = [{
        recordIndex: state.recordCount,
        fieldName: '$',
        message: `La ligne NDJSON ${lineNumber} ne contient aucun enregistrement`
      }]
      state.errors = errors
      throw new StreamValidationError(errors[0].message, errors)
    }
    for (const row of adaptedRows) {
      pendingRows.push(row)
    }

    while (pendingRows.length >= maxRows) {
      const batch = pendingRows.splice(0, maxRows)
      const pivots = await validateStreamingBatch({
        rows: batch,
        terms,
        state,
        requestConfig,
        config
      })
      for (const pivot of pivots) {
        yield Buffer.from(`${JSON.stringify(pivot)}\n`, 'utf8')
      }
    }
  }

  if (pendingRows.length > 0) {
    const pivots = await validateStreamingBatch({
      rows: pendingRows,
      terms,
      state,
      requestConfig,
      config
    })
    for (const pivot of pivots) {
      yield Buffer.from(`${JSON.stringify(pivot)}\n`, 'utf8')
    }
  }

  if (state.recordCount === 0) {
    const errors = [{
      recordIndex: 0,
      fieldName: '$',
      message: 'Le flux NDJSON INBOUND est vide'
    }]
    state.errors = errors
    throw new StreamValidationError(errors[0].message, errors)
  }
}

function sendMediatorResponse(res, responseObject) {
  res.writeHead(200, {
    'Content-Type': 'application/json+openhim; charset=utf-8'
  })
  res.end(JSON.stringify(responseObject))
}

function sendHealth(res, config) {
  res.writeHead(200, {
    'Content-Type': 'application/json; charset=utf-8'
  })
  res.end(JSON.stringify({
    status: 'UP',
    mediator: config.mediatorUrn
  }))
}

function sendReadiness(res, config) {
  const state = config.readinessState || {}
  const checks = {
    openhim: state.openhimRegistered === true,
    kafka: state.kafkaReady === true
  }
  const ready = Object.values(checks).every(Boolean)
  res.writeHead(ready ? 200 : 503, {
    'Content-Type': 'application/json; charset=utf-8'
  })
  res.end(JSON.stringify({
    status: ready ? 'UP' : 'DOWN',
    mediator: config.mediatorUrn,
    checks
  }))
}

function createServer({ config, logger = console } = {}) {
  if (!config) {
    throw new Error('config is required')
  }

  const requestHandler = async (req, res) => {
    if (req.method === 'GET' && req.url === '/health') {
      sendHealth(res, config)
      return
    }
    if (req.method === 'GET' && req.url === '/ready') {
      sendReadiness(res, config)
      return
    }

    try {
      const requestConfig = resolveRequestConfig(req, config)
      const contentType = header(req, 'content-type') || ''

      if (requestConfig.standardId) {
        requireIdempotencyKey(requestConfig.idempotencyKey)
      }

      if (requestConfig.standardId && isNdjson(contentType)) {
        await handleStreamingInbound({req, res, config, logger, requestConfig})
        return
      }

      const body = await readBody(req, config.maxInboundBytes)

      if (!requestConfig.standardId) {
        const mediatorResponse = buildPassThroughResponse(req, body, config)
        sendMediatorResponse(res, mediatorResponse)
        return
      }

      const payload = parseInboundBody(body, contentType)
      const correlationId = getCorrelationId(req)
      let adapted
      try {
        adapted = parseInboundPayload(payload, requestConfig.adapter)
      } catch (error) {
        if (!(error instanceof AdapterError)) {
          throw error
        }
        const failure = {
          ...requestConfig,
          correlationId,
          errors: [{fieldName: '$adapter', message: error.message}]
        }
        await publishValidationFailure({config, logger, req, body, failure})
        sendMediatorResponse(res, buildValidationFailureResponse(req, body, config, failure))
        return
      }

      const result = await normalizeManyAndValidate({
        payload: adapted.payload,
        standardId: requestConfig.standardId,
        sourceSystem: requestConfig.sourceSystem,
        apiClient: config.apiClient,
        validationBatchSize: config.validationBatchSize
      })

      if (result.valid) {
        const preparedExecution = await prepareAndPublishInboundExecution({
          config,
          logger,
          requestConfig,
          correlationId,
          pivots: result.pivots,
          estimatedBytes: body.length,
          payloadHash: sha256(body)
        })
        sendMediatorResponse(res, buildNormalizedResponse(req, body, config, {
          ...requestConfig,
          correlationId,
          adapter: adapted.adapter,
          pivot: result.pivots.length === 1 ? result.pivots[0] : undefined,
          recordCount: result.recordCount,
          idempotentReplay: preparedExecution.idempotentReplay === true,
          handoff: {
            workflowId: preparedExecution.workflowId,
            execLogId: preparedExecution.execLogId,
            kafkaTopic: preparedExecution.kafkaTopic,
            kafkaKey: preparedExecution.kafkaKey,
            organizationId: preparedExecution.organizationId,
            dataTransport: preparedExecution.dataTransport,
            recordCount: preparedExecution.recordCount
          }
        }))
        return
      }

      const failure = {
        ...requestConfig,
        correlationId,
        errors: result.errors
      }
      await publishValidationFailure({config, logger, req, body, failure})
      sendMediatorResponse(res, buildValidationFailureResponse(req, body, config, failure))
    } catch (error) {
      logger.error('Mediator request failed', safeErrorSummary(error))
      sendMediatorResponse(res, buildErrorResponse(error, config))
    }
  }

  if (!config.serverTlsEnabled) return http.createServer(requestHandler)
  return https.createServer({
    cert: fs.readFileSync(config.serverTlsCertFile),
    key: fs.readFileSync(config.serverTlsKeyFile),
    ca: fs.readFileSync(config.serverTlsCaFile),
    requestCert: config.serverTlsRequireClientCert,
    rejectUnauthorized: config.serverTlsRequireClientCert,
    minVersion: 'TLSv1.2'
  }, requestHandler)
}

async function handleStreamingInbound({
  req,
  res,
  config,
  logger,
  requestConfig
}) {
  if (!config.apiClient
      || typeof config.apiClient.prepareInboundExecutionStream !== 'function') {
    throw new Error(
      'apiClient.prepareInboundExecutionStream is required for NDJSON INBOUND hand-off'
    )
  }

  const correlationId = getCorrelationId(req)
  const state = {
    receivedBytes: 0,
    recordCount: 0,
    errors: [],
    adapter: requestConfig.adapter || 'generic-json'
  }
  const contentLength = nonNegativeInteger(header(req, 'content-length'))
  const estimatedBytes = requestEstimate(req, 'x-iol-estimated-bytes') ?? contentLength
  const estimatedRows = requestEstimate(req, 'x-iol-estimated-rows')
  const estimatedMaxRecordBytes = requestEstimate(
    req,
    'x-iol-estimated-max-record-bytes'
  )
  const maxStreamBytes = config.maxStreamBytes || 10 * 1024 * 1024 * 1024

  try {
    if (estimatedBytes !== undefined && estimatedBytes > maxStreamBytes) {
      throw new StreamValidationError(
        `Le flux NDJSON annoncé dépasse ${maxStreamBytes} octets.`,
        [{recordIndex: 0, fieldName: '$', message: 'Taille maximale du flux dépassée'}]
      )
    }

    const terms = await config.apiClient.getTerms(requestConfig.standardId)
    const body = normalizedNdjsonBody({
      req,
      terms,
      state,
      requestConfig,
      config
    })
    let preparedExecution
    try {
      preparedExecution = await config.apiClient.prepareInboundExecutionStream(
        requestConfig.standardId,
        {
          workflowId: requestConfig.workflowId || undefined,
          sourceSystem: requestConfig.sourceSystem,
          correlationId,
          openhimTransactionId: requestConfig.openhimTransactionId || undefined,
          idempotencyKey: requestConfig.idempotencyKey,
          payloadHash: requestConfig.payloadHash || undefined,
          estimatedBytes,
          estimatedRows,
          estimatedMaxRecordBytes
        },
        body
      )
    } catch (error) {
      if (state.errors.length > 0) {
        throw new StreamValidationError(
          'La validation du flux NDJSON a échoué.',
          state.errors
        )
      }
      throw error
    }

    if (!preparedExecution.commandPublished) {
      throw new Error(
        'Le transfert NDJSON exige une publication de commande atomique par api-core.'
      )
    }

    logger.info('Streaming INBOUND hand-off completed.', {
      workflowId: preparedExecution.workflowId,
      execLogId: preparedExecution.execLogId,
      topic: preparedExecution.kafkaTopic,
      transport: preparedExecution.dataTransport,
      records: preparedExecution.recordCount,
      receivedBytes: state.receivedBytes
    })
    const summaryBody = Buffer.from(
      `[NDJSON payload omitted: ${state.receivedBytes} bytes, ${state.recordCount} records]`,
      'utf8'
    )
    sendMediatorResponse(res, buildNormalizedResponse(req, summaryBody, config, {
      ...requestConfig,
      correlationId,
      adapter: state.adapter,
      recordCount: preparedExecution.recordCount,
      idempotentReplay: preparedExecution.idempotentReplay === true,
      handoff: {
        workflowId: preparedExecution.workflowId,
        execLogId: preparedExecution.execLogId,
        kafkaTopic: preparedExecution.kafkaTopic,
        kafkaKey: preparedExecution.kafkaKey,
        organizationId: preparedExecution.organizationId,
        dataTransport: preparedExecution.dataTransport,
        recordCount: preparedExecution.recordCount
      }
    }))
  } catch (error) {
    if (!(error instanceof StreamValidationError)) throw error
    const summaryBody = Buffer.from(
      `[NDJSON payload omitted after ${state.receivedBytes} bytes]`,
      'utf8'
    )
    const failure = {
      ...requestConfig,
      correlationId,
      adapter: state.adapter,
      errors: error.errors
    }
    await publishValidationFailure({
      config,
      logger,
      req,
      body: summaryBody,
      failure
    })
    sendMediatorResponse(
      res,
      buildValidationFailureResponse(req, summaryBody, config, failure)
    )
  }
}

function header(req, name) {
  const value = req.headers[name.toLowerCase()]
  return Array.isArray(value) ? value[0] : value
}

function resolveRequestConfig(req, config) {
  const runtimeConfig = config.runtimeConfig || {}
  return {
    standardId: header(req, 'x-iol-standard-id') || runtimeConfig.standardId || config.defaultStandardId || '',
    workflowId: header(req, 'x-iol-workflow-id') || runtimeConfig.workflowId || config.defaultWorkflowId || '',
    sourceSystem: header(req, 'x-iol-source-system') || runtimeConfig.sourceSystem || config.defaultSourceSystem || 'generic-json',
    adapter: header(req, 'x-iol-adapter') || runtimeConfig.adapter || config.defaultAdapter || 'generic-json',
    openhimTransactionId: header(req, 'x-openhim-transactionid') || header(req, 'x-openhim-transaction-id') || '',
    idempotencyKey: header(req, 'idempotency-key') || '',
    payloadHash: header(req, 'x-iol-payload-sha256') || ''
  }
}

function requireIdempotencyKey(value) {
  const normalized = String(value || '').trim()
  if (!normalized) {
    throw new RequestContractError(
      'Idempotency-Key est obligatoire pour toute réception qui déclenche un workflow.',
      428
    )
  }
  if (normalized.length > 255 || /[\u0000-\u001f\u007f]/.test(normalized)) {
    throw new RequestContractError(
      'Idempotency-Key est invalide ou dépasse 255 caractères.',
      400
    )
  }
  return normalized
}

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex')
}

function parseJsonBody(body) {
  try {
    return JSON.parse(body.toString('utf8') || '{}')
  } catch (_error) {
    return null
  }
}

function parseInboundBody(body, contentType = '') {
  const text = body.toString('utf8').trim()
  if (!text) return null
  if (!String(contentType).toLowerCase().includes('ndjson')) {
    return parseJsonBody(body)
  }
  try {
    return text.split(/\r?\n/).filter(Boolean).map(line => JSON.parse(line))
  } catch (_error) {
    return null
  }
}

async function publishValidationFailure({config, logger, req, body, failure}) {
  if (!config.dlqPublisher || typeof config.dlqPublisher.publishRejection !== 'function') {
    return
  }

  try {
    await config.dlqPublisher.publishRejection(createDlqMessage({
      req,
      rawBody: body,
      standardId: failure.standardId,
      sourceSystem: failure.sourceSystem,
      correlationId: failure.correlationId,
      openhimTransactionId: failure.openhimTransactionId,
      errors: failure.errors
    }))
  } catch (error) {
    logger.error(
      'Unable to publish validation rejection to DLQ.',
      safeErrorSummary(error)
    )
  }
}

async function prepareAndPublishInboundExecution({
  config,
  logger,
  requestConfig,
  correlationId,
  pivots,
  estimatedBytes,
  payloadHash
}) {
  if (!config.apiClient || typeof config.apiClient.prepareInboundExecution !== 'function') {
    throw new Error('apiClient.prepareInboundExecution is required for INBOUND hand-off')
  }
  const preparedExecution = await config.apiClient.prepareInboundExecution(requestConfig.standardId, {
    workflowId: requestConfig.workflowId || undefined,
    sourceSystem: requestConfig.sourceSystem,
    correlationId,
    openhimTransactionId: requestConfig.openhimTransactionId || undefined,
    idempotencyKey: requestConfig.idempotencyKey,
    payloadHash,
    pivots,
    estimatedBytes
  })

  if (!preparedExecution.commandPublished) {
    if (!config.commandPublisher || typeof config.commandPublisher.publishCommand !== 'function') {
      throw new Error('commandPublisher.publishCommand is required for legacy INBOUND hand-off')
    }
    await config.commandPublisher.publishCommand(preparedExecution)
  }
  logger.info('INBOUND hand-off completed.', {
    workflowId: preparedExecution.workflowId,
    execLogId: preparedExecution.execLogId,
    topic: preparedExecution.kafkaTopic,
    transport: preparedExecution.dataTransport,
    records: preparedExecution.recordCount
  })
  return preparedExecution
}

module.exports = {
  createServer,
  handleStreamingInbound,
  normalizedNdjsonBody,
  parseInboundBody,
  parseJsonBody,
  prepareAndPublishInboundExecution,
  readNdjsonLines,
  requireIdempotencyKey,
  resolveRequestConfig,
  readBody,
  sha256
}
