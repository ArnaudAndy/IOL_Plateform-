# OpenHIM interoperability stack

This folder runs OpenHIM Core, OpenHIM Console, a dedicated MongoDB replica
set, the generic IOL streaming mediator, and the Java FHIR R4, ISO 20022 and
Ed-Fi mediators.

## Versions

- OpenHIM Core: `iol/openhim-core:v8.5.0-privacy`, construit localement à
  partir de `jembi/openhim-core:v8.5.0`
- OpenHIM Console: `jembi/openhim-console:v1.18.2`
- OpenHIM MongoDB: dedicated `mongo:4.4` replica set, separate from the IOL metadata MongoDB
- IOL generic mediator: local image built from `backend/iol-mediator`
- Java standard packs: local images built from `backend/openhim-mediators`

The generic mediator uses `openhim-mediator-utils@0.4.0`; the standard packs use
Spring Boot 3.4 and Java 17.

## Start

> Les commandes de cette section servent au developpement local. En
> preproduction et en production, utiliser le point d'entree unique decrit
> dans [le guide de deploiement](../../docs/GUIDE_DEPLOIEMENT.md), qui emploie
> le meme fichier d'environnement que la stack principale.

From `backend/`:

```powershell
Copy-Item openhim\.env.example openhim\.env
notepad openhim\.env
docker compose up -d nginx
docker compose --env-file openhim\.env -f openhim\docker-compose.openhim.yml up -d
docker compose --env-file openhim\.env -f openhim\docker-compose.openhim.yml ps
```

The compose file does not publish OpenHIM ports. Use Nginx only:

- Console: `http://localhost/openhim-console/`
- Core API proxy for the console: `http://localhost/openhim-api/heartbeat`
- Transaction entrypoint: `http://localhost/interop/<domain>/<standard>`

For production, enable the HTTPS server in `backend/nginx/nginx.conf`, provide certs in `backend/nginx/ssl`, set `OPENHIM_PUBLIC_PROTOCOL=https`, `OPENHIM_PUBLIC_PORT=443`, and set `OPENHIM_API_SECURE_COOKIE=true`.

## Root password

OpenHIM Core v8.5.0 creates `root@openhim.org` with the public bootstrap password `openhim-password` if no root user exists. The console detects that exact first login and forces a password reset.

First-run checklist:

1. Open `http://localhost/openhim-console/`.
2. Login with the bootstrap credentials.
3. Set a new root password immediately.
4. Put the new value in `openhim/.env` as `OPENHIM_MEDIATOR_PASSWORD`, or create a dedicated OpenHIM admin user for mediator registration and use that account.
5. Store the new value in your secret manager, not in this repository.

The `.env.example` contains only placeholders for operator reference; the OpenHIM image does not expose an official env var to replace the bootstrap password before first startup.

## Internal api-core secret

The mediator calls api-core through `/api/internal/interop/**`. That endpoint is not JWT-based; it requires `X-IOL-Internal-Secret` and is disabled when the secret is blank.

Use the same value in both stacks:

```powershell
# backend/.env, consumed by backend/docker-compose.yml
INTEROP_INTERNAL_SECRET=<strong-shared-secret>

# backend/openhim/.env, consumed by docker-compose.openhim.yml
INTEROP_INTERNAL_SECRET=<strong-shared-secret>
```

For local Docker only, generate or reconcile both ignored `.env` files without
printing the secret:

```powershell
.\ops\configure-local-interop-secret.ps1
```

Production must inject this value from the platform secret manager instead of
persisting it in an image, Compose file or source-controlled environment file.

## Private INBOUND client

All IOL channels are private by default. The generic mediator provisions the
OpenHIM system client when both its identifier and password are supplied:

```powershell
# backend/openhim/.env
IOL_INBOUND_AUTH_TYPE=private
IOL_INBOUND_ALLOWED_ROLES=iol-inbound
IOL_INBOUND_CLIENT_ID=partner-system
IOL_INBOUND_CLIENT_NAME=Partner system
IOL_INBOUND_CLIENT_PASSWORD=<long-random-secret>
```

The password is sent to the OpenHIM administration API only as a salted
SHA-512 hash. The client receives the `iol-inbound` role, and every installed
IOL channel reconciles its `allow` list with that role. Use one client and one
secret per external system in production; client lifecycle can also be managed
through the OpenHIM Console or an external identity/provisioning system.

When the channel is private and these variables are absent, the mediator emits
an explicit warning and no external request is accepted. It never silently
falls back to a public channel.

## Mediator heartbeat

After `OPENHIM_MEDIATOR_PASSWORD` is set to a valid OpenHIM admin password,
restart all mediators:

```powershell
docker compose --env-file openhim\.env -f openhim\docker-compose.openhim.yml up -d --build iol-mediator iol-fhir-mediator iol-iso20022-mediator iol-edfi-mediator
```

Expected result in the console:

- `urn:mediator:iol-generic` is visible with a recent heartbeat.
- `urn:mediator:iol-fhir-r4` routes `/interop/fhir/**`.
- `urn:mediator:iol-iso20022` routes `/interop/iso20022/**`.
- `urn:mediator:iol-edfi` routes `/interop/edfi/**`.
- Specialized channels have priority `1`; the generic fallback has priority
  `100`.
- Readiness remains DOWN while registration or channel installation fails.

The specialized mediator validates its domain message and streams a non-lossy
NDJSON envelope to the generic mediator. The generic mediator validates pivot
terms and hands the stream to `api-core`. Normal-volume rows travel through
Kafka. Big-data payloads or Kafka-oversized individual records travel through
RustFS. No CSV is created.

Rejected messages are published to `iol.pipeline.commands.dlq` with:

- `log_id`
- `source_id`
- `error_context.step/message/severity`
- `original_data`
- `timestamp`

Pipeline failures for INBOUND commands also use the same NoSQL error shape, with `error_context.step=PIPELINE_CONSUMER`.

## Test clients and standard channels

After the console login succeeds:

1. Configure or create one OpenHIM client per external system.
2. Use basic authentication only for local testing; prefer mTLS in production.
3. Start all mediator services. Their default channels are installed
   automatically.
4. Associate the client with only the required channel(s).
5. Set the matching workflow identifier through mediator configuration or
   `X-IOL-Workflow-ID`.
6. Send a synthetic example:

```powershell
curl.exe -u client:secret `
  -H "Content-Type: application/fhir+json" `
  -H "Idempotency-Key: fhir-example-0001" `
  -H "X-IOL-Workflow-ID: <workflow-id>" `
  --data-binary "@openhim-mediators/examples/fhir-patient.json" `
  http://localhost/interop/fhir

curl.exe -u client:secret `
  -H "Content-Type: application/xml" `
  -H "Idempotency-Key: iso20022-example-0001" `
  -H "X-IOL-Workflow-ID: <workflow-id>" `
  --data-binary "@openhim-mediators/examples/iso20022-pain001.xml" `
  http://localhost/interop/iso20022

curl.exe -u client:secret `
  -H "Content-Type: application/x-ndjson" `
  -H "Idempotency-Key: edfi-example-0001" `
  -H "X-IOL-Workflow-ID: <workflow-id>" `
  --data-binary "@openhim-mediators/examples/edfi-students.ndjson" `
  http://localhost/interop/edfi/students
```

Expected result: the transaction appears in OpenHIM, the IOL execution receives
the same correlation identifier, and the source transport is selected
automatically.

## Transaction privacy and replay

The mediator reconciles every IOL channel with:

- `requestBody: false`;
- `responseBody: false`;
- empty `txViewFullAcl`;
- empty `txRerunAcl`.

OpenHIM Core `v8.5.0` n'applique ces deux options qu'aux champs principaux de
la transaction. L'image IOL ajoute donc le correctif déterministe décrit dans
`openhim-core-privacy/README.md` pour vider aussi les corps des orchestrations
et des routes secondaires. Le build échoue volontairement si une future image
OpenHIM ne correspond plus au code audité : toute montée de version impose
ainsi de revoir ce correctif au lieu de perdre silencieusement la protection.

Mediator responses and orchestrations contain identifiers, counts and statuses
only. They never echo the business payload. This prevents a manual OpenHIM
rerun from bypassing IOL's persistent idempotency ledger.

Audit the live database without reading any payload:

```powershell
docker cp openhim\scripts\audit-transaction-privacy.js `
  iol-openhim-mongo:/tmp/audit-transaction-privacy.js
docker exec iol-openhim-mongo mongo openhim --quiet `
  /tmp/audit-transaction-privacy.js
```

The command exits with code `2` on any body or rerunnable transaction. For
historical records created before this policy, take the required audit backup,
then run the irreversible payload cleanup:

```powershell
docker cp openhim\scripts\purge-sensitive-transaction-bodies.js `
  iol-openhim-mongo:/tmp/purge-sensitive-transaction-bodies.js
docker exec iol-openhim-mongo mongo openhim --quiet --eval `
  'var transactionIds=["<transaction-object-id>"]; load("/tmp/purge-sensitive-transaction-bodies.js")'
```

La liste des identifiants est obligatoire. Le script refuse une purge globale
et s'arrête avant toute écriture si un identifiant est invalide ou introuvable.

The IOL `inbound_idempotency_ledger` collection persists only hashes and
execution metadata. Repeating a completed `Idempotency-Key` returns the same
`execLogId`; a concurrent, failed or content-mismatched request returns
`409 Conflict`.

After a deployment, run the complete synthetic transaction check from
`backend/openhim`:

```powershell
.\scripts\run-openhim-privacy-smoke.ps1
```

It rebuilds the privacy-patched Core and the mediator, provisions an ephemeral
client, then probes the generic, FHIR R4, ISO 20022 and Ed-Fi channels. Every
probe requires either a successful response or a controlled application
rejection (`400`, `409`, `415`, `422` or `428`) through the real OpenHIM router.
The script removes that client and finally runs the read-only privacy audit over
the entire transaction collection. Controlled rejection deliberately avoids
writing synthetic rows into a business target.

Detailed architecture and production checks are in
[the production interoperability guide](../../docs/INTEROPERABILITE_FHIR_ISO20022_EDFI_PRODUCTION.md).

## Stop

From `backend/`:

```powershell
docker compose --env-file openhim\.env -f openhim\docker-compose.openhim.yml down
```

Use `-v` only when you intentionally want to delete the OpenHIM MongoDB volume.
