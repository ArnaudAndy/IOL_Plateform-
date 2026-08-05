'use strict'

/*
 * Builds the structured response expected by OpenHIM mediators. Orchestration
 * bodies are always empty: observability lives in properties and correlation
 * identifiers, never in copies of sensitive business payloads.
 */
const crypto = require('crypto')

const REDACTED_HEADERS = new Set([
  'authorization',
  'cookie',
  'set-cookie',
  'x-api-key'
])

function redactHeaders(headers = {}) {
  return Object.fromEntries(
    Object.entries(headers).map(([key, value]) => [
      key,
      REDACTED_HEADERS.has(key.toLowerCase()) ? '[redacted]' : value
    ])
  )
}

function getCorrelationId(req) {
  const headerValue = req.headers['x-correlation-id'] || req.headers['x-request-id']
  if (Array.isArray(headerValue)) {
    return headerValue[0]
  }
  return headerValue || crypto.randomUUID()
}

function buildNormalizedResponse(req, rawBody, config, result) {
  const requestTimestamp = Date.now()
  const correlationId = result.correlationId || getCorrelationId(req)
  const responseBody = JSON.stringify({
    mediator: 'iol-generic',
    mode: 'validated-pivot',
    correlationId,
    openhimTransactionId: result.openhimTransactionId || null,
    adapter: result.adapter || 'generic-json',
    standardId: result.standardId,
    sourceSystem: result.sourceSystem,
    recordCount: result.recordCount || 1,
    handoff: result.handoff
      ? {
          workflowId: result.handoff.workflowId,
          execLogId: result.handoff.execLogId,
          dataTransport: result.handoff.dataTransport,
          recordCount: result.handoff.recordCount
        }
      : null,
    idempotentReplay: result.idempotentReplay === true
  })
  const responseTimestamp = Date.now()

  return {
    'x-mediator-urn': config.mediatorUrn,
    status: 'Successful',
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json'
      },
      body: responseBody,
      timestamp: responseTimestamp
    },
    orchestrations: [
      {
        name: 'IOL mediator validation',
        request: {
          method: req.method,
          path: req.url,
          headers: redactHeaders(req.headers),
          body: '',
          timestamp: requestTimestamp
        },
        response: {
          status: 200,
          headers: {
            'Content-Type': 'application/json'
          },
          body: '',
          timestamp: responseTimestamp
        }
      }
    ],
    properties: {
      mediator: 'iol-generic',
      mode: 'validated-pivot',
      direction: 'INBOUND',
      validation: 'passed',
      adapter: result.adapter || 'generic-json',
      standardId: result.standardId,
      sourceSystem: result.sourceSystem,
      correlationId,
      openhimTransactionId: result.openhimTransactionId,
      workflowId: result.handoff && result.handoff.workflowId,
      execLogId: result.handoff && result.handoff.execLogId,
      kafkaTopic: result.handoff && result.handoff.kafkaTopic,
      recordCount: result.recordCount || 1,
      dataTransport: result.handoff && result.handoff.dataTransport,
      organizationId: result.handoff && result.handoff.organizationId
    }
  }
}

function buildValidationFailureResponse(req, rawBody, config, failure) {
  const requestTimestamp = Date.now()
  const correlationId = failure.correlationId || getCorrelationId(req)
  const responseBody = JSON.stringify({
    mediator: 'iol-generic',
    mode: 'validated-pivot',
    correlationId,
    openhimTransactionId: failure.openhimTransactionId || null,
    adapter: failure.adapter || 'generic-json',
    standardId: failure.standardId,
    sourceSystem: failure.sourceSystem,
    errors: failure.errors
  })
  const responseTimestamp = Date.now()

  return {
    'x-mediator-urn': config.mediatorUrn,
    status: 'Failed',
    response: {
      status: 400,
      headers: {
        'Content-Type': 'application/json'
      },
      body: responseBody,
      timestamp: responseTimestamp
    },
    orchestrations: [
      {
        name: 'IOL mediator validation',
        request: {
          method: req.method,
          path: req.url,
          headers: redactHeaders(req.headers),
          body: '',
          timestamp: requestTimestamp
        },
        response: {
          status: 400,
          headers: {
            'Content-Type': 'application/json'
          },
          body: '',
          timestamp: responseTimestamp
        }
      }
    ],
    properties: {
      mediator: 'iol-generic',
      mode: 'validated-pivot',
      direction: 'INBOUND',
      validation: 'failed',
      adapter: failure.adapter || 'generic-json',
      standardId: failure.standardId,
      sourceSystem: failure.sourceSystem,
      correlationId,
      openhimTransactionId: failure.openhimTransactionId
    }
  }
}

function buildPassThroughResponse(req, rawBody, config) {
  const requestTimestamp = Date.now()
  const correlationId = getCorrelationId(req)
  const responseBody = JSON.stringify({
    mediator: 'iol-generic',
    mode: 'pass-through',
    correlationId,
    received: {
      method: req.method,
      path: req.url
    }
  })
  const responseTimestamp = Date.now()

  return {
    'x-mediator-urn': config.mediatorUrn,
    status: 'Successful',
    response: {
      status: 200,
      headers: {
        'Content-Type': 'application/json'
      },
      body: responseBody,
      timestamp: responseTimestamp
    },
    orchestrations: [
      {
        name: 'IOL mediator pass-through',
        request: {
          method: req.method,
          path: req.url,
          headers: redactHeaders(req.headers),
          body: '',
          timestamp: requestTimestamp
        },
        response: {
          status: 200,
          headers: {
            'Content-Type': 'application/json'
          },
          body: '',
          timestamp: responseTimestamp
        }
      }
    ],
    properties: {
      mediator: 'iol-generic',
      mode: 'pass-through',
      direction: 'INBOUND',
      correlationId
    }
  }
}

function buildErrorResponse(error, config) {
  const now = Date.now()
  const statusCode = Number.isInteger(error.statusCode) ? error.statusCode : 500
  const responseBody = JSON.stringify({
    mediator: 'iol-generic',
    mode: 'pass-through',
    error: error.message || 'Unexpected mediator error'
  })

  return {
    'x-mediator-urn': config.mediatorUrn,
    status: 'Failed',
    response: {
      status: statusCode,
      headers: {
        'Content-Type': 'application/json'
      },
      body: responseBody,
      timestamp: now
    },
    orchestrations: [],
    properties: {
      mediator: 'iol-generic',
      mode: 'pass-through',
      direction: 'INBOUND'
    }
  }
}

module.exports = {
  buildErrorResponse,
  buildNormalizedResponse,
  buildPassThroughResponse,
  buildValidationFailureResponse,
  getCorrelationId,
  redactHeaders
}
