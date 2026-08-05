'use strict'

class ValidationError extends Error {
  constructor(message, details = {}) {
    super(message)
    this.name = 'ValidationError'
    this.details = details
  }
}

function actualDataType(value) {
  if (value === null || Array.isArray(value) || typeof value === 'object') {
    return 'JSON'
  }
  if (typeof value === 'boolean') {
    return 'BOOLEAN'
  }
  if (typeof value === 'number') {
    return Number.isInteger(value) ? 'INTEGER' : 'DECIMAL'
  }
  return 'STRING'
}

function inferDataTypeForTerm(value, expectedType) {
  const expected = String(expectedType || '').toUpperCase()

  if (expected === 'STRING') {
    return typeof value === 'string' ? 'STRING' : actualDataType(value)
  }
  if (expected === 'INTEGER') {
    return (Number.isInteger(value) || (typeof value === 'string' && /^-?\d+$/.test(value))) ? 'INTEGER' : actualDataType(value)
  }
  if (expected === 'DECIMAL') {
    return ((typeof value === 'number' && Number.isFinite(value)) || (typeof value === 'string' && /^-?\d+(\.\d+)?$/.test(value))) ? 'DECIMAL' : actualDataType(value)
  }
  if (expected === 'BOOLEAN') {
    return (typeof value === 'boolean' || (typeof value === 'string' && /^(true|false)$/i.test(value))) ? 'BOOLEAN' : actualDataType(value)
  }
  if (expected === 'DATE') {
    return (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)) ? 'DATE' : actualDataType(value)
  }
  if (expected === 'DATETIME') {
    return (typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value)) ? 'DATETIME' : actualDataType(value)
  }
  if (expected === 'TIME') {
    return (typeof value === 'string' && /^\d{2}:\d{2}(:\d{2})?$/.test(value)) ? 'TIME' : actualDataType(value)
  }
  if (expected === 'UUID') {
    return (typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)) ? 'UUID' : actualDataType(value)
  }
  if (expected === 'TIMESTAMP') {
    return (Number.isInteger(value) || (typeof value === 'string' && /^\d+$/.test(value))) ? 'TIMESTAMP' : actualDataType(value)
  }
  if (expected === 'JSON') {
    return actualDataType(value) === 'JSON' ? 'JSON' : actualDataType(value)
  }

  return actualDataType(value)
}

function candidateNames(term, sourceSystem) {
  const names = []
  const mappings = term.systemMappings || {}
  if (sourceSystem && mappings[sourceSystem]) {
    names.push(mappings[sourceSystem])
  }
  names.push(term.termName)
  Object.values(mappings).forEach(value => names.push(value))
  return [...new Set(names.filter(Boolean))]
}

function buildFieldIndex(terms, sourceSystem) {
  const index = new Map()
  for (const term of terms) {
    for (const candidate of candidateNames(term, sourceSystem)) {
      if (index.has(candidate) && index.get(candidate).termName !== term.termName) {
        throw new ValidationError(`Mapping ambigu pour le champ ${candidate}`, {
          fieldName: candidate
        })
      }
      index.set(candidate, term)
    }
  }
  return index
}

function presentCandidate(payload, term, sourceSystem) {
  return candidateNames(term, sourceSystem).find(name => Object.prototype.hasOwnProperty.call(payload, name))
}

function inboundRows(payload) {
  if (Array.isArray(payload)) return payload
  if (payload && typeof payload === 'object' && Array.isArray(payload.records)) return payload.records
  if (payload && typeof payload === 'object' && Array.isArray(payload.rows)) return payload.rows
  return payload == null ? [] : [payload]
}

function normalizeRecord({
  payload,
  terms,
  fieldIndex,
  sourceSystem,
  recordIndex,
  includeAllTerms = false
}) {
  if (!payload || Array.isArray(payload) || typeof payload !== 'object') {
    return {
      errors: [{
        recordIndex,
        fieldName: '$',
        message: 'Chaque enregistrement INBOUND doit etre un objet JSON'
      }],
      pivot: {},
      validationFields: []
    }
  }

  const errors = []
  const pivot = includeAllTerms
    ? Object.fromEntries(terms.map(term => [term.termName, null]))
    : {}
  const validationFields = []

  for (const [incomingName, value] of Object.entries(payload)) {
    const term = fieldIndex.get(incomingName)
    if (!term) {
      errors.push({
        recordIndex,
        fieldName: incomingName,
        message: 'Champ non declare dans le standard'
      })
      continue
    }

    pivot[term.termName] = value
    validationFields.push({
      fieldName: term.termName,
      fieldValue: value,
      dataType: inferDataTypeForTerm(value, term.dataType),
      recordIndex
    })
  }

  for (const term of terms) {
    if (term.required === false) {
      continue
    }
    if (!presentCandidate(payload, term, sourceSystem)) {
      errors.push({
        recordIndex,
        fieldName: term.termName,
        message: 'Champ obligatoire manquant'
      })
    }
  }

  return {
    errors,
    pivot,
    validationFields
  }
}

async function normalizeManyAndValidate({
  payload,
  standardId,
  sourceSystem,
  apiClient,
  validationBatchSize = 2000,
  terms: suppliedTerms,
  recordIndexOffset = 0,
  includeAllTerms = false
}) {
  const rows = inboundRows(payload)
  if (rows.length === 0) {
    return {
      valid: false,
      errors: [{recordIndex: 0, fieldName: '$', message: 'Le lot INBOUND est vide'}],
      pivots: [],
      recordCount: 0
    }
  }

  const terms = suppliedTerms || await apiClient.getTerms(standardId)
  const fieldIndex = buildFieldIndex(terms, sourceSystem)
  const errors = []
  const pivots = []
  const validationFields = []

  rows.forEach((row, localRecordIndex) => {
    const recordIndex = recordIndexOffset + localRecordIndex
    const normalized = normalizeRecord({
      payload: row,
      terms,
      fieldIndex,
      sourceSystem,
      recordIndex,
      includeAllTerms
    })
    errors.push(...normalized.errors)
    pivots.push(normalized.pivot)
    validationFields.push(...normalized.validationFields)
  })

  const chunkSize = Math.max(1, Math.min(validationBatchSize, 5000))
  for (let offset = 0; offset < validationFields.length; offset += chunkSize) {
    const chunk = validationFields.slice(offset, offset + chunkSize)
    const batch = await apiClient.validateBatch(
      standardId,
      chunk.map(({recordIndex: _recordIndex, ...field}) => field)
    )
    const results = batch.results || []
    results.forEach((result, index) => {
      if (!result.valid) {
        const source = chunk[index] || {}
        errors.push({
          recordIndex: source.recordIndex,
          fieldName: result.fieldName,
          message: result.message || 'Validation echouee',
          dataType: result.dataType
        })
      }
    })
  }

  return {
    valid: errors.length === 0,
    errors,
    pivots,
    recordCount: rows.length
  }
}

async function normalizeAndValidate(options) {
  const result = await normalizeManyAndValidate(options)
  return {
    valid: result.valid,
    errors: result.errors,
    pivot: result.pivots[0] || {}
  }
}

module.exports = {
  ValidationError,
  buildFieldIndex,
  candidateNames,
  inferDataTypeForTerm,
  inboundRows,
  normalizeManyAndValidate,
  normalizeAndValidate
}
