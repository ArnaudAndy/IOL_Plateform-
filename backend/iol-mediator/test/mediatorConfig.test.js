'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')

const {buildMediatorConfig} = require('../src/mediatorConfig')

test('buildMediatorConfig declares every default config parameter', () => {
  const mediatorConfig = buildMediatorConfig({
    mediatorHost: 'iol-mediator',
    mediatorPath: '/',
    mediatorUrn: 'urn:mediator:iol-generic',
    mediatorName: 'IOL Generic Interop Mediator',
    mediatorVersion: '0.1.0',
    port: 3000
  })

  const declaredParams = new Set(mediatorConfig.configDefs.map(def => def.param))

  for (const param of Object.keys(mediatorConfig.config)) {
    assert.ok(declaredParams.has(param), `${param} must be declared in configDefs`)
  }
})

test('default channel cannot retain or rerun sensitive payloads', () => {
  const mediatorConfig = buildMediatorConfig({
    mediatorHost: 'iol-mediator',
    mediatorPath: '/',
    mediatorUrn: 'urn:mediator:iol-generic',
    mediatorName: 'IOL Generic Interop Mediator',
    mediatorVersion: '0.2.0',
    inboundAuthType: 'private',
    port: 3000
  })
  const channel = mediatorConfig.defaultChannelConfig[0]

  assert.equal(channel.requestBody, false)
  assert.equal(channel.responseBody, false)
  assert.deepEqual(channel.txRerunAcl, [])
  assert.deepEqual(channel.txViewFullAcl, [])
})
