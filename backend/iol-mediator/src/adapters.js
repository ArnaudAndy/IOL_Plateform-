'use strict'

class AdapterError extends Error {
  constructor(message, details = {}) {
    super(message)
    this.name = 'AdapterError'
    this.details = details
  }
}

function identity(payload) {
  return payload
}

function firstIdentifier(resource) {
  const identifiers = Array.isArray(resource.identifier) ? resource.identifier : []
  return identifiers.find(item => item && item.value)?.value || ''
}

function firstCoding(resource) {
  const codings = Array.isArray(resource.code?.coding) ? resource.code.coding : []
  return codings.find(item => item && (item.code || item.display)) || {}
}

function displayName(resource) {
  if (Array.isArray(resource.name) && resource.name.length > 0) {
    const name = resource.name[0]
    if (name.text) return name.text
    const family = name.family || ''
    const given = Array.isArray(name.given) ? name.given.join(' ') : ''
    return [given, family].filter(Boolean).join(' ')
  }
  return ''
}

function unwrapFhirResources(payload) {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) {
    throw new AdapterError('FHIR adapter expects a JSON object')
  }

  if (payload.resourceType === 'Bundle') {
    const resources = Array.isArray(payload.entry)
      ? payload.entry.filter(item => item && item.resource).map(item => item.resource)
      : []
    if (resources.length === 0) {
      throw new AdapterError('FHIR Bundle does not contain a resource entry')
    }
    return resources
  }

  return [payload]
}

function fhirBasic(payload) {
  const resources = unwrapFhirResources(payload)
  const adapted = resources.map(resource => fhirBasicRecord(resource))
  return adapted.length === 1 ? adapted[0] : adapted
}

function fhirBasicRecord(resource) {
  const coding = firstCoding(resource)

  return {
    resourceType: resource.resourceType || '',
    id: resource.id || '',
    identifier: firstIdentifier(resource),
    status: resource.status || '',
    code: coding.code || resource.code?.text || '',
    code_display: coding.display || resource.code?.text || '',
    subject_reference: resource.subject?.reference || '',
    patient_reference: resource.patient?.reference || resource.subject?.reference || '',
    effective_datetime: resource.effectiveDateTime || resource.issued || '',
    birth_date: resource.birthDate || '',
    gender: resource.gender || '',
    name_text: displayName(resource)
  }
}

function rowsFromPayload(payload) {
  if (Array.isArray(payload)) return payload
  if (payload && typeof payload === 'object' && Array.isArray(payload.rows)) return payload.rows
  if (payload && typeof payload === 'object' && Array.isArray(payload.records)) return payload.records
  return payload == null ? [] : [payload]
}

function fhirValue(value) {
  if (value === null || value === undefined) return ''
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function fhirBasicResource(row, index) {
  if (!row || typeof row !== 'object' || Array.isArray(row)) {
    throw new AdapterError('FHIR outbound adapter expects object rows')
  }

  const id = row.id || row.identifier || row.patient_id || row.patientId || `iol-outbound-${index + 1}`
  const extension = Object.entries(row)
    .filter(([key]) => !['resourceType', 'id'].includes(key))
    .map(([key, value]) => ({
      url: `urn:iol:field:${key}`,
      valueString: fhirValue(value)
    }))

  return {
    resourceType: 'Basic',
    id: String(id),
    code: {
      text: 'IOL outbound payload'
    },
    extension
  }
}

function fhirBasicOutbound(payload) {
  const resources = rowsFromPayload(payload).map(fhirBasicResource)
  if (resources.length === 1) return resources[0]

  return {
    resourceType: 'Bundle',
    type: 'collection',
    entry: resources.map(resource => ({resource}))
  }
}

const ADAPTERS = {
  'generic-json': {
    name: 'generic-json',
    parse: identity,
    serialize: identity
  },
  'fhir-basic': {
    name: 'fhir-basic',
    parse: fhirBasic,
    serialize: fhirBasicOutbound
  },
  fhir: {
    name: 'fhir',
    parse: fhirBasic,
    serialize: fhirBasicOutbound
  }
}

function parseInboundPayload(payload, adapterName = 'generic-json') {
  const normalizedName = String(adapterName || 'generic-json').trim().toLowerCase()
  const adapter = ADAPTERS[normalizedName]
  if (!adapter) {
    throw new AdapterError(`Unknown inbound adapter: ${adapterName}`, {adapterName})
  }

  return {
    adapter: adapter.name,
    payload: adapter.parse(payload)
  }
}

function serializeOutboundPayload(denormalized, adapterName = 'generic-json') {
  const normalizedName = String(adapterName || 'generic-json').trim().toLowerCase()
  const adapter = ADAPTERS[normalizedName]
  if (!adapter || typeof adapter.serialize !== 'function') {
    throw new AdapterError(`Unknown outbound adapter: ${adapterName}`, {adapterName})
  }

  return {
    adapter: adapter.name,
    payload: adapter.serialize(denormalized)
  }
}

module.exports = {
  AdapterError,
  parseInboundPayload,
  serializeOutboundPayload
}
