'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')

const {
  buildPassThroughResponse,
  redactHeaders
} = require('../src/responseBuilder')

const config = {
  mediatorUrn: 'urn:mediator:iol-generic'
}

test('buildPassThroughResponse creates an OpenHIM mediator response', () => {
  const req = {
    method: 'POST',
    url: '/interop/custom/test',
    headers: {
      'content-type': 'application/json',
      authorization: 'Basic secret',
      'x-correlation-id': 'corr-123'
    }
  }

  const response = buildPassThroughResponse(req, Buffer.from('{"id":42}'), config)
  const body = JSON.parse(response.response.body)

  assert.equal(response['x-mediator-urn'], 'urn:mediator:iol-generic')
  assert.equal(response.status, 'Successful')
  assert.equal(response.response.status, 200)
  assert.equal(response.orchestrations.length, 1)
  assert.equal(response.properties.direction, 'INBOUND')
  assert.equal(response.properties.correlationId, 'corr-123')
  assert.deepEqual(body.received, {
    method: 'POST',
    path: '/interop/custom/test'
  })
  assert.equal(response.orchestrations[0].request.headers.authorization, '[redacted]')
  assert.equal(response.orchestrations[0].request.body, '')
  assert.equal(response.orchestrations[0].response.body, '')
})

test('redactHeaders redacts sensitive headers case-insensitively', () => {
  assert.deepEqual(redactHeaders({
    Authorization: 'secret',
    Cookie: 'session',
    Accept: 'application/json'
  }), {
    Authorization: '[redacted]',
    Cookie: '[redacted]',
    Accept: 'application/json'
  })
})
