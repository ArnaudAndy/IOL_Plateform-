Pipeline consumer microservice

This Spring Boot application consumes prioritized orchestration commands from Kafka and launches Apache Hop with the generated metadata JSON.

Run locally:

```bash
cd backend/pipeline-consumer
mvn -DskipTests package
java -jar target/pipeline-consumer-0.0.1-SNAPSHOT.jar
```

Configuration:
- `KAFKA_BOOTSTRAP_SERVERS` environment variable or default `localhost:9092`
- `APP_KAFKA_COMMANDS_HIGH_TOPIC`, `APP_KAFKA_COMMANDS_TOPIC`, `APP_KAFKA_COMMANDS_LOW_TOPIC`
- `APP_KAFKA_STATUS_TOPIC` for Hop execution results
- `APP_KAFKA_DLQ_TOPIC` for unrecoverable execution failures

## INBOUND PUSH

For interop INBOUND commands, the listener expects the same command envelope as api-core uses, including `eventType=PIPELINE_EXECUTION_REQUESTED`. When `direction=INBOUND` and a source is marked `PUSH`, the consumer materializes the already-normalized pivot record(s) to a temporary UTF-8 CSV file, rewrites only that executable metadata source to CSV for Hop, and preserves the root `direction`, `standardId`, `workflowId`, and `execLogId`.

The existing INTERNAL/PULL path is unchanged; non-INBOUND commands are written to the Hop metadata file as received.

For INBOUND failures, the consumer publishes the DLQ entry in the interop NoSQL error shape:

```json
{
  "log_id": "execution-log-id",
  "source_id": "external-system",
  "workflow_id": "workflow-id",
  "standard_id": "standard-id",
  "correlation_id": "correlation-id",
  "openhim_transaction_id": "openhim-transaction-id",
  "error_context": {
    "step": "PIPELINE_CONSUMER",
    "message": "error",
    "severity": "ERROR"
  },
  "original_data": {
    "command": {}
  },
  "timestamp": "..."
}
```

## Profil OS (Hop) — Windows vs Linux

Le service détecte automatiquement l'OS au démarrage et active le profil Spring
correspondant si aucun n'est fourni explicitement :
- **Windows** → profil `windows` (`hop-run.bat`, chemins `C:/...`)
- **Linux / Docker** → profil `linux` (`hop-run.sh`, `/opt/hop`)

**En dev Windows : aucune action requise** — le profil `windows` est activé tout seul.

Pour forcer un profil : `--spring.profiles.active=windows` (ou `SPRING_PROFILES_ACTIVE=windows`).
Un profil fourni manuellement n'est jamais écrasé par l'auto-détection.

Les valeurs Hop par profil sont dans `application.yml` (blocs `on-profile: linux` /
`on-profile: windows`) et surchargeables par variables d'environnement `HOP_*` :
`HOP_HOME`, `HOP_RUN_SCRIPT`, `HOP_WORKFLOW_FILE`, `HOP_PIPELINES_DIR`, `HOP_TEMP_DIR`.

Au démarrage, un log `Hop config → home=... script=... workflow=...` affiche les valeurs
réellement chargées ; un `WARN` explicite signale un script Hop introuvable.

> ⚠️ Après modification de `application.yml`, relancer `mvn clean package` : la config
> est copiée dans `target/classes/` au build — lancer un vieux JAR/`target` utiliserait
> l'ancienne config (cause classique de « profil actif mais valeurs par défaut »).
