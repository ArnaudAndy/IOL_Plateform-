'use strict'

const fs = require('node:fs')
const {Agent} = require('undici')

/*
 * Client for API Core's private interop boundary. Business payloads are sent
 * only to normalization and transport endpoints; logs in this module never
 * contain request bodies or the internal shared secret.
 */
function createApiClient(config, fetchImpl = globalThis.fetch) {
  if (!fetchImpl) {
    throw new Error('fetch is required')
  }

  const baseUrl = config.iolApiBaseUrl.replace(/\/$/, '')
  let cachedToken = null
  const dispatcher = config.iolMtlsEnabled
    ? new Agent({
        connect: {
          ca: fs.readFileSync(config.iolTlsCaFile),
          cert: fs.readFileSync(config.iolTlsCertFile),
          key: fs.readFileSync(config.iolTlsKeyFile),
          rejectUnauthorized: true
        }
      })
    : null

  function request(url, options) {
    return fetchImpl(url, dispatcher ? {...options, dispatcher} : options)
  }

  function oauthSecret() {
    if (config.iolOauthClientSecretFile) {
      return fs.readFileSync(config.iolOauthClientSecretFile, 'utf8').trim()
    }
    return String(config.iolOauthClientSecret || '').trim()
  }

  async function accessToken() {
    if (!config.iolOauthTokenUrl) return ''
    const now = Date.now()
    if (cachedToken && cachedToken.refreshAt > now) return cachedToken.value
    const body = new URLSearchParams({
      grant_type: 'client_credentials',
      client_id: config.iolOauthClientId,
      client_secret: oauthSecret()
    })
    const response = await request(config.iolOauthTokenUrl, {
      method: 'POST',
      headers: {'Content-Type': 'application/x-www-form-urlencoded'},
      body: body.toString()
    })
    const payload = await parseResponse(response)
    if (!payload?.access_token) throw new Error('Keycloak did not return a mediator access token')
    const expiresIn = Math.max(60, Number(payload.expires_in || 300))
    cachedToken = {value: payload.access_token, refreshAt: now + Math.max(30, expiresIn / 2) * 1000}
    return cachedToken.value
  }

  async function headers(extra = {}, contentType = 'application/json') {
    const token = await accessToken()
    const authentication = token
      ? {Authorization: `Bearer ${token}`}
      : {'X-IOL-Internal-Secret': config.iolInternalSecret}
    return {'Content-Type': contentType, ...authentication, ...extra}
  }

  function metadataHeaders(metadata = {}) {
    const result = {}
    for (const [headerName, value] of [
      ['X-IOL-Workflow-Id', metadata.workflowId],
      ['X-IOL-Source-System', metadata.sourceSystem],
      ['X-Correlation-Id', metadata.correlationId],
      ['X-OpenHIM-TransactionID', metadata.openhimTransactionId],
      ['Idempotency-Key', metadata.idempotencyKey],
      ['X-IOL-Payload-SHA256', metadata.payloadHash],
      ['X-IOL-Estimated-Bytes', metadata.estimatedBytes],
      ['X-IOL-Estimated-Rows', metadata.estimatedRows],
      ['X-IOL-Estimated-Max-Record-Bytes', metadata.estimatedMaxRecordBytes]
    ]) {
      if (value !== undefined && value !== null && String(value).trim() !== '') {
        result[headerName] = String(value)
      }
    }
    return result
  }

  async function parseResponse(response) {
    const text = await response.text()
    if (!response.ok) {
      const error = new Error(`api-core ${response.status}: ${text}`)
      error.statusCode = response.status
      throw error
    }
    return text ? JSON.parse(text) : null
  }

  return {
    async getTerms(standardId) {
      const response = await request(`${baseUrl}/api/internal/interop/standards/${encodeURIComponent(standardId)}/terms`, {
        method: 'GET',
        headers: await headers()
      })
      return parseResponse(response)
    },

    async validateBatch(standardId, fields) {
      const response = await request(`${baseUrl}/api/internal/interop/standards/${encodeURIComponent(standardId)}/validate-batch`, {
        method: 'POST',
        headers: await headers(),
        body: JSON.stringify({fields})
      })
      return parseResponse(response)
    },

    async prepareInboundExecution(standardId, request) {
      const response = await request(`${baseUrl}/api/internal/interop/standards/${encodeURIComponent(standardId)}/inbound-executions`, {
        method: 'POST',
        headers: await headers(),
        body: JSON.stringify(request)
      })
      return parseResponse(response)
    },

    async prepareInboundExecutionStream(standardId, metadata, body) {
      const response = await request(
        `${baseUrl}/api/internal/interop/standards/${encodeURIComponent(standardId)}/inbound-executions/stream`,
        {
          method: 'POST',
          headers: await headers(metadataHeaders(metadata), 'application/x-ndjson'),
          body,
          duplex: 'half'
        }
      )
      return parseResponse(response)
    },

    async denormalizeFromPivot(standardId, request) {
      const response = await request(`${baseUrl}/api/internal/interop/standards/${encodeURIComponent(standardId)}/denormalize`, {
        method: 'POST',
        headers: await headers(),
        body: JSON.stringify(request)
      })
      return parseResponse(response)
    },

    async claimOutboundDelivery(request) {
      const response = await request(`${baseUrl}/api/internal/interop/outbound-deliveries/claim`, {
        method: 'POST',
        headers: await headers(),
        body: JSON.stringify(request)
      })
      return parseResponse(response)
    },

    async completeOutboundDelivery(request) {
      const response = await request(`${baseUrl}/api/internal/interop/outbound-deliveries/complete`, {
        method: 'POST',
        headers: await headers(),
        body: JSON.stringify(request)
      })
      return parseResponse(response)
    },

    async failOutboundDelivery(request) {
      const response = await request(`${baseUrl}/api/internal/interop/outbound-deliveries/fail`, {
        method: 'POST',
        headers: await headers(),
        body: JSON.stringify(request)
      })
      return parseResponse(response)
    }
  }
}

module.exports = {
  createApiClient
}
