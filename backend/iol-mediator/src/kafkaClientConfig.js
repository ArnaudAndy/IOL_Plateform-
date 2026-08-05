'use strict'

const fs = require('node:fs')

function readRequiredFile(path, label) {
  if (!path) throw new Error(`${label} is required when Kafka TLS is enabled`)
  return fs.readFileSync(path)
}

function kafkaClientOptions(config, clientId) {
  const options = {
    clientId,
    brokers: config.kafkaBootstrapServers.split(',').map(item => item.trim()).filter(Boolean)
  }
  if (!config.kafkaTlsEnabled) return options

  options.ssl = {
    rejectUnauthorized: true,
    ca: [readRequiredFile(config.kafkaTlsCaFile, 'KAFKA_TLS_CA_FILE')],
    cert: readRequiredFile(config.kafkaTlsCertFile, 'KAFKA_TLS_CERT_FILE'),
    key: readRequiredFile(config.kafkaTlsKeyFile, 'KAFKA_TLS_KEY_FILE')
  }
  return options
}

module.exports = {kafkaClientOptions}
