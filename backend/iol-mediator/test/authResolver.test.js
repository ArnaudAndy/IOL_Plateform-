'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')

const {resolveAuthHeaders} = require('../src/authResolver')

test('resolves bearer secret without exposing its reference', async () => {
  const headers = await resolveAuthHeaders({
    auth: {type: 'BEARER', secretRef: 'hospital-b-token'}
  }, {
    config: {outboundAuthSecrets: {'hospital-b-token': 'actual-token'}}
  })
  assert.deepEqual(headers, {Authorization: 'Bearer actual-token'})
})

test('resolves basic credentials from environment references', async () => {
  const headers = await resolveAuthHeaders({
    auth: {type: 'BASIC', usernameRef: 'PARTNER_USER', passwordRef: 'PARTNER_PASSWORD'}
  }, {
    env: {PARTNER_USER: 'alice', PARTNER_PASSWORD: 'secret'}
  })
  assert.equal(headers.Authorization, `Basic ${Buffer.from('alice:secret').toString('base64')}`)
})
