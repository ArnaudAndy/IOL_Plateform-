'use strict'

/*
 * Executed inside the generic mediator container by
 * run-openhim-privacy-smoke.ps1. It uses only synthetic data and removes its
 * temporary OpenHIM client even when routing fails.
 */
const crypto = require('node:crypto')
const {
  buildOpenHimOptions,
  ensureInboundClient,
  requestJson
} = require('/app/src/openhim')

const clientId = `iol-privacy-audit-${Date.now()}`
const clientPassword = crypto.randomBytes(32).toString('base64url')
const auditTimestamp = Date.now()
const probes = [
  {
    name: 'generic',
    path: `/interop/privacy-audit-${auditTimestamp}`,
    contentType: 'application/json',
    body: JSON.stringify({syntheticValue: 'must-never-be-persisted'})
  },
  {
    name: 'fhir-r4',
    path: `/interop/fhir/privacy-audit-${auditTimestamp}`,
    contentType: 'application/fhir+json',
    body: JSON.stringify({syntheticValue: 'must-never-be-persisted'})
  },
  {
    name: 'iso-20022',
    path: `/interop/iso20022/privacy-audit-${auditTimestamp}`,
    contentType: 'application/xml',
    body: '<PrivacyAudit>must-never-be-persisted</PrivacyAudit>'
  },
  {
    name: 'ed-fi',
    path: `/interop/edfi/privacy-audit-${auditTimestamp}`,
    contentType: 'application/x-ndjson',
    // Keep the privacy marker while forcing structural rejection before any
    // downstream workflow can receive a synthetic Ed-Fi resource.
    body: '{"syntheticValue":"must-never-be-persisted"\n'
  }
]
const config = {
  openhimApiUrl: process.env.OPENHIM_API_URL,
  openhimUsername: process.env.OPENHIM_USERNAME,
  openhimPassword: process.env.OPENHIM_PASSWORD,
  trustSelfSigned: true,
  mediatorUrn: process.env.MEDIATOR_URN,
  inboundAuthType: 'private',
  inboundAllowedRoles: ['iol-inbound'],
  inboundClientId: clientId,
  inboundClientName: 'Ephemeral IOL privacy audit',
  inboundClientPassword: clientPassword
}
const options = buildOpenHimOptions(config)

async function main() {
  let client = null
  let result = null
  try {
    await ensureInboundClient(options, config, {info() {}, warn() {}})
    const clients = await requestJson({options, path: '/clients'})
    client = clients.find(candidate => candidate.clientID === clientId)

    const authorization = `Basic ${Buffer.from(`${clientId}:${clientPassword}`).toString('base64')}`
    const probeResults = []
    for (const probe of probes) {
      const response = await fetch(`http://openhim-core:5001${probe.path}`, {
        method: 'POST',
        headers: {
          Authorization: authorization,
          'Content-Type': probe.contentType,
          'Idempotency-Key': `privacy-audit-${probe.name}-${auditTimestamp}`
        },
        body: probe.body
      })
      await response.text()
      const controlledApplicationResponse = [400, 409, 415, 422, 428]
        .includes(response.status)
      probeResults.push({
        name: probe.name,
        path: probe.path,
        httpStatus: response.status,
        businessAccepted: response.status >= 200 && response.status < 300,
        routed: (response.status >= 200 && response.status < 300) ||
          controlledApplicationResponse
      })
    }
    result = {
      routed: probeResults.every(probe => probe.routed),
      probes: probeResults
    }
  } finally {
    if (client) {
      await requestJson({
        options,
        method: 'DELETE',
        path: `/clients/${encodeURIComponent(client._id)}`
      })
    }
  }

  process.stdout.write(JSON.stringify(result))
  if (!result.routed) process.exitCode = 2
}

main().catch(error => {
  console.error(error.message)
  process.exit(1)
})
