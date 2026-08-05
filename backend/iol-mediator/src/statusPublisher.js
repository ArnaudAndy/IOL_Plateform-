'use strict'

const {kafkaClientOptions} = require('./kafkaClientConfig')

function createNoopDeliveryStatusPublisher(logger = console) {
  return {
    async publishDeliveryStatus(message) {
      logger.warn('Delivery status publisher disabled; status was not sent to Kafka.', {
        execLogId: message.execLogId,
        status: message.status
      })
    }
  }
}

function createKafkaDeliveryStatusPublisher(config, logger = console) {
  if (!config.kafkaBootstrapServers) {
    return createNoopDeliveryStatusPublisher(logger)
  }

  const {Kafka} = require('kafkajs')
  const kafka = new Kafka(kafkaClientOptions(config, 'iol-mediator-outbound-status'))
  const producer = kafka.producer()
  let connectionPromise = null

  async function producerInstance() {
    if (!connectionPromise) {
      connectionPromise = producer.connect().then(() => producer)
    }
    return connectionPromise
  }

  return {
    async publishDeliveryStatus(message) {
      const instance = await producerInstance()
      await instance.send({
        topic: config.outboundStatusTopic,
        messages: [
          {
            key: message.correlationId || message.execLogId,
            value: JSON.stringify(message)
          }
        ]
      })
    }
  }
}

module.exports = {
  createKafkaDeliveryStatusPublisher,
  createNoopDeliveryStatusPublisher
}
