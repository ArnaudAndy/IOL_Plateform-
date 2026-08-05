'use strict'

function buildMediatorConfig(config) {
  const route = {
    name: 'IOL Generic Inbound Route',
    host: config.mediatorHost,
    port: config.port,
    path: config.mediatorPath,
    primary: true,
    type: 'http',
    secured: config.serverTlsEnabled === true,
    status: 'enabled'
  }

  return {
    urn: config.mediatorUrn,
    version: config.mediatorVersion,
    name: config.mediatorName,
    description: 'Generic metadata-driven IOL mediator for inbound interoperability messages.',
    endpoints: [route],
    defaultChannelConfig: [
      {
        name: 'IOL Generic INBOUND',
        description: 'Routes inbound normalized-standard messages to the IOL generic mediator.',
        urlPattern: '^/interop/.*$',
        type: 'http',
        methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'],
        authType: config.inboundAuthType,
        allow: config.inboundAuthType === 'private'
          ? config.inboundAllowedRoles
          : [],
        routes: [route],
        priority: 100,
        requestBody: false,
        responseBody: false,
        txRerunAcl: [],
        txViewFullAcl: [],
        status: 'enabled'
      }
    ],
    configDefs: [
      {
        param: 'standardId',
        displayName: 'Standard ID',
        description: 'IOL Standard identifier used by the channel. Used from slice 3.2 onward.',
        type: 'string'
      },
      {
        param: 'workflowId',
        displayName: 'Workflow ID',
        description: 'Target workflow for inbound ingestion. Used from slice 3.3 onward.',
        type: 'string'
      },
      {
        param: 'sourceSystem',
        displayName: 'Source system',
        description: 'Key used to select StandardTerm.systemMappings for incoming JSON fields.',
        type: 'string'
      },
      {
        param: 'adapter',
        displayName: 'Inbound adapter',
        description: 'Parser adapter executed before generic StandardTerm validation. Examples: generic-json, fhir-basic.',
        type: 'string'
      },
      {
        param: 'mode',
        displayName: 'Mediator mode',
        description: 'Default processing mode used before channel-specific runtime config is provided.',
        type: 'string'
      }
    ],
    config: {
      standardId: config.defaultStandardId || '',
      workflowId: config.defaultWorkflowId || '',
      sourceSystem: config.defaultSourceSystem || 'generic-json',
      adapter: config.defaultAdapter || 'generic-json',
      mode: 'pass-through'
    }
  }
}

module.exports = {
  buildMediatorConfig
}
