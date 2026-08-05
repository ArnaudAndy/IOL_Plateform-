'use strict'

const assert = require('node:assert/strict')
const EventEmitter = require('node:events')
const test = require('node:test')

const {
  buildOpenHimOptions,
  ensureInboundClient,
  extractRuntimeConfig,
  reconciledRoutes,
  synchronizeDefaultChannelAuthTypes,
  startOpenHimLifecycle
} = require('../src/openhim')

test('buildOpenHimOptions maps mediator config to SDK options', () => {
  const options = buildOpenHimOptions({
    openhimApiUrl: 'https://openhim-core:8080',
    openhimUsername: 'root@openhim.org',
    openhimPassword: 'secret',
    mediatorUrn: 'urn:mediator:iol-generic',
    trustSelfSigned: true
  })

  assert.deepEqual(options, {
    apiURL: 'https://openhim-core:8080',
    username: 'root@openhim.org',
    password: 'secret',
    urn: 'urn:mediator:iol-generic',
    trustSelfSigned: true
  })
})

test('extractRuntimeConfig supports direct and nested OpenHIM config values', () => {
  assert.deepEqual(extractRuntimeConfig({
    config: {
      standardId: 'std_1',
      workflow_id: 'wf_1',
      sourceSystem: 'external',
      parser_adapter: 'fhir-basic'
    }
  }), {
    standardId: 'std_1',
    workflowId: 'wf_1',
    sourceSystem: 'external',
    adapter: 'fhir-basic'
  })
})

test('reconciledRoutes repairs the primary target and preserves secondary routes', () => {
  const routes = reconciledRoutes([
    {
      _id: 'route_1',
      name: 'Old route',
      host: 'localhost',
      port: 80,
      primary: true,
      timeout: 45000
    },
    {name: 'Audit route', host: 'audit', port: 9000, primary: false}
  ], [{
    name: 'IOL Generic Inbound Route',
    host: 'iol-mediator',
    port: 3000,
    path: '/',
    primary: true
  }])

  assert.equal(routes.length, 2)
  assert.equal(routes[0]._id, 'route_1')
  assert.equal(routes[0].host, 'iol-mediator')
  assert.equal(routes[0].port, 3000)
  assert.equal(routes[0].timeout, 45000)
  assert.equal(routes[1].name, 'Audit route')
})

test('synchronizeDefaultChannelAuthTypes preserves channel settings while updating authType', async () => {
  const requests = []
  const existing = {
    _id: 'channel_1',
    name: 'IOL Generic INBOUND',
    urlPattern: '^/interop/.*$',
    authType: 'private',
    allow: ['client_1'],
    routes: [{name: 'Existing route', host: 'iol-mediator'}]
  }
  const requester = async request => {
    requests.push(request)
    return request.method === 'PUT' ? request.body : [existing]
  }

  const updates = await synchronizeDefaultChannelAuthTypes(
    {apiURL: 'https://openhim-core:8080', username: 'user', password: 'secret'},
    {defaultChannelConfig: [{
      name: 'IOL Generic INBOUND',
      urlPattern: '^/interop/.*$',
      authType: 'public',
      allow: [],
      routes: [{
        name: 'IOL Generic Inbound Route',
        host: 'iol-mediator',
        port: 3000,
        primary: true
      }],
      requestBody: false,
      responseBody: false,
      txRerunAcl: [],
      txViewFullAcl: []
    }]},
    {info: () => {}},
    requester
  )

  assert.deepEqual(updates, ['channel_1'])
  assert.equal(requests.length, 2)
  assert.equal(requests[1].method, 'PUT')
  assert.equal(requests[1].path, '/channels/channel_1')
  assert.equal(requests[1].body.authType, 'public')
  assert.equal(requests[1].body.requestBody, false)
  assert.equal(requests[1].body.responseBody, false)
  assert.deepEqual(requests[1].body.allow, [])
  assert.equal(requests[1].body.routes[0].host, 'iol-mediator')
  assert.equal(requests[1].body.routes[0].port, 3000)
})

test('synchronizeDefaultChannelAuthTypes installs a missing default channel', async () => {
  const requests = []
  const requester = async request => {
    requests.push(request)
    return request.method === 'POST' ? null : []
  }

  const updates = await synchronizeDefaultChannelAuthTypes(
    {
      apiURL: 'https://openhim-core:8080',
      username: 'user',
      password: 'secret',
      urn: 'urn:mediator:iol-generic'
    },
    {
      defaultChannelConfig: [{
        name: 'IOL Generic INBOUND',
        urlPattern: '^/interop/.*$',
        authType: 'private'
      }]
    },
    {info: () => {}},
    requester
  )

  assert.deepEqual(updates, ['IOL Generic INBOUND'])
  assert.equal(requests[1].method, 'POST')
  assert.equal(
    requests[1].path,
    '/mediators/urn%3Amediator%3Aiol-generic/channels'
  )
  assert.deepEqual(requests[1].body, ['IOL Generic INBOUND'])
})

test('ensureInboundClient creates a salted client assigned to the allowed role', async () => {
  const requests = []
  const requester = async request => {
    requests.push(request)
    return request.path === '/clients' && request.method !== 'POST' ? [] : null
  }

  const result = await ensureInboundClient(
    {apiURL: 'https://openhim-core:8080', username: 'admin', password: 'secret'},
    {
      inboundAuthType: 'private',
      inboundAllowedRoles: ['iol-inbound'],
      inboundClientId: 'partner-a',
      inboundClientName: 'Partner A',
      inboundClientPassword: 'client-secret'
    },
    {info: () => {}, warn: () => {}},
    requester
  )

  assert.deepEqual(result, {provisioned: true, created: true})
  assert.equal(requests.length, 2)
  assert.equal(requests[1].method, 'POST')
  assert.equal(requests[1].path, '/clients')
  assert.deepEqual(requests[1].body.roles, ['iol-inbound'])
  assert.equal(requests[1].body.passwordAlgorithm, 'sha512')
  assert.notEqual(requests[1].body.passwordHash, 'client-secret')
  assert.equal(requests[1].body.passwordSalt.length, 32)
})

test('startOpenHimLifecycle registers and activates heartbeat with mocked SDK', async () => {
  const heartbeat = new EventEmitter()
  const calls = []
  const updates = []
  const utils = {
    registerMediator: (options, mediatorConfig, callback) => {
      calls.push(['registerMediator', options, mediatorConfig])
      callback()
    },
    fetchConfig: (options, callback) => {
      calls.push(['fetchConfig', options])
      callback(null, {config: {standardId: 'std_1'}})
    },
    activateHeartbeat: (options, interval) => {
      calls.push(['activateHeartbeat', options, interval])
      return heartbeat
    }
  }
  const logger = {
    info: () => {},
    warn: () => {},
    error: () => {}
  }

  const result = await startOpenHimLifecycle({
    config: {
      openhimApiUrl: 'https://openhim-core:8080',
      openhimUsername: 'root@openhim.org',
      openhimPassword: 'secret',
      mediatorUrn: 'urn:mediator:iol-generic',
      trustSelfSigned: true,
      heartbeatIntervalMs: 10000
    },
    mediatorConfig: {urn: 'urn:mediator:iol-generic'},
    logger,
    utils,
    channelSynchronizer: async (options, mediatorConfig) => {
      calls.push(['channelSynchronizer', options, mediatorConfig])
    },
    onConfig: update => updates.push(update)
  })

  assert.equal(result.registered, true)
  assert.equal(result.heartbeat, heartbeat)
  assert.equal(calls.length, 4)
  assert.equal(calls[0][0], 'registerMediator')
  assert.equal(calls[1][0], 'channelSynchronizer')
  assert.equal(calls[2][0], 'fetchConfig')
  assert.equal(calls[3][0], 'activateHeartbeat')
  assert.equal(calls[3][2], 10000)
  assert.deepEqual(updates, [{standardId: 'std_1'}])
})

test('startOpenHimLifecycle skips registration when credentials are missing', async () => {
  const calls = []
  const utils = {
    registerMediator: () => calls.push('registerMediator'),
    activateHeartbeat: () => calls.push('activateHeartbeat')
  }
  const logger = {
    info: () => {},
    warn: () => {},
    error: () => {}
  }

  const result = await startOpenHimLifecycle({
    config: {
      openhimUsername: '',
      openhimPassword: '',
      mediatorUrn: 'urn:mediator:iol-generic'
    },
    mediatorConfig: {urn: 'urn:mediator:iol-generic'},
    logger,
    utils
  })

  assert.equal(result.registered, false)
  assert.equal(result.heartbeat, null)
  assert.deepEqual(calls, [])
})
