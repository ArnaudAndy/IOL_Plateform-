'use strict'

const {kafkaClientOptions} = require('./kafkaClientConfig')

function createNoopCommandPublisher(logger = console) {
  return {
    async publishCommand(preparedExecution) {
      logger.warn('Kafka command publisher disabled; inbound command was not sent.', {
        workflowId: preparedExecution.workflowId,
        execLogId: preparedExecution.execLogId,
        topic: preparedExecution.kafkaTopic
      })
      return {published: false}
    }
  }
}

function createKafkaCommandPublisher(config, logger = console) {
  if (!config.kafkaBootstrapServers) {
    return createNoopCommandPublisher(logger)
  }

  const {Kafka} = require('kafkajs')
  const kafka = new Kafka(kafkaClientOptions(config, 'iol-mediator-command-publisher'))
  const producer = kafka.producer()
  let connectionPromise = null

  async function producerInstance() {
    if (!connectionPromise) {
      connectionPromise = producer.connect().then(() => producer)
    }
    return connectionPromise
  }

  return {
    async publishCommand(preparedExecution) {
      if (!preparedExecution || !preparedExecution.kafkaTopic || !preparedExecution.command) {
        throw new Error('Prepared inbound execution is incomplete.')
      }

      const instance = await producerInstance()
      await instance.send({
        topic: preparedExecution.kafkaTopic,
        messages: [
          {
            key: preparedExecution.kafkaKey || preparedExecution.workflowId,
            value: JSON.stringify(preparedExecution.command)
          }
        ]
      })
      return {published: true}
    }
  }
}

module.exports = {
  createKafkaCommandPublisher,
  createNoopCommandPublisher
}
