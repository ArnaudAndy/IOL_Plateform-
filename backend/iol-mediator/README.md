# IOL generic OpenHIM mediator

This service is the generic OpenHIM fallback and the internal streaming handoff
for the Java FHIR R4, ISO 20022 and Ed-Fi mediators. It validates generic data
against api-core `StandardTerm` metadata, normalizes accepted records to the IOL
pivot, and sends NDJSON progressively to api-core.

Invalid messages are rejected before Bronze and audited to the Kafka DLQ.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `PORT` | `3000` | HTTP port inside the container |
| `MEDIATOR_HOST` | `iol-mediator` | Host OpenHIM uses when creating the default route |
| `MEDIATOR_PATH` | `/` | Endpoint path registered in OpenHIM |
| `MEDIATOR_URN` | `urn:mediator:iol-generic` | Stable mediator URN |
| `OPENHIM_API_URL` | `https://openhim-core:8080` | OpenHIM Core API URL |
| `OPENHIM_USERNAME` | empty | OpenHIM admin username |
| `OPENHIM_PASSWORD` | empty | OpenHIM admin password |
| `OPENHIM_TRUST_SELF_SIGNED` | `true` | Trust Core self-signed cert in local Docker |
| `MEDIATOR_HEARTBEAT_INTERVAL_MS` | `10000` | Heartbeat interval |
| `IOL_API_BASE_URL` | `http://api-core:8084` | api-core URL inside Docker |
| `IOL_INTERNAL_SECRET` | empty | Shared secret for `/api/internal/interop/**` |
| `IOL_DEFAULT_STANDARD_ID` | empty | Local fallback standardId |
| `IOL_DEFAULT_WORKFLOW_ID` | empty | Optional local fallback workflowId; otherwise api-core resolves the active INBOUND workflow for the standard |
| `IOL_DEFAULT_SOURCE_SYSTEM` | `generic-json` | Mapping key used in `StandardTerm.systemMappings` |
| `IOL_DEFAULT_ADAPTER` | `generic-json` | Local fallback parser adapter before StandardTerm validation |
| `IOL_INBOUND_AUTH_TYPE` | `private` | OpenHIM channel authentication mode |
| `IOL_INBOUND_ALLOWED_ROLES` | `iol-inbound` | Roles allowed on every private IOL channel |
| `IOL_INBOUND_CLIENT_ID` | empty | Optional system client provisioned through the OpenHIM API |
| `IOL_INBOUND_CLIENT_NAME` | `IOL inbound system` | Display name of the provisioned client |
| `IOL_INBOUND_CLIENT_PASSWORD` | empty | Client secret; only its salted hash is sent to OpenHIM |
| `IOL_MAX_INBOUND_BYTES` | `268435456` | Buffered JSON request limit |
| `IOL_MAX_STREAM_BYTES` | `10737418240` | Progressive NDJSON request limit |
| `IOL_MAX_NDJSON_LINE_BYTES` | `134217728` | Maximum individual NDJSON record |
| `IOL_STREAM_BATCH_ROWS` | `500` | Validation batch size |
| `KAFKA_BOOTSTRAP_SERVERS` | empty | Enables Kafka DLQ and command publication when set |
| `APP_KAFKA_DLQ_TOPIC` | `iol.pipeline.commands.dlq` | DLQ topic |

If the OpenHIM username or password is missing, the service still starts and
serves `/health`, but skips registration and heartbeat. A private channel never
becomes public when client credentials are missing. If no `standardId` is
configured, it remains in pass-through mode for local smoke tests.

## Local test

```powershell
npm test
```

## Runtime endpoints

- `GET /health`: service health
- any other path without a configured `standardId`: pass-through mediator response
- JSON with a configured `standardId`: validate and normalize a bounded message
- NDJSON with a configured `standardId`: validate and hand off progressively
- invalid input: return a structured OpenHIM response and publish a DLQ entry

## Correlation

The mediator reads these inbound headers when present:

- `idempotency-key`: mandatory for every request that creates an IOL execution.
  A completed retry receives the same `execLogId`.
- `x-correlation-id` or `x-request-id`: application correlation ID propagated to OpenHIM response properties, api-core `ExecutionLog.executionParams`, Kafka status, and DLQ.
- `x-openhim-transactionid` or `x-openhim-transaction-id`: OpenHIM transaction identifier propagated beside the correlation ID.
- `x-iol-adapter`: compatibility override for legacy generic channels.

## Standard packs

`generic-json` remains the identity adapter for custom JSON contracts. The old
`fhir-basic` adapter is compatibility-only and is not a FHIR conformance pack.
New FHIR traffic must use `/interop/fhir` and the Java HAPI FHIR R4 mediator in
`backend/openhim-mediators`.

## INBOUND Kafka hand-off

For progressive valid messages, the mediator calls:

```text
POST /api/internal/interop/standards/{standardId}/inbound-executions/stream
```

api-core resolves the target workflow, creates the `ExecutionLog`, chooses the
transport, transfers all source records, then publishes the execution command.
The choice is automatic:

- normal volume: `PIPELINE_SOURCE_ROW_BATCH` events carry all rows in Kafka;
- big data or an oversized individual record: RustFS stores a temporary JSONL
  object and Kafka carries its manifest;
- no CSV is generated;
- Hop and Spark never reconnect to the external source.

OpenHIM stores transaction metadata only: the channel disables request and
response bodies, mediator orchestrations are body-free, and rerun permissions
are empty. API Core owns the persistent replay decision in MongoDB.

Interop executions can be monitored through api-core:

- `GET /api/logs/interop`
- `GET /api/logs/interop/correlation/{correlationId}`
- `GET /api/logs/interop/summary`

## Examples

- `examples/custom-standard.json`: sample CUSTOM Standard and StandardTerm definitions
- `examples/inbound-valid.json`: valid generic JSON input, normalized to pivot keys such as `patient_id`
- `examples/inbound-invalid.json`: invalid input, rejected with `response.status=400` and a DLQ message
