import type {
  FieldMappingWire,
  GoldConfigGlobal,
  SourceField,
  WorkflowConfigUi,
  WorkflowConfigWire,
  WorkflowLoadMode,
  WorkflowOutboundConfig,
  WorkflowSourceConfigWire,
  WorkflowSourceUi,
  WorkflowSourceWire,
  WorkflowWriteMode,
} from './types'

type LooseRecord = Record<string, unknown>

function isRecord(value: unknown): value is LooseRecord {
  return !!value && typeof value === 'object' && !Array.isArray(value)
}

function asString(value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined
  const text = String(value).trim()
  return text || undefined
}

function firstText(...values: unknown[]): string | undefined {
  for (const value of values) {
    const text = asString(value)
    if (text) return text
  }
  return undefined
}

function compactRecord<T extends object>(record: T): Partial<T> {
  const compacted: LooseRecord = {}

  Object.entries(record as LooseRecord).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return
    if (Array.isArray(value) && value.length === 0) return
    if (isRecord(value) && Object.keys(value).length === 0) return
    compacted[key] = value
  })

  return compacted as Partial<T>
}

function hasValues(record: unknown): boolean {
  return isRecord(record) && Object.keys(compactRecord(record)).length > 0
}

function normalizeLoadMode(value: unknown): WorkflowLoadMode {
  return String(value || 'FULL').toUpperCase() === 'INCREMENTAL' ? 'INCREMENTAL' : 'FULL'
}

function normalizeWriteMode(value: unknown): WorkflowWriteMode {
  return String(value || 'append').toLowerCase() === 'replace' ? 'replace' : 'append'
}

function selectedProjectionFields(fields?: SourceField[]): string[] | undefined {
  if (!fields || fields.length === 0) return undefined

  const seen = new Set<string>()
  const selected: string[] = []

  fields.forEach((field) => {
    if (field.selected !== true) return
    const column = firstText(field.originalName, field.name)
    if (!column || seen.has(column)) return
    seen.add(column)
    selected.push(column)
  })

  return selected.length > 0 ? selected : undefined
}

function fieldMappingsFromUi(sources: WorkflowSourceUi[]): FieldMappingWire[] | undefined {
  const mappings: FieldMappingWire[] = []

  sources.forEach((source) => {
    ;(source.fields || []).forEach((field) => {
      if (field.selected !== true || !field.semanticTerm) return

      const sourceField = firstText(field.originalName, field.name)
      if (!sourceField) return

      const mapping: FieldMappingWire = {
        sourceName: source.source_name,
        type: field.type,
        iolTerm: field.semanticTerm,
        mappingType: 'DIRECT',
        sourceFields: [sourceField],
      }

      if (field.alias) {
        mapping.cleaningRules = { alias: field.alias }
      }

      mappings.push(compactRecord(mapping) as FieldMappingWire)
    })
  })

  return mappings.length > 0 ? mappings : undefined
}

function rootDestinationConnectionId(ui: WorkflowConfigUi): string | undefined {
  return firstText(
    ui.destinationConnectionId,
    ...(ui.sources || []).map((source) => source.connectionId),
  )
}

function buildTargetConnection(source: WorkflowSourceUi, targetTable?: string): Record<string, unknown> | undefined {
  if (!isRecord(source.connectionDetails)) return undefined

  return compactRecord({
    ...source.connectionDetails,
    target_table: targetTable,
  })
}

function toBackendSource(source: WorkflowSourceUi, destinationConnectionId?: string): WorkflowSourceWire {
  const preservedConfig = isRecord(source.config) ? { ...source.config } : {}
  delete preservedConfig.gold_config
  delete preservedConfig.last_watermark

  const sourceConfig = compactRecord({
    ...(isRecord(source.source_config) ? source.source_config : {}),
  })
  const targetTable = firstText(source.target_table, preservedConfig.target_table)
  const targetConnection = destinationConnectionId
    ? undefined
    : buildTargetConnection(source, targetTable)

  const config: WorkflowSourceConfigWire = {
    ...preservedConfig,
    source_connection_id: firstText(source.sourceConnectionId, preservedConfig.source_connection_id),
    upload_id: firstText(source.upload_id, preservedConfig.upload_id),
    file_path: firstText(source.file_path, preservedConfig.file_path),
    uri: firstText(source.uri, source.file_path, preservedConfig.uri),
    target_table: targetTable,
    target_connection: targetConnection,
    source_config: hasValues(sourceConfig) ? sourceConfig : undefined,
    load_mode: normalizeLoadMode(source.load_mode || preservedConfig.load_mode),
    write_mode: normalizeWriteMode(source.write_mode || preservedConfig.write_mode),
    jdbc_partitioning_enabled: source.jdbc_partitioning_enabled === true,
    partition_column: source.jdbc_partitioning_enabled ? firstText(source.partition_column) : undefined,
    partition_type: source.jdbc_partitioning_enabled ? (source.partition_type || 'NUMERIC') : undefined,
    partition_lower_bound: source.jdbc_partitioning_enabled ? firstText(source.partition_lower_bound) : undefined,
    partition_upper_bound: source.jdbc_partitioning_enabled ? firstText(source.partition_upper_bound) : undefined,
    partition_count: source.jdbc_partitioning_enabled ? (source.partition_count || 4) : undefined,
    partition_parallelism: source.jdbc_partitioning_enabled ? (source.partition_parallelism || 4) : undefined,
    jdbc_fetch_size: source.jdbc_fetch_size || 1000,
    spark_write_partitions: source.spark_write_partitions || 4,
    bulk_load_strategy: source.bulk_load_strategy || 'AUTO',
    transport_mode: source.transport_mode || 'AUTO',
    fields: selectedProjectionFields(source.fields),
    silver_config: hasValues(source.silver_config) ? compactRecord(source.silver_config || {}) : undefined,
  }

  if (config.load_mode === 'INCREMENTAL') {
    config.incremental_column = firstText(source.incremental_column, preservedConfig.incremental_column)
  } else {
    delete config.incremental_column
  }

  delete config.last_watermark

  return {
    source_name: firstText(source.source_name, 'UNKNOWN')!,
    config: compactRecord(config) as WorkflowSourceConfigWire,
  }
}

export function toBackendWorkflow(ui: WorkflowConfigUi): WorkflowConfigWire {
  const destinationConnectionId = rootDestinationConnectionId(ui)
  const sources = (ui.sources || []).map((source) => toBackendSource(source, destinationConnectionId))
  const gold = compactRecord((ui.goldConfigGlobal || ui.gold_config_global || {}) as LooseRecord) as GoldConfigGlobal
  const outboundConfig = compactRecord((ui.outboundConfig || {}) as LooseRecord) as WorkflowOutboundConfig
  const fieldMappings = fieldMappingsFromUi(ui.sources || [])
  const protocol = firstText(ui.protocol, sources[0]?.source_name, ui.sources?.[0]?.source_name)

  const wire: WorkflowConfigWire = {
    id: ui.id,
    workflowName: firstText(ui.workflowName, ui.name) || '',
    description: ui.description,
    protocol: protocol || '',
    standardDomain: ui.standardDomain,
    standardId: ui.standardId,
    direction: ui.direction || 'INTERNAL',
    outboundConfig: hasValues(outboundConfig) ? outboundConfig : undefined,
    metadataFileName: ui.metadataFileName,
    metadataJson: ui.metadataJson,
    metadataVersion: ui.metadataVersion,
    schedule: ui.schedule,
    sources,
    gold_config_global: hasValues(gold) ? gold : undefined,
    destinationConnectionId,
    sourceConfig: hasValues(ui.sourceConfig) ? ui.sourceConfig : undefined,
    fields: fieldMappings || ui.fields,
    aggregationScripts: firstText(ui.aggregationScripts, gold.elt_scripts_gold),
    priority: ui.priority,
    estimatedRows: ui.estimatedRows,
    isActive: ui.isActive ?? ui.active,
    createdBy: ui.createdBy,
  }

  return compactRecord(wire) as unknown as WorkflowConfigWire
}

function sourceFieldsFromConfig(rawFields: unknown): SourceField[] {
  if (!Array.isArray(rawFields)) return []

  return rawFields
    .map((raw) => {
      if (typeof raw === 'string') {
        return { name: raw, originalName: raw, selected: true }
      }

      if (!isRecord(raw)) return undefined

      const name = firstText(raw.name, raw.columnName, raw.sourceName, raw.source_name)
      if (!name) return undefined

      return compactRecord({
        name,
        originalName: firstText(raw.originalName, raw.original_name, name),
        type: firstText(raw.type, raw.sqlType, raw.sql_type),
        selected: raw.selected !== false,
        semanticTerm: firstText(raw.semanticTerm, raw.iolTerm, raw.iol_term),
        alias: firstText(raw.alias),
      }) as SourceField
    })
    .filter((field): field is SourceField => !!field)
}

function mappingSourceName(mapping: FieldMappingWire): string | undefined {
  return firstText(mapping.sourceName, mapping.source_name)
}

function mappingIolTerm(mapping: FieldMappingWire): string | undefined {
  return firstText(mapping.iolTerm, mapping.iol_term)
}

function mappingSourceFields(mapping: FieldMappingWire): string[] {
  const fields = mapping.sourceFields || mapping.source_fields || []
  return fields.map((field) => firstText(field)).filter((field): field is string => !!field)
}

function mappingAlias(mapping: FieldMappingWire): string | undefined {
  const rules = mapping.cleaningRules || mapping.cleaning_rules
  return isRecord(rules) ? firstText(rules.alias) : undefined
}

function mergeFieldMappings(
  fields: SourceField[],
  sourceName: string,
  mappings?: FieldMappingWire[],
): SourceField[] {
  if (!mappings || mappings.length === 0) return fields

  const next = [...fields]

  mappings.forEach((mapping) => {
    const mappingSource = mappingSourceName(mapping)
    if (mappingSource && mappingSource !== sourceName) return

    const iolTerm = mappingIolTerm(mapping)
    const alias = mappingAlias(mapping)
    const type = firstText(mapping.type)

    mappingSourceFields(mapping).forEach((sourceField) => {
      const existingIndex = next.findIndex((field) =>
        field.name === sourceField || field.originalName === sourceField,
      )

      if (existingIndex >= 0) {
        next[existingIndex] = compactRecord({
          ...next[existingIndex],
          type: next[existingIndex].type || type,
          semanticTerm: next[existingIndex].semanticTerm || iolTerm,
          alias: next[existingIndex].alias || alias,
          selected: next[existingIndex].selected !== false,
        }) as SourceField
      } else {
        next.push(compactRecord({
          name: sourceField,
          originalName: sourceField,
          type,
          selected: true,
          semanticTerm: iolTerm,
          alias,
        }) as SourceField)
      }
    })
  })

  return next
}

function fromBackendSource(
  rawSource: unknown,
  destinationConnectionId?: string,
  mappings?: FieldMappingWire[],
): WorkflowSourceUi {
  const source = isRecord(rawSource) ? rawSource : {}
  const config = isRecord(source.config) ? source.config as WorkflowSourceConfigWire : source as WorkflowSourceConfigWire
  const sourceName = firstText(source.source_name, source.sourceName, source.name) || ''
  const targetConnection = isRecord(config.target_connection) ? config.target_connection : undefined
  const sourceConfig = isRecord(config.source_config) ? config.source_config : undefined
  const fields = mergeFieldMappings(sourceFieldsFromConfig(config.fields), sourceName, mappings)

  return compactRecord({
    id: firstText(source.id),
    source_name: sourceName,
    upload_id: firstText(config.upload_id),
    file_path: firstText(config.file_path),
    uri: firstText(config.uri, config.file_path),
    target_table: firstText(config.target_table, targetConnection?.target_table),
    sourceConnectionId: firstText(config.source_connection_id),
    connectionId: destinationConnectionId,
    connectionDetails: targetConnection,
    source_config: sourceConfig,
    load_mode: normalizeLoadMode(config.load_mode),
    incremental_column: firstText(config.incremental_column),
    last_watermark: firstText(config.last_watermark),
    jdbc_partitioning_enabled: config.jdbc_partitioning_enabled === true,
    partition_column: firstText(config.partition_column),
    partition_type: config.partition_type === 'DATE' ? 'DATE' : 'NUMERIC',
    partition_lower_bound: firstText(config.partition_lower_bound),
    partition_upper_bound: firstText(config.partition_upper_bound),
    partition_count: Number(config.partition_count || 4),
    partition_parallelism: Number(config.partition_parallelism || 4),
    jdbc_fetch_size: Number(config.jdbc_fetch_size || 1000),
    spark_write_partitions: Number(config.spark_write_partitions || 4),
    bulk_load_strategy: (config.bulk_load_strategy || 'AUTO') as WorkflowSourceUi['bulk_load_strategy'],
    transport_mode: (config.transport_mode || 'AUTO') as WorkflowSourceUi['transport_mode'],
    write_mode: config.write_mode ? normalizeWriteMode(config.write_mode) : undefined,
    fields,
    silver_config: isRecord(config.silver_config) ? config.silver_config : undefined,
    config,
  }) as WorkflowSourceUi
}

export function fromBackendWorkflow(input: WorkflowConfigWire | WorkflowConfigUi): WorkflowConfigUi {
  const raw = input as unknown as LooseRecord
  const destinationConnectionId = firstText(raw.destinationConnectionId, raw.destination_connection_id)
  const sources = Array.isArray(raw.sources)
    ? raw.sources.map((source) => fromBackendSource(source, destinationConnectionId, raw.fields as FieldMappingWire[] | undefined))
    : []
  const goldConfigGlobal = (raw.gold_config_global || raw.goldConfigGlobal) as GoldConfigGlobal | undefined
  const outboundConfig = isRecord(raw.outboundConfig) ? raw.outboundConfig as WorkflowOutboundConfig : undefined

  return compactRecord({
    id: firstText(raw.id),
    name: firstText(raw.workflowName, raw.workflow_name, raw.name) || '',
    workflowName: firstText(raw.workflowName, raw.workflow_name),
    description: firstText(raw.description),
    direction: (firstText(raw.direction) as WorkflowConfigUi['direction']) || 'INTERNAL',
    outboundConfig,
    protocol: firstText(raw.protocol, sources[0]?.source_name),
    standardId: firstText(raw.standardId, raw.standard_id),
    standardDomain: firstText(raw.standardDomain, raw.standard_domain),
    destinationConnectionId,
    priority: typeof raw.priority === 'number' ? raw.priority : Number(raw.priority ?? 3),
    executionMode: (firstText(raw.executionMode, raw.execution_mode) as WorkflowConfigUi['executionMode']) || 'LOCAL',
    estimatedRows: typeof raw.estimatedRows === 'number' ? raw.estimatedRows : undefined,
    schedule: isRecord(raw.schedule) ? raw.schedule : { enabled: false },
    sources,
    goldConfigGlobal,
    aggregationScripts: firstText(raw.aggregationScripts, raw.aggregation_scripts),
    metadataFileName: firstText(raw.metadataFileName, raw.metadata_file_name),
    metadataJson: firstText(raw.metadataJson, raw.metadata_json),
    metadataVersion: firstText(raw.metadataVersion, raw.metadata_version),
    sourceConfig: isRecord(raw.sourceConfig) ? raw.sourceConfig : undefined,
    fields: Array.isArray(raw.fields) ? raw.fields : undefined,
    isActive: typeof raw.isActive === 'boolean' ? raw.isActive : typeof raw.active === 'boolean' ? raw.active : undefined,
    active: typeof raw.active === 'boolean' ? raw.active : undefined,
    createdAt: firstText(raw.createdAt),
    updatedAt: firstText(raw.updatedAt),
    createdBy: firstText(raw.createdBy),
  }) as WorkflowConfigUi
}

export function toBackendDiscoverySource(source: WorkflowSourceUi): Record<string, unknown> {
  const sourceConfig = isRecord(source.source_config) ? source.source_config : {}
  const config = compactRecord({
    ...sourceConfig,
    source_connection_id: firstText(source.sourceConnectionId, source.config?.source_connection_id),
    file_path: firstText(source.file_path, source.uri),
    uri: firstText(source.uri, source.file_path),
    target_table: firstText(source.target_table),
    target_connection: buildTargetConnection(source, source.target_table),
    columns: selectedProjectionFields(source.fields),
  })

  return compactRecord({
    protocol: firstText(source.source_name),
    sourceName: firstText(source.source_name),
    config,
  })
}
