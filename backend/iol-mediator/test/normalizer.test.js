'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')

const {
  inferDataTypeForTerm,
  normalizeManyAndValidate,
  normalizeAndValidate
} = require('../src/normalizer')

const terms = [
  {
    termName: 'patient_id',
    dataType: 'STRING',
    required: true,
    systemMappings: {external: 'patientId'}
  },
  {
    termName: 'age',
    dataType: 'INTEGER',
    required: false,
    systemMappings: {external: 'patientAge'}
  },
  {
    termName: 'status',
    dataType: 'STRING',
    required: true,
    systemMappings: {external: 'state'}
  }
]

function apiClient(validateResponse) {
  return {
    async getTerms() {
      return terms
    },
    async validateBatch() {
      return validateResponse
    }
  }
}

test('inferDataTypeForTerm respects expected semantic type', () => {
  assert.equal(inferDataTypeForTerm('001', 'STRING'), 'STRING')
  assert.equal(inferDataTypeForTerm('42', 'INTEGER'), 'INTEGER')
  assert.equal(inferDataTypeForTerm('abc', 'INTEGER'), 'STRING')
  assert.equal(inferDataTypeForTerm(42.5, 'DECIMAL'), 'DECIMAL')
  assert.equal(inferDataTypeForTerm(true, 'BOOLEAN'), 'BOOLEAN')
  assert.equal(inferDataTypeForTerm('2026-07-07', 'DATE'), 'DATE')
})

test('normalizeAndValidate maps source fields to pivot term names', async () => {
  const result = await normalizeAndValidate({
    payload: {
      patientId: 'P001',
      patientAge: 34,
      state: 'ACTIVE'
    },
    standardId: 'std_1',
    sourceSystem: 'external',
    apiClient: apiClient({
      valid: true,
      results: [
        {fieldName: 'patient_id', valid: true},
        {fieldName: 'age', valid: true},
        {fieldName: 'status', valid: true}
      ]
    })
  })

  assert.equal(result.valid, true)
  assert.deepEqual(result.pivot, {
    patient_id: 'P001',
    age: 34,
    status: 'ACTIVE'
  })
})

test('normalizeAndValidate rejects unknown and missing required fields', async () => {
  const result = await normalizeAndValidate({
    payload: {
      patientId: 'P001',
      extra: 'nope'
    },
    standardId: 'std_1',
    sourceSystem: 'external',
    apiClient: apiClient({
      valid: true,
      results: [
        {fieldName: 'patient_id', valid: true}
      ]
    })
  })

  assert.equal(result.valid, false)
  assert.ok(result.errors.some(error => error.fieldName === 'extra'))
  assert.ok(result.errors.some(error => error.fieldName === 'status'))
})

test('normalizeAndValidate includes api-core validation failures', async () => {
  const result = await normalizeAndValidate({
    payload: {
      patientId: 'P001',
      state: 'UNKNOWN'
    },
    standardId: 'std_1',
    sourceSystem: 'external',
    apiClient: apiClient({
      valid: false,
      results: [
        {fieldName: 'patient_id', valid: true},
        {fieldName: 'status', valid: false, message: 'hors enum', dataType: 'STRING'}
      ]
    })
  })

  assert.equal(result.valid, false)
  assert.ok(result.errors.some(error => error.fieldName === 'status' && error.message === 'hors enum'))
})

test('normalizeManyAndValidate maps a JSON batch with one terms lookup', async () => {
  let termsCalls = 0
  const client = {
    async getTerms() {
      termsCalls++
      return terms
    },
    async validateBatch(_standardId, fields) {
      return {
        valid: true,
        results: fields.map(field => ({fieldName: field.fieldName, valid: true}))
      }
    }
  }

  const result = await normalizeManyAndValidate({
    payload: [
      {patientId: 'P001', state: 'ACTIVE'},
      {patientId: 'P002', patientAge: 12, state: 'ACTIVE'}
    ],
    standardId: 'std_1',
    sourceSystem: 'external',
    apiClient: client
  })

  assert.equal(result.valid, true)
  assert.equal(result.recordCount, 2)
  assert.equal(termsCalls, 1)
  assert.deepEqual(result.pivots, [
    {patient_id: 'P001', status: 'ACTIVE'},
    {patient_id: 'P002', age: 12, status: 'ACTIVE'}
  ])
})
