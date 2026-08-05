'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')

const {
  createOpenHimChannelPolicy,
  matchingChannel
} = require('../src/openhimChannelPolicy')

test('matchingChannel follows OpenHIM priority for the target path', () => {
  const selected = matchingChannel([
    {
      name: 'fallback',
      urlPattern: '^/interop/.*$',
      priority: 100,
      status: 'enabled'
    },
    {
      name: 'finance',
      urlPattern: '^/interop/finance$',
      priority: 1,
      status: 'enabled',
      methods: ['POST']
    }
  ], 'http://openhim-core:5001/interop/finance')

  assert.equal(selected.name, 'finance')
})

test('policy rejects an OpenHIM channel that can retain the POST body', async () => {
  const policy = createOpenHimChannelPolicy({
    openhimApiUrl: 'https://openhim-core:8080',
    openhimUsername: 'user',
    openhimPassword: 'secret',
    mediatorUrn: 'urn:mediator:iol-generic',
    trustSelfSigned: true
  }, {
    requester: async () => [{
      name: 'unsafe',
      urlPattern: '^/interop/outbound$',
      status: 'enabled',
      requestBody: true,
      responseBody: false
    }]
  })

  await assert.rejects(
    policy.assertNonReplayable(
      'http://openhim-core:5001/interop/outbound'
    ),
    /retains transaction bodies/
  )
})

test('policy accepts only an explicitly body-free OpenHIM channel', async () => {
  const policy = createOpenHimChannelPolicy({
    openhimApiUrl: 'https://openhim-core:8080',
    openhimUsername: 'user',
    openhimPassword: 'secret',
    mediatorUrn: 'urn:mediator:iol-generic',
    trustSelfSigned: true
  }, {
    requester: async () => [{
      name: 'safe',
      urlPattern: '^/interop/outbound$',
      status: 'enabled',
      requestBody: false,
      responseBody: false
    }]
  })

  const channel = await policy.assertNonReplayable(
    'http://openhim-core:5001/interop/outbound'
  )
  assert.equal(channel.name, 'safe')
})
