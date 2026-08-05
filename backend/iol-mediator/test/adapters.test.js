'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')

const {AdapterError, parseInboundPayload, serializeOutboundPayload} = require('../src/adapters')

test('generic-json adapter returns payload unchanged', () => {
  const payload = {patientId: 'P001'}
  const result = parseInboundPayload(payload, 'generic-json')

  assert.equal(result.adapter, 'generic-json')
  assert.equal(result.payload, payload)
})

test('fhir-basic adapter flattens a patient resource for StandardTerm mappings', () => {
  const result = parseInboundPayload({
    resourceType: 'Patient',
    id: 'pat-1',
    identifier: [{system: 'MRN', value: 'P001'}],
    name: [{given: ['Ada'], family: 'Lovelace'}],
    gender: 'female',
    birthDate: '1815-12-10'
  }, 'fhir-basic')

  assert.equal(result.adapter, 'fhir-basic')
  assert.deepEqual(result.payload, {
    resourceType: 'Patient',
    id: 'pat-1',
    identifier: 'P001',
    status: '',
    code: '',
    code_display: '',
    subject_reference: '',
    patient_reference: '',
    effective_datetime: '',
    birth_date: '1815-12-10',
    gender: 'female',
    name_text: 'Ada Lovelace'
  })
})

test('fhir-basic adapter preserves every resource in a Bundle', () => {
  const result = parseInboundPayload({
    resourceType: 'Bundle',
    type: 'batch',
    entry: [
      {resource: {resourceType: 'Patient', id: 'pat-1'}},
      {resource: {resourceType: 'Observation', id: 'obs-1', status: 'final'}}
    ]
  }, 'fhir-basic')

  assert.equal(result.payload.length, 2)
  assert.equal(result.payload[0].resourceType, 'Patient')
  assert.equal(result.payload[1].resourceType, 'Observation')
  assert.equal(result.payload[1].status, 'final')
})

test('unknown adapter fails fast', () => {
  assert.throws(
    () => parseInboundPayload({}, 'iso20022-future'),
    AdapterError
  )
})

test('generic-json outbound adapter returns denormalized rows unchanged', () => {
  const rows = [{patientId: 'P001', name: 'Ada'}]
  const result = serializeOutboundPayload(rows, 'generic-json')

  assert.equal(result.adapter, 'generic-json')
  assert.equal(result.payload, rows)
})

test('fhir outbound adapter serializes rows as a Bundle of Basic resources', () => {
  const result = serializeOutboundPayload([
    {identifier: 'P001', name_text: 'Ada Lovelace'},
    {identifier: 'P002', name_text: 'Grace Hopper'}
  ], 'fhir')

  assert.equal(result.adapter, 'fhir')
  assert.equal(result.payload.resourceType, 'Bundle')
  assert.equal(result.payload.entry.length, 2)
  assert.equal(result.payload.entry[0].resource.resourceType, 'Basic')
  assert.equal(result.payload.entry[0].resource.id, 'P001')
  assert.deepEqual(result.payload.entry[0].resource.extension[0], {
    url: 'urn:iol:field:identifier',
    valueString: 'P001'
  })
})

test('unknown outbound adapter fails fast', () => {
  assert.throws(
    () => serializeOutboundPayload([], 'future-outbound'),
    AdapterError
  )
})
