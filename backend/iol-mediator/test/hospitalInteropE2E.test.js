'use strict'

const assert = require('node:assert/strict')
const test = require('node:test')

const {normalizeAndValidate} = require('../src/normalizer')
const {processOutboundDelivery} = require('../src/deliveryWorker')

test('hospital_a.patientId becomes pivot patient_id then hospital_b.patientNumber', async () => {
  const terms = [{
    termName: 'patient_id',
    dataType: 'STRING',
    required: true,
    systemMappings: {hospital_a: 'patientId', hospital_b: 'patientNumber'}
  }]
  const inbound = await normalizeAndValidate({
    payload: {patientId: 'P001'},
    standardId: 'custom_patient_v1',
    sourceSystem: 'hospital_a',
    apiClient: {
      async getTerms() { return terms },
      async validateBatch() { return {valid: true, results: [{fieldName: 'patient_id', valid: true}]} }
    }
  })
  assert.deepEqual(inbound.pivot, {patient_id: 'P001'})

  const goldRows = [inbound.pivot]
  let delivered
  await processOutboundDelivery({
    eventType: 'OUTBOUND_DELIVERY_REQUESTED',
    workflowId: 'wf-hospital',
    execLogId: 'exec-hospital',
    targetStandardId: 'custom_patient_v1',
    targetSystem: 'hospital_b',
    targetAdapter: 'generic-json',
    outboundConfig: {destination: {endpointUrl: 'https://hospital-b.example/patients'}},
    pivotRows: goldRows
  }, {
    config: {deliveryTimeoutMs: 1000, outboundAllowedHosts: ['hospital-b.example']},
    apiClient: {
      async denormalizeFromPivot(_standardId, request) {
        assert.equal(request.targetSystem, 'hospital_b')
        return {rows: request.pivotRows.map(row => ({patientNumber: row.patient_id}))}
      }
    },
    async fetchImpl(_url, options) {
      delivered = JSON.parse(options.body)
      return {ok: true, status: 202}
    },
    logger: {info() {}, warn() {}, error() {}}
  })

  assert.deepEqual(delivered, [{patientNumber: 'P001'}])
})
