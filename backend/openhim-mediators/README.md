# IOL OpenHIM Standard Mediators

Java 17 OpenHIM mediators for receiving and validating interoperable messages
before handing them to the IOL streaming ingestion pipeline.

This repository contains three independently deployable services:

| Module | Standard | Default route | Port |
| --- | --- | --- | --- |
| `openhim-mediator-iol-fhir` | HL7 FHIR R4 | `/interop/fhir/**` | `3101` |
| `openhim-mediator-iol-iso20022` | ISO 20022 MX | `/interop/iso20022/**` | `3102` |
| `openhim-mediator-iol-edfi` | Ed-Fi Data Standard 6.1 | `/interop/edfi/**` | `3103` |

Each service registers itself with OpenHIM, installs or updates its default
channel, validates the domain message, converts it to a non-lossy canonical
envelope, and streams the resulting records as NDJSON to the configured IOL
handoff endpoint.

## Architecture

```mermaid
flowchart LR
    EXT[External system] -->|FHIR, ISO 20022 or Ed-Fi| OH[OpenHIM Router]
    OH --> MED[Domain Java mediator]
    MED -->|Validated NDJSON envelope| GEN[IOL generic mediator]
    GEN --> API[IOL api-core]
    API -->|Normal volume| K[Kafka row batches]
    API -->|Big data or oversized record| R[RustFS object]
    K --> C[Pipeline consumer]
    R --> C
```

The domain mediator never connects to a source database. Kafka transports
normal-volume payloads. RustFS is selected only for big-data transfers or when
a single Kafka record would be unsafe.

## Validation scope

### FHIR R4

- Parses FHIR JSON and XML with HAPI FHIR.
- Validates core R4 structures and terminology available to the local
  validation support chain.
- Preserves every resource in a Bundle.
- Enforces `entry.request` on `batch` and `transaction` Bundles.
- Preserves the complete resource JSON in `fhir_resource_json`.

National implementation guides are not bundled automatically. Add their NPM
packages and terminology service configuration before claiming conformance to
a country-specific profile.

### ISO 20022

- Parses MX messages with Prowide ISO 20022.
- Identifies the message definition, namespace and business family.
- Accepts one XML message or a JSON batch containing `xml` or `messages[]`.
- Can restrict allowed families such as `pain`, `pacs` and `camt`.
- Blocks DTDs and external XML entities.
- Preserves both XML and the Prowide JSON representation.

This is structural and model validation. Scheme-specific market-practice rules,
signatures and settlement-network certification remain deployment concerns.

### Ed-Fi

- Accepts one JSON resource, an array, `records[]`, `resources[]`, or NDJSON.
- Carries the resource name from the URL or `X-EdFi-Resource`.
- Validates object shape, UUID identifiers, ETags and reference objects.
- Preserves the complete resource in `edfi_payload_json`.

Full endpoint-specific validation requires the OpenAPI metadata of the target
Ed-Fi ODS/API deployment.

## Build and test

```bash
mvn test
mvn package
```

Build an individual image from this repository root:

```bash
docker build -f openhim-mediator-iol-fhir/Dockerfile -t iol/openhim-mediator-fhir:1.1.0 .
docker build -f openhim-mediator-iol-iso20022/Dockerfile -t iol/openhim-mediator-iso20022:1.1.0 .
docker build -f openhim-mediator-iol-edfi/Dockerfile -t iol/openhim-mediator-edfi:1.1.0 .
```

The runtime images use a non-root user.

## Required configuration

| Variable | Purpose |
| --- | --- |
| `OPENHIM_API_URL` | OpenHIM Core API or trusted reverse-proxy base URL |
| `OPENHIM_USERNAME` | OpenHIM account allowed to register mediators/channels |
| `OPENHIM_PASSWORD` | Password injected from a secret manager |
| `IOL_MEDIATOR_URL` | Internal URL of the IOL generic handoff mediator |
| `IOL_*_STANDARD_ID` | Standard pack identifier in IOL |
| `IOL_*_WORKFLOW_ID` | Optional fixed INBOUND workflow |
| `IOL_INBOUND_AUTH_TYPE` | OpenHIM channel auth mode; production default is `private` |

Useful limits:

- `MEDIATOR_MAX_REQUEST_BYTES`, default 256 MiB per standards transaction.
- `ISO20022_MAX_MESSAGE_BYTES`, default 64 MiB per MX document.
- `EDFI_MAX_RECORDS_PER_REQUEST`, default 250,000 resources.
- IOL's progressive NDJSON handoff is bounded separately and does not create a
  temporary CSV.

## OpenHIM behavior

At startup each service:

1. registers its mediator definition;
2. installs the default channel if missing;
3. synchronizes route, priority and privacy on an existing default channel;
4. sends heartbeats;
5. reports readiness as DOWN while registration is incomplete.

Dedicated channels use priority `1`; the generic fallback channel uses priority
`100`. A specialized route therefore wins for FHIR, ISO 20022 and Ed-Fi.

## Example requests

```bash
curl -u client:secret \
  -H "Content-Type: application/fhir+json" \
  -H "X-IOL-Workflow-ID: workflow-id" \
  --data-binary @examples/fhir-patient.json \
  https://gateway.example/interop/fhir

curl -u client:secret \
  -H "Content-Type: application/xml" \
  -H "X-IOL-Workflow-ID: workflow-id" \
  --data-binary @examples/iso20022-pain001.xml \
  https://gateway.example/interop/iso20022

curl -u client:secret \
  -H "Content-Type: application/x-ndjson" \
  -H "X-IOL-Workflow-ID: workflow-id" \
  --data-binary @examples/edfi-students.ndjson \
  https://gateway.example/interop/edfi/students
```

Never expose mediator service ports directly to external systems. External
traffic must enter through TLS and OpenHIM authentication.

## Publication

The recommended public repository name is
`openhim-mediator-iol-standard-packs`. The OpenHIM Mediator Library discovers
public GitHub repositories whose names begin with `openhim-mediator`.

See [docs/PUBLISHING.md](docs/PUBLISHING.md) for the release checklist. This
monorepo appears as one library entry containing three deployable mediators.

## License

Apache License 2.0. See [LICENSE](LICENSE).
