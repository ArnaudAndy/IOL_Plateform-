'use strict'

const crypto = require('crypto')
const {kafkaClientOptions} = require('./kafkaClientConfig')

function createDlqMessage({req, rawBody, standardId, sourceSystem, correlationId, openhimTransactionId, errors}) {
  return {
    log_id: crypto.randomUUID(),
    source_id: sourceSystem || 'openhim-inbound',
    standard_id: standardId || null,
    correlation_id: correlationId || null,
    openhim_transaction_id: openhimTransactionId || null,
    error_context: {
      step: 'IOL_MEDIATOR_VALIDATION',
      message: errors.map(error => `${error.fieldName}: ${error.message}`).join('; '),
      severity: 'ERROR'
    },
    original_data: {
      method: req.method,
      path: req.url,
      body: rawBody.toString('utf8')
    },
    timestamp: new Date().toISOString()
  }
}

function createNoopDlqPublisher(logger = console) {
  return {
    async publishRejection(message) {
      logger.warn('DLQ publisher disabled; rejection was not sent to Kafka.', message.error_context)
    }
  }
}

function createKafkaDlqPublisher(config, logger = console) {
  if (!config.kafkaBootstrapServers) {
    return createNoopDlqPublisher(logger)
  }

  const {Kafka} = require('kafkajs')
  const kafka = new Kafka(kafkaClientOptions(config, 'iol-mediator'))
  const producer = kafka.producer()
  let connectionPromise = null

  async function producerInstance() {
    if (!connectionPromise) {
      connectionPromise = producer.connect().then(() => producer)
    }
    return connectionPromise
  }

  return {
    async publishRejection(message) {
      const instance = await producerInstance()
      await instance.send({
        topic: config.dlqTopic,
        messages: [
          {
            key: message.correlation_id || message.log_id,
            value: JSON.stringify(message)
          }
        ]
      })
    }
  }
}

module.exports = {
  createDlqMessage,
  createKafkaDlqPublisher,
  createNoopDlqPublisher
}
