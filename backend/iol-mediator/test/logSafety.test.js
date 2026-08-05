'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')

const {safeErrorSummary} = require('../src/logSafety')

test('safeErrorSummary keeps diagnostics without nested HTTP request secrets', () => {
  const error = new Error('request failed Authorization: Basic dXNlcjpzZWNyZXQ=')
  error.code = 'ECONNREFUSED'
  error.config = {
    headers: {Authorization: 'Basic dXNlcjpzZWNyZXQ='},
    data: {patient: 'sensitive'}
  }

  const summary = safeErrorSummary(error)

  assert.deepEqual(Object.keys(summary), ['name', 'code', 'message'])
  assert.equal(summary.code, 'ECONNREFUSED')
  assert.match(summary.message, /\[REDACTED\]/)
  assert.doesNotMatch(JSON.stringify(summary), /dXNlcjpzZWNyZXQ|patient|sensitive/)
})
