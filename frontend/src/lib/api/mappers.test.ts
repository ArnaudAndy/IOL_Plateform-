import assert from 'node:assert/strict'

import { fromBackendWorkflow, toBackendWorkflow } from './mappers.js'
import type { WorkflowConfigUi } from './types.js'

const sampleWorkflow: WorkflowConfigUi = {
  id: 'wf_1',
  name: 'daily-clients',
  protocol: 'CSV',
  description: 'Clients multi-source',
  direction: 'INTERNAL',
  standardId: 'std_clients',
  priority: 3,
  destinationConnectionId: 'dw_conn',
  schedule: { enabled: true, frequency: 'DAILY', time: '02:00' },
  sources: [
    {
      source_name: 'CSV',
      file_path: '/data/clients.csv',
      uri: '/data/clients.csv',
      target_table: 'stg_clients',
      load_mode: 'INCREMENTAL',
      incremental_column: 'updated_at',
      last_watermark: '2026-01-01T00:00:00Z',
      write_mode: 'append',
      source_config: { delimiter: ';', encoding: 'UTF-8' },
      fields: [
        { name: 'client_id', originalName: 'Client ID', type: 'String', selected: true, semanticTerm: 'client_id', alias: 'clientId' },
        { name: 'email', originalName: 'Email', type: 'String', selected: true },
        { name: 'internal_note', originalName: 'Internal Note', type: 'String', selected: false, semanticTerm: 'note' },
      ],
      silver_config: {
        target_table_silver: 'cln_clients',
        elt_scripts_silver: 'select client_id, email from stg_clients',
      },
    },
    {
      source_name: 'POSTGRES',
      sourceConnectionId: 'hospital_a_conn',
      target_table: 'stg_orders',
      load_mode: 'FULL',
      write_mode: 'append',
      source_config: { query: 'select id, total from orders' },
      fields: [
        { name: 'id', originalName: 'id', type: 'Integer', selected: true },
        { name: 'total', originalName: 'total', type: 'Decimal', selected: true },
      ],
      silver_config: {
        target_table_silver: 'cln_orders',
        elt_scripts_silver: 'select id, total from stg_orders',
      },
    },
  ],
  goldConfigGlobal: {
    target_table_gold: 'gold.client_orders',
    elt_scripts_gold: 'select * from cln_clients join cln_orders using (client_id)',
  },
}

const wire = toBackendWorkflow(sampleWorkflow)

assert.equal(wire.workflowName, 'daily-clients')
assert.equal((wire as unknown as Record<string, unknown>).name, undefined)
assert.equal(wire.protocol, 'CSV')
assert.equal(wire.destinationConnectionId, 'dw_conn')
assert.equal(wire.gold_config_global?.target_table_gold, 'gold.client_orders')
assert.equal(wire.aggregationScripts, sampleWorkflow.goldConfigGlobal?.elt_scripts_gold)

assert.equal(wire.sources?.[0]?.source_name, 'CSV')
assert.equal(wire.sources?.[0]?.config.target_table, 'stg_clients')
assert.equal(wire.sources?.[0]?.config.source_config?.delimiter, ';')
assert.equal(wire.sources?.[0]?.config.load_mode, 'INCREMENTAL')
assert.equal(wire.sources?.[0]?.config.incremental_column, 'updated_at')
assert.equal(wire.sources?.[0]?.config.write_mode, 'append')
assert.equal(wire.sources?.[0]?.config.last_watermark, undefined)
assert.equal(wire.sources?.[0]?.config.target_connection, undefined)
assert.deepEqual(wire.sources?.[0]?.config.fields, ['Client ID', 'Email'])

assert.equal(wire.sources?.[1]?.config.load_mode, 'FULL')
assert.equal(wire.sources?.[1]?.config.incremental_column, undefined)
assert.equal(wire.sources?.[1]?.config.source_connection_id, 'hospital_a_conn')
assert.deepEqual(wire.sources?.[1]?.config.fields, ['id', 'total'])

const roundTrip = fromBackendWorkflow(wire)
assert.equal(roundTrip.name, sampleWorkflow.name)
assert.equal(roundTrip.destinationConnectionId, 'dw_conn')
assert.equal(roundTrip.goldConfigGlobal?.elt_scripts_gold, sampleWorkflow.goldConfigGlobal?.elt_scripts_gold)
assert.equal(roundTrip.sources[0]?.source_config?.delimiter, ';')
assert.equal(roundTrip.sources[0]?.load_mode, 'INCREMENTAL')
assert.equal(roundTrip.sources[0]?.write_mode, 'append')
assert.equal(roundTrip.sources[1]?.sourceConnectionId, 'hospital_a_conn')
assert.deepEqual(
  roundTrip.sources[0]?.fields?.filter((field) => field.selected).map((field) => field.originalName),
  ['Client ID', 'Email'],
)
assert.equal(roundTrip.sources[0]?.fields?.[0]?.semanticTerm, 'client_id')
assert.equal(roundTrip.sources[0]?.fields?.[0]?.alias, 'clientId')

const outboundWire = toBackendWorkflow({
  ...sampleWorkflow,
  direction: 'OUTBOUND',
  outboundConfig: {
    targetStandardId: 'std_partner',
    targetSystem: 'hospital_b',
    targetAdapter: 'generic-json',
    source: { goldTable: 'gold.client_orders' },
    destination: { openhimChannel: 'outbound-client', auth: 'vault:partner-token' },
  },
})

assert.equal(outboundWire.direction, 'OUTBOUND')
assert.equal(outboundWire.outboundConfig?.targetStandardId, 'std_partner')
assert.equal(outboundWire.outboundConfig?.targetSystem, 'hospital_b')
assert.equal(outboundWire.outboundConfig?.targetAdapter, 'generic-json')
assert.equal(outboundWire.outboundConfig?.source?.goldTable, 'gold.client_orders')
assert.equal(outboundWire.outboundConfig?.destination?.openhimChannel, 'outbound-client')
assert.equal(fromBackendWorkflow(outboundWire).outboundConfig?.destination?.auth, 'vault:partner-token')

const inlineConnectionWire = toBackendWorkflow({
  ...sampleWorkflow,
  destinationConnectionId: undefined,
  sources: [
    {
      ...sampleWorkflow.sources[0]!,
      connectionDetails: { host: 'localhost', port: 5432, database: 'dw', username: 'postgres', password: 'secret' },
    },
  ],
})

assert.equal(inlineConnectionWire.destinationConnectionId, undefined)
assert.equal(inlineConnectionWire.sources?.[0]?.config.target_connection?.target_table, 'stg_clients')
assert.equal(inlineConnectionWire.sources?.[0]?.config.target_connection?.database, 'dw')

console.log(JSON.stringify(wire, null, 2))
