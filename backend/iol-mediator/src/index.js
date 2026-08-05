'use strict'

const {loadConfig} = require('./config')
const {buildMediatorConfig} = require('./mediatorConfig')
const {createServer} = require('./server')
const {startOpenHimLifecycle} = require('./openhim')
const {createApiClient} = require('./apiClient')
const {createKafkaDlqPublisher} = require('./dlqPublisher')
const {createKafkaCommandPublisher} = require('./commandPublisher')
const {createKafkaDeliveryStatusPublisher} = require('./statusPublisher')
const {createOutboundDeliveryWorker} = require('./deliveryWorker')
const {createOpenHimChannelPolicy} = require('./openhimChannelPolicy')
const {safeErrorSummary} = require('./logSafety')

const config = loadConfig()
const readinessState = {
  openhimRegistered: false,
  kafkaReady: false
}
config.readinessState = readinessState
const mediatorConfig = buildMediatorConfig(config)
const runtimeConfig = {
  standardId: config.defaultStandardId,
  workflowId: config.defaultWorkflowId,
  sourceSystem: config.defaultSourceSystem,
  adapter: config.defaultAdapter
}
const apiClient = createApiClient(config)
const dlqPublisher = createKafkaDlqPublisher(config)
const commandPublisher = createKafkaCommandPublisher(config)
const deliveryStatusPublisher = createKafkaDeliveryStatusPublisher(config)
const openhimChannelPolicy = createOpenHimChannelPolicy(config)
config.runtimeConfig = runtimeConfig
config.apiClient = {
  getTerms: standardId => apiClient.getTerms(standardId),
  validateBatch: (standardId, fields) => apiClient.validateBatch(standardId, fields),
  prepareInboundExecution: (standardId, request) => apiClient.prepareInboundExecution(standardId, request),
  prepareInboundExecutionStream: (standardId, metadata, body) =>
    apiClient.prepareInboundExecutionStream(standardId, metadata, body),
  denormalizeFromPivot: (standardId, request) => apiClient.denormalizeFromPivot(standardId, request),
  claimOutboundDelivery: request => apiClient.claimOutboundDelivery(request),
  completeOutboundDelivery: request => apiClient.completeOutboundDelivery(request),
  failOutboundDelivery: request => apiClient.failOutboundDelivery(request)
}
config.dlqPublisher = dlqPublisher
config.commandPublisher = commandPublisher
const outboundDeliveryWorker = createOutboundDeliveryWorker({
  config,
  apiClient: config.apiClient,
  statusPublisher: deliveryStatusPublisher,
  dlqPublisher,
  openhimChannelPolicy,
  logger: console
})
const server = createServer({config})
let openhimRegistrationInProgress = false

// OpenHIM and the mediator start independently in Compose. Retrying here makes
// channel privacy enforcement converge even when Core was unavailable at boot.
async function ensureOpenHimRegistration() {
  if (readinessState.openhimRegistered || openhimRegistrationInProgress) return
  openhimRegistrationInProgress = true
  try {
    const result = await startOpenHimLifecycle({
      config,
      mediatorConfig,
      onConfig: updatedConfig => Object.assign(runtimeConfig, updatedConfig)
    })
    readinessState.openhimRegistered = result.registered === true
  } catch (error) {
    readinessState.openhimRegistered = false
    console.error(
      'OpenHIM registration failed; a retry is scheduled.',
      safeErrorSummary(error)
    )
  } finally {
    openhimRegistrationInProgress = false
  }
}

server.listen(config.port, () => {
  console.log(`IOL mediator listening on port ${config.port}`)

  ensureOpenHimRegistration()

  outboundDeliveryWorker.start()
    .then(() => {
      readinessState.kafkaReady = Boolean(config.kafkaBootstrapServers)
    })
    .catch(error => {
      readinessState.kafkaReady = false
      console.error('OUTBOUND delivery worker failed to start.', safeErrorSummary(error))
    })
})

const openhimRegistrationTimer = setInterval(
  ensureOpenHimRegistration,
  Math.max(5000, config.registrationRetryMs)
)
openhimRegistrationTimer.unref()

process.on('SIGTERM', () => {
  clearInterval(openhimRegistrationTimer)
  outboundDeliveryWorker.stop()
    .finally(() => server.close(() => process.exit(0)))
})
