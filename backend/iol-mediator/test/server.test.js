'use strict'

const assert = require('node:assert/strict')
const http = require('node:http')
const test = require('node:test')

const {createServer} = require('../src/server')

function request({port, method = 'GET', path = '/', headers = {}, body = ''}) {
  return new Promise((resolve, reject) => {
    const req = http.request({
      host: '127.0.0.1',
      port,
      path,
      method,
      headers: method === 'POST'
        ? {'Idempotency-Key': 'test-request-key', ...headers}
        : headers
    }, res => {
      const chunks = []
      res.on('data', chunk => chunks.push(chunk))
      res.on('end', () => resolve({
        statusCode: res.statusCode,
        headers: res.headers,
        body: Buffer.concat(chunks).toString('utf8')
      }))
    })

    req.on('error', reject)
    if (body) {
      req.write(body)
    }
    req.end()
  })
}

function listen(server) {
  return new Promise(resolve => {
    server.listen(0, '127.0.0.1', () => resolve(server.address().port))
  })
}

test('health endpoint returns UP', async () => {
  const server = createServer({
    config: {mediatorUrn: 'urn:mediator:iol-generic'}
  })
  const port = await listen(server)

  try {
    const res = await request({port, path: '/health'})
    assert.equal(res.statusCode, 200)
    assert.equal(JSON.parse(res.body).status, 'UP')
  } finally {
    server.close()
  }
})

test('readiness endpoint reflects OpenHIM and Kafka state', async () => {
  const readinessState = {openhimRegistered: false, kafkaReady: true}
  const server = createServer({
    config: {mediatorUrn: 'urn:mediator:iol-generic', readinessState}
  })
  const port = await listen(server)

  try {
    const unavailable = await request({port, path: '/ready'})
    assert.equal(unavailable.statusCode, 503)
    assert.equal(JSON.parse(unavailable.body).checks.openhim, false)

    readinessState.openhimRegistered = true
    const ready = await request({port, path: '/ready'})
    assert.equal(ready.statusCode, 200)
    assert.equal(JSON.parse(ready.body).status, 'UP')
  } finally {
    server.close()
  }
})

test('non-health requests return OpenHIM mediator response', async () => {
  const server = createServer({
    config: {mediatorUrn: 'urn:mediator:iol-generic'}
  })
  const port = await listen(server)

  try {
    const res = await request({
      port,
      method: 'POST',
      path: '/interop/custom/test',
      headers: {
        'content-type': 'application/json',
        'x-correlation-id': 'corr-456'
      },
      body: '{"hello":"world"}'
    })
    const payload = JSON.parse(res.body)

    assert.equal(res.statusCode, 200)
    assert.match(res.headers['content-type'], /application\/json\+openhim/)
    assert.equal(payload.status, 'Successful')
    assert.equal(payload.properties.correlationId, 'corr-456')
  } finally {
    server.close()
  }
})

test('configured standard validates data without echoing the pivot', async () => {
  const publishedCommands = []
  const prepareRequests = []
  const server = createServer({
    config: {
      mediatorUrn: 'urn:mediator:iol-generic',
      defaultStandardId: 'std_1',
      defaultSourceSystem: 'external',
      apiClient: {
        async getTerms() {
          return [{
            termName: 'patient_id',
            dataType: 'STRING',
            required: true,
            systemMappings: {external: 'patientId'}
          }]
        },
        async validateBatch(_standardId, fields) {
          return {
            valid: true,
            results: fields.map(field => ({
              fieldName: field.fieldName,
              dataType: field.dataType,
              valid: true
            }))
          }
        },
        async prepareInboundExecution(_standardId, request) {
          prepareRequests.push(request)
          return {
            workflowId: 'wf_1',
            execLogId: 'log_1',
            kafkaTopic: 'iol.pipeline.commands',
            kafkaKey: 'wf_1',
            recordCount: 1,
            dataTransport: 'KAFKA_INLINE_JSON',
            commandPublished: false,
            command: {
              eventType: 'PIPELINE_EXECUTION_REQUESTED',
              workflowId: 'wf_1'
            }
          }
        }
      },
      commandPublisher: {
        async publishCommand(prepared) {
          publishedCommands.push(prepared)
        }
      }
    }
  })
  const port = await listen(server)

  try {
    const res = await request({
      port,
      method: 'POST',
      path: '/interop/custom/test',
      headers: {
        'content-type': 'application/json',
        'x-correlation-id': 'corr-789',
        'x-openhim-transactionid': 'tx-openhim-789'
      },
      body: '{"patientId":"P001"}'
    })
    const payload = JSON.parse(res.body)
    const responseBody = JSON.parse(payload.response.body)

    assert.equal(res.statusCode, 200)
    assert.equal(payload.status, 'Successful')
    assert.equal(payload.properties.validation, 'passed')
    assert.equal('pivot' in responseBody, false)
    assert.equal(responseBody.handoff.execLogId, 'log_1')
    assert.equal(responseBody.openhimTransactionId, 'tx-openhim-789')
    assert.equal(payload.properties.workflowId, 'wf_1')
    assert.equal(payload.properties.openhimTransactionId, 'tx-openhim-789')
    assert.equal(prepareRequests[0].openhimTransactionId, 'tx-openhim-789')
    assert.equal(prepareRequests[0].idempotencyKey, 'test-request-key')
    assert.match(prepareRequests[0].payloadHash, /^[0-9a-f]{64}$/)
    assert.deepEqual(prepareRequests[0].pivots, [{patient_id: 'P001'}])
    assert.equal(publishedCommands.length, 1)
    assert.equal(publishedCommands[0].kafkaTopic, 'iol.pipeline.commands')
  } finally {
    server.close()
  }
})

test('NDJSON input is validated and handed off progressively', async () => {
  const streamedBodies = []
  const streamMetadata = []
  let bufferedHandOffs = 0
  const server = createServer({
    config: {
      mediatorUrn: 'urn:mediator:iol-generic',
      defaultStandardId: 'std_stream',
      defaultSourceSystem: 'external',
      streamBatchRows: 1,
      maxStreamBytes: 1024 * 1024,
      maxNdjsonLineBytes: 64 * 1024,
      validationBatchSize: 100,
      apiClient: {
        async getTerms() {
          return [
            {
              termName: 'student_id',
              dataType: 'STRING',
              required: true,
              systemMappings: {external: 'studentId'}
            },
            {
              termName: 'grade',
              dataType: 'INTEGER',
              required: false,
              systemMappings: {external: 'grade'}
            }
          ]
        },
        async validateBatch(_standardId, fields) {
          return {
            valid: true,
            results: fields.map(field => ({
              fieldName: field.fieldName,
              dataType: field.dataType,
              valid: true
            }))
          }
        },
        async prepareInboundExecution() {
          bufferedHandOffs += 1
          throw new Error('buffered hand-off must not be used')
        },
        async prepareInboundExecutionStream(_standardId, metadata, body) {
          streamMetadata.push(metadata)
          const chunks = []
          for await (const chunk of body) chunks.push(Buffer.from(chunk))
          streamedBodies.push(Buffer.concat(chunks).toString('utf8'))
          return {
            workflowId: 'wf_stream',
            execLogId: 'log_stream',
            kafkaTopic: 'iol.pipeline.commands',
            kafkaKey: 'iol-default:wf_stream',
            organizationId: 'iol-default',
            recordCount: 2,
            dataTransport: 'KAFKA_ROW_BATCH',
            commandPublished: true
          }
        }
      }
    }
  })
  const port = await listen(server)

  try {
    const body = '{"studentId":"S001","grade":5}\n{"studentId":"S002"}\n'
    const res = await request({
      port,
      method: 'POST',
      path: '/interop/edfi/students',
      headers: {
        'content-type': 'application/x-ndjson',
        'content-length': Buffer.byteLength(body),
        'x-correlation-id': 'corr-stream'
      },
      body
    })
    const payload = JSON.parse(res.body)
    const responseBody = JSON.parse(payload.response.body)

    assert.equal(res.statusCode, 200)
    assert.equal(payload.status, 'Successful')
    assert.equal(responseBody.recordCount, 2)
    assert.equal(responseBody.handoff.execLogId, 'log_stream')
    assert.equal(bufferedHandOffs, 0)
    assert.equal(streamMetadata[0].estimatedBytes, Buffer.byteLength(body))
    assert.equal(streamMetadata[0].idempotencyKey, 'test-request-key')
    assert.deepEqual(
      streamedBodies[0].trim().split('\n').map(line => JSON.parse(line)),
      [
        {student_id: 'S001', grade: 5},
        {student_id: 'S002', grade: null}
      ]
    )
  } finally {
    server.close()
  }
})

test('an invalid NDJSON line fails validation without publishing a command', async () => {
  const dlqMessages = []
  let streamHandOffs = 0
  const server = createServer({
    config: {
      mediatorUrn: 'urn:mediator:iol-generic',
      defaultStandardId: 'std_stream',
      defaultSourceSystem: 'external',
      streamBatchRows: 1,
      maxStreamBytes: 1024 * 1024,
      maxNdjsonLineBytes: 64 * 1024,
      validationBatchSize: 100,
      apiClient: {
        async getTerms() {
          return [{
            termName: 'student_id',
            dataType: 'STRING',
            required: true,
            systemMappings: {external: 'studentId'}
          }]
        },
        async validateBatch(_standardId, fields) {
          return {
            valid: true,
            results: fields.map(field => ({...field, valid: true}))
          }
        },
        async prepareInboundExecutionStream(_standardId, _metadata, body) {
          streamHandOffs += 1
          for await (const _chunk of body) {
            // Consume the progressive request exactly as api-core does.
          }
          throw new Error('validation should abort this stream')
        }
      },
      dlqPublisher: {
        async publishRejection(message) {
          dlqMessages.push(message)
        }
      }
    }
  })
  const port = await listen(server)

  try {
    const res = await request({
      port,
      method: 'POST',
      path: '/interop/edfi/students',
      headers: {'content-type': 'application/x-ndjson'},
      body: '{"studentId":"S001"}\n{bad-json}\n'
    })
    const payload = JSON.parse(res.body)

    assert.equal(payload.status, 'Failed')
    assert.equal(payload.response.status, 400)
    assert.equal(streamHandOffs, 1)
    assert.equal(dlqMessages.length, 1)
    assert.match(dlqMessages[0].error_context.message, /JSON invalide/)
    assert.match(dlqMessages[0].original_data.body, /payload omitted/)
  } finally {
    server.close()
  }
})

test('validation failures are returned and sent to DLQ publisher', async () => {
  const dlqMessages = []
  const server = createServer({
    config: {
      mediatorUrn: 'urn:mediator:iol-generic',
      defaultStandardId: 'std_1',
      defaultSourceSystem: 'external',
      apiClient: {
        async getTerms() {
          return [{
            termName: 'age',
            dataType: 'INTEGER',
            required: true,
            systemMappings: {external: 'age'}
          }]
        },
        async validateBatch(_standardId, fields) {
          return {
            valid: false,
            results: fields.map(field => ({
              fieldName: field.fieldName,
              dataType: field.dataType,
              valid: false,
              message: 'La valeur ne respecte pas les regles du standard'
            }))
          }
        }
      },
      dlqPublisher: {
        async publishRejection(message) {
          dlqMessages.push(message)
        }
      }
    }
  })
  const port = await listen(server)

  try {
    const res = await request({
      port,
      method: 'POST',
      path: '/interop/custom/test',
      headers: {
        'content-type': 'application/json',
        'x-correlation-id': 'corr-dlq',
        'x-openhim-transaction-id': 'tx-openhim-dlq'
      },
      body: '{"age":"bad"}'
    })
    const payload = JSON.parse(res.body)

    assert.equal(res.statusCode, 200)
    assert.equal(payload.status, 'Failed')
    assert.equal(payload.response.status, 400)
    assert.equal(payload.properties.validation, 'failed')
    assert.equal(dlqMessages.length, 1)
    assert.equal(dlqMessages[0].correlation_id, 'corr-dlq')
    assert.equal(dlqMessages[0].openhim_transaction_id, 'tx-openhim-dlq')
    assert.equal(dlqMessages[0].error_context.step, 'IOL_MEDIATOR_VALIDATION')
  } finally {
    server.close()
  }
})
