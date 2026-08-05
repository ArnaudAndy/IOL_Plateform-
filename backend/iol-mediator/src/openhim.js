'use strict'

/*
 * OpenHIM control-plane integration: registration, channel installation,
 * privacy reconciliation and heartbeat. It never handles business payloads.
 */
const http = require('node:http')
const https = require('node:https')
const crypto = require('node:crypto')
const {isDeepStrictEqual} = require('node:util')
const {safeErrorSummary} = require('./logSafety')

function hasCredentials(config) {
  return Boolean(config.openhimUsername && config.openhimPassword)
}

function buildOpenHimOptions(config) {
  return {
    apiURL: config.openhimApiUrl,
    username: config.openhimUsername,
    password: config.openhimPassword,
    urn: config.mediatorUrn,
    trustSelfSigned: config.trustSelfSigned
  }
}

function loadMediatorUtils() {
  return require('openhim-mediator-utils')
}

function extractRuntimeConfig(openhimConfig = {}) {
  const source = openhimConfig.config || openhimConfig
  const result = {}

  for (const [sourceKey, targetKey] of [
    ['standardId', 'standardId'],
    ['standard_id', 'standardId'],
    ['workflowId', 'workflowId'],
    ['workflow_id', 'workflowId'],
    ['sourceSystem', 'sourceSystem'],
    ['source_system', 'sourceSystem'],
    ['adapter', 'adapter'],
    ['parserAdapter', 'adapter'],
    ['parser_adapter', 'adapter']
  ]) {
    if (typeof source[sourceKey] === 'string' && source[sourceKey].trim()) {
      result[targetKey] = source[sourceKey].trim()
    }
  }

  return result
}

function registerMediator(utils, options, mediatorConfig) {
  return new Promise((resolve, reject) => {
    utils.registerMediator(options, mediatorConfig, error => {
      if (error) {
        reject(error)
        return
      }
      resolve()
    })
  })
}

function fetchConfig(utils, options) {
  if (typeof utils.fetchConfig !== 'function') {
    return Promise.resolve(null)
  }

  return new Promise((resolve, reject) => {
    utils.fetchConfig(options, (error, config) => {
      if (error) {
        reject(error)
        return
      }
      resolve(config)
    })
  })
}

function requestJson({options, method = 'GET', path, body}) {
  const baseUrl = options.apiURL.endsWith('/') ? options.apiURL : `${options.apiURL}/`
  const endpoint = new URL(path.replace(/^\//, ''), baseUrl)
  const transport = endpoint.protocol === 'https:' ? https : http
  const payload = body === undefined ? null : JSON.stringify(body)
  const authorization = Buffer.from(`${options.username}:${options.password}`).toString('base64')

  return new Promise((resolve, reject) => {
    const request = transport.request({
      protocol: endpoint.protocol,
      hostname: endpoint.hostname,
      port: endpoint.port,
      path: `${endpoint.pathname}${endpoint.search}`,
      method,
      rejectUnauthorized: !options.trustSelfSigned,
      headers: {
        Accept: 'application/json',
        Authorization: `Basic ${authorization}`,
        ...(payload ? {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(payload)
        } : {})
      }
    }, response => {
      const chunks = []
      response.on('data', chunk => chunks.push(chunk))
      response.on('end', () => {
        const raw = Buffer.concat(chunks).toString('utf8')
        if (response.statusCode < 200 || response.statusCode >= 300) {
          reject(new Error(`OpenHIM API ${method} ${endpoint.pathname} failed with ${response.statusCode}: ${raw}`))
          return
        }

        if (!raw.trim()) {
          resolve(null)
          return
        }

        const trimmed = raw.trim()
        if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
          resolve(raw)
          return
        }

        try {
          resolve(JSON.parse(raw))
        } catch (error) {
          reject(new Error(`OpenHIM API returned invalid JSON for ${endpoint.pathname}: ${error.message}`))
        }
      })
    })

    request.on('error', reject)
    if (payload) {
      request.write(payload)
    }
    request.end()
  })
}

function clientPasswordHash(password, salt) {
  return crypto.createHash('sha512').update(password).update(salt).digest('hex')
}

/*
 * OpenHIM clients represent external systems, not human console users. The
 * password is transformed before it crosses the administration API and only
 * its salted hash is persisted by OpenHIM.
 */
async function ensureInboundClient(
  options,
  config,
  logger = console,
  requester = requestJson
) {
  if (config.inboundAuthType !== 'private') return null

  const roles = Array.isArray(config.inboundAllowedRoles)
    ? config.inboundAllowedRoles.filter(Boolean)
    : []
  if (roles.length === 0) {
    throw new Error('A private OpenHIM channel requires IOL_INBOUND_ALLOWED_ROLES.')
  }

  const clientId = String(config.inboundClientId || '').trim()
  const password = String(config.inboundClientPassword || '')
  if (!clientId && !password) {
    logger.warn(
      'OpenHIM INBOUND channel is private but no client is provisioned; ' +
      'set IOL_INBOUND_CLIENT_ID and IOL_INBOUND_CLIENT_PASSWORD.'
    )
    return {provisioned: false}
  }
  if (!clientId || !password) {
    throw new Error(
      'IOL_INBOUND_CLIENT_ID and IOL_INBOUND_CLIENT_PASSWORD must be configured together.'
    )
  }

  const clients = await requester({options, path: '/clients'})
  const existing = (clients || []).find(client => client.clientID === clientId)
  const mergedRoles = [...new Set([...(existing?.roles || []), ...roles])]
  const currentSalt = existing?.passwordSalt
  const passwordUnchanged = Boolean(
    currentSalt &&
    existing.passwordAlgorithm === 'sha512' &&
    existing.passwordHash === clientPasswordHash(password, currentSalt)
  )
  const salt = passwordUnchanged ? currentSalt : crypto.randomBytes(16).toString('hex')
  const desired = {
    ...(existing || {}),
    clientID: clientId,
    name: String(config.inboundClientName || '').trim() || 'IOL inbound system',
    roles: mergedRoles,
    passwordAlgorithm: 'sha512',
    passwordSalt: salt,
    passwordHash: passwordUnchanged
      ? existing.passwordHash
      : clientPasswordHash(password, salt)
  }
  delete desired._id
  delete desired.__v
  delete desired.customTokenSet

  if (!existing) {
    await requester({options, method: 'POST', path: '/clients', body: desired})
    logger.info(`Provisioned OpenHIM INBOUND client ${clientId}.`)
    return {provisioned: true, created: true}
  }

  const changed = (
    desired.name !== existing.name ||
    !isDeepStrictEqual(mergedRoles, existing.roles || []) ||
    !passwordUnchanged
  )
  if (changed) {
    await requester({
      options,
      method: 'PUT',
      path: `/clients/${encodeURIComponent(existing._id)}`,
      body: desired
    })
    logger.info(`Synchronized OpenHIM INBOUND client ${clientId}.`)
  }
  return {provisioned: true, created: false, changed}
}

function reconciledRoutes(existingRoutes = [], expectedRoutes = []) {
  const used = new Set()
  const reconciled = expectedRoutes.map((expected, index) => {
    let matchingIndex = existingRoutes.findIndex((existing, candidateIndex) =>
      !used.has(candidateIndex) && existing.name === expected.name
    )
    if (matchingIndex < 0 && expected.primary === true) {
      matchingIndex = existingRoutes.findIndex((existing, candidateIndex) =>
        !used.has(candidateIndex) && existing.primary === true
      )
    }
    if (matchingIndex < 0 && existingRoutes[index] && !used.has(index)) {
      matchingIndex = index
    }
    if (matchingIndex < 0) return {...expected}
    used.add(matchingIndex)
    return {...existingRoutes[matchingIndex], ...expected}
  })

  // Secondary routes configured by an administrator are not owned by the
  // mediator and must survive reconciliation of the primary runtime route.
  existingRoutes.forEach((route, index) => {
    if (!used.has(index)) reconciled.push(route)
  })
  return reconciled
}

async function synchronizeDefaultChannelAuthTypes(
  options,
  mediatorConfig,
  logger = console,
  requester = requestJson
) {
  const expectedChannels = mediatorConfig.defaultChannelConfig || []
  if (expectedChannels.length === 0) {
    return []
  }

  const channels = await requester({options, path: '/channels'})
  const updates = []
  const missingChannelNames = expectedChannels
    .filter(expected => !(channels || []).some(channel =>
      channel.name === expected.name || channel.urlPattern === expected.urlPattern
    ))
    .map(channel => channel.name)

  if (missingChannelNames.length > 0) {
    await requester({
      options,
      method: 'POST',
      path: `/mediators/${encodeURIComponent(options.urn)}/channels`,
      body: missingChannelNames
    })
    missingChannelNames.forEach(name => {
      updates.push(name)
      logger.info(`Installed OpenHIM channel ${name}.`)
    })
  }

  for (const expected of expectedChannels) {
    const existing = (channels || []).find(channel =>
      channel.name === expected.name || channel.urlPattern === expected.urlPattern
    )

    if (!existing || !existing._id) {
      continue
    }

    const synchronizedFields = [
      'authType',
      'allow',
      'priority',
      'urlPattern',
      'methods',
      'requestBody',
      'responseBody',
      'txRerunAcl',
      'txViewFullAcl',
      'status'
    ]
    const changedFields = synchronizedFields.filter(
      field => expected[field] !== undefined &&
        !isDeepStrictEqual(existing[field], expected[field])
    )
    const routes = reconciledRoutes(existing.routes || [], expected.routes || [])
    if (expected.routes && !isDeepStrictEqual(existing.routes || [], routes)) {
      changedFields.push('routes')
    }
    if (changedFields.length === 0) continue

    const updated = {
      ...existing
    }
    changedFields.forEach(field => {
      updated[field] = field === 'routes' ? routes : expected[field]
    })
    await requester({
      options,
      method: 'PUT',
      path: `/channels/${encodeURIComponent(existing._id)}`,
      body: updated
    })
    updates.push(existing._id)
    logger.info(
      `Synchronized OpenHIM channel ${existing.name}: ${changedFields.join(', ')}.`
    )
  }

  return updates
}

async function startOpenHimLifecycle({
  config,
  mediatorConfig,
  logger = console,
  utils = loadMediatorUtils(),
  clientProvisioner = ensureInboundClient,
  channelSynchronizer = synchronizeDefaultChannelAuthTypes,
  onConfig = () => {}
}) {
  if (!hasCredentials(config)) {
    logger.warn('OpenHIM credentials are missing; mediator registration and heartbeat skipped.')
    return {
      registered: false,
      heartbeat: null
    }
  }

  const options = buildOpenHimOptions(config)

  await registerMediator(utils, options, mediatorConfig)
  logger.info(`Registered mediator ${config.mediatorUrn} with OpenHIM.`)

  await clientProvisioner(options, config, logger)
  await channelSynchronizer(options, mediatorConfig, logger)

  try {
    const initialConfig = await fetchConfig(utils, options)
    if (initialConfig) {
      onConfig(extractRuntimeConfig(initialConfig))
    }
  } catch (error) {
    logger.warn(
      'Unable to fetch initial OpenHIM mediator config.',
      safeErrorSummary(error)
    )
  }

  const heartbeat = utils.activateHeartbeat(options, config.heartbeatIntervalMs)
  heartbeat.on('config', updatedConfig => {
    logger.info('Received OpenHIM mediator config update.', updatedConfig)
    onConfig(extractRuntimeConfig(updatedConfig))
  })
  heartbeat.on('error', error => {
    logger.error('OpenHIM mediator heartbeat failed.', safeErrorSummary(error))
  })

  return {
    registered: true,
    heartbeat
  }
}

module.exports = {
  buildOpenHimOptions,
  extractRuntimeConfig,
  ensureInboundClient,
  reconciledRoutes,
  requestJson,
  synchronizeDefaultChannelAuthTypes,
  startOpenHimLifecycle
}
