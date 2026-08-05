package com.iol.etlplatform.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.entity.DestinationConnection;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.embedded.GoldConfigGlobal;
import com.iol.etlplatform.entity.embedded.SourceDefinition;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import com.iol.etlplatform.service.DestinationConnectionService;
import com.iol.etlplatform.service.SourceLoadEstimatorService;
import com.iol.etlplatform.service.SourceDataTransportService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Gestion des topics Kafka de la plateforme IOL.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  RÔLE DE KAFKA DANS CETTE ARCHITECTURE
 * ═══════════════════════════════════════════════════════════════════
 *
 * Kafka est la FILE D'ATTENTE ORDONNÉE entre les utilisateurs et le moteur.
 * Il transporte aussi toutes les données de volume normal. Pour un traitement
 * Big Data, il transporte un manifeste RustFS avec taille et somme SHA-256.
 * Les moteurs Hop et Spark ne reçoivent jamais la connexion à la source.
 *
 * Problème sans Kafka :
 *   10 utilisateurs soumettent simultanément → 10 processus Hop en parallèle
 *   → surcharge mémoire → crash → résultats corrompus.
 *
 * Avec Kafka :
 *   10 demandes → empilées dans Kafka → pipeline-consumer les dépile
 *   1 par 1 dans l'ordre de priorité → Hop traite sereinement.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  TOPICS ET PRIORITÉS
 * ═══════════════════════════════════════════════════════════════════
 *
 *  3 topics de commandes selon la priorité du workflow :
 *
 *  iol.pipeline.high      ← priorité 1 (CRITICAL) et 2 (HIGH)
 *                           Ex: rapport décisionnel urgent, alerte métier
 *
 *  iol.pipeline.commands  ← priorité 3 (NORMAL, défaut)
 *                           Ex: traitement quotidien configuré
 *
 *  iol.pipeline.low       ← priorité 4 (LOW) et 5 (BACKGROUND)
 *                           Ex: batch de nuit, archivage, nettoyage
 *
 *  Le pipeline-consumer écoute les 3 topics avec des polling weights :
 *    high > commands > low (voir KafkaConfig dans pipeline-consumer)
 *
 *  Topics de retour (un seul) :
 *  iol.pipeline.status    ← résultats Hop → api-core
 *  iol.pipeline.commands.dlq ← erreurs non récupérables (audit/rejeu)
 * ═══════════════════════════════════════════════════════════════════
 */
@Service
@RequiredArgsConstructor
public class KafkaPipelineEventService {

    private static final Logger log = LoggerFactory.getLogger(KafkaPipelineEventService.class);
    public static final String EVENT_TYPE_PIPELINE_EXECUTION_REQUESTED = "PIPELINE_EXECUTION_REQUESTED";
    public static final String EVENT_TYPE_OUTBOUND_DELIVERY_REQUESTED = "OUTBOUND_DELIVERY_REQUESTED";

    private final ObjectMapper objectMapper;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplateProvider;
    private final ExecutionLogRepository executionLogRepository;
    private final DestinationConnectionService destinationConnectionService;

    @Autowired(required = false)
    private SourceDataTransportService sourceDataTransportService;

    @Autowired(required = false)
    private SourceLoadEstimatorService sourceLoadEstimatorService;

    @Value("${app.kafka.enabled:true}")
    private boolean kafkaEnabled;

    @Value("${app.kafka.topics.commands.high:iol.pipeline.high}")
    private String topicHigh;

    @Value("${app.kafka.topics.commands:iol.pipeline.commands}")
    private String topicNormal;

    @Value("${app.kafka.topics.commands.low:iol.pipeline.low}")
    private String topicLow;

    @Value("${app.kafka.topics.outbound:iol.outbound.delivery}")
    private String topicOutbound;

    @Value("${app.spark.row.threshold:${SPARK_ROW_THRESHOLD:1000000}}")
    private long sparkRowThreshold;

    @Value("${app.interop.big-data.byte-threshold:${SPARK_FILE_SIZE_THRESHOLD_BYTES:2147483648}}")
    private long sparkByteThreshold;

    /**
     * Publishes an execution request to Kafka with EXACT multi-source structure.
     *
     * Follows this structure for all workflows:
     * {
     *   "workflowId": "wf_123",
     *   "execLogId": "log_456",
     *   "workflowName": "...",
     *   "executionMode": "LOCAL",
     *   "priority": 3,
     *   "schedule": { ... },
     *   "sources": [
     *     {
     *       "source_name": "CSV|POSTGRES|...",
     *       "config": { file_path/uri, target_table, target_connection, source_config, fields, silver_config }
     *     }
     *   ],
     *   "gold_config_global": { target_table_gold, elt_scripts_gold }  // Gold UNIQUE du workflow
     * }
     *
     * Note: chaque source porte UNIQUEMENT son silver_config. Le Gold est unique
     * au niveau workflow (gold_config_global). Tout gold_config par source legacy
     * est retiré du payload.
     *
     * Fallback: if workflow uses legacy mono-source (no sources[] list), auto-convert from protocol/sourceConfig.
     */
    public String publishExecutionRequested(WorkflowConfig workflow, String execLogId) {
        int priority = workflow.getPriority() > 0 ? workflow.getPriority() : 3;
        String topic = resolveTopic(priority);

        Map<String, Object> command = buildCommandPayload(workflow, execLogId);
        String executionKey = executionPartitionKey(workflow, command);
        command.put("executionKey", executionKey);

        if (kafkaEnabled && sourceDataTransportService == null) {
            throw new IllegalStateException(
                    "Transport des donnees source indisponible: la commande ne sera pas publiee.");
        }
        if (kafkaEnabled) {
            List<Map<String, Object>> manifest = sourceDataTransportService.publishSourceData(
                    topic, executionKey, workflow.getId(), execLogId, command);
            if (!manifest.isEmpty()) {
                command.put("sourceDataManifest", manifest);
                java.util.Set<String> transports = manifest.stream()
                        .map(item -> String.valueOf(item.getOrDefault("transport", "KAFKA_CHUNKED")))
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                command.put("dataTransport", transports.size() == 1 ? transports.iterator().next() : "MIXED");
            }
            assertNoPlaintextCredential(command, "command");
        }

        String result = publish(topic, executionKey, command);
        Object sources = command.get("sources");
        int sourceCount = sources instanceof List<?> list ? list.size() : 0;
        log.info("Pipeline '{}' soumis — priorité={} topic={} sources={}",
                 workflow.getWorkflowName(), priority, topic, sourceCount);
        return result;
    }

    public String publishOutboundDeliveryRequested(
            WorkflowConfig workflow,
            String execLogId,
            String correlationId,
            String openhimTransactionId,
            List<Map<String, Object>> pivotRows) {
        Map<String, Object> command = buildOutboundDeliveryCommandPayload(
                workflow,
                execLogId,
                correlationId,
                openhimTransactionId,
                pivotRows);
        String key = correlationId != null && !correlationId.isBlank() ? correlationId : workflow.getId();
        String result = publish(topicOutbound, key, command);
        log.info("Livraison OUTBOUND demandee — workflowId={} execLogId={} rows={} topic={}",
                workflow.getId(), execLogId, pivotRows != null ? pivotRows.size() : 0, topicOutbound);
        return result;
    }

    public InboundPublication publishInboundExecutionRequested(
            WorkflowConfig workflow,
            String execLogId,
            String organizationId,
            String standardId,
            String sourceSystem,
            String correlationId,
            String openhimTransactionId,
            List<Map<String, Object>> pivots,
            Long estimatedBytes) {
        Map<String, Object> command = buildInboundPushCommandPayload(
                workflow,
                execLogId,
                standardId,
                sourceSystem,
                correlationId,
                openhimTransactionId,
                pivots);
        command.put("schemaVersion", "1.0.0");
        command.put("eventId", UUID.randomUUID().toString());
        command.put("organizationId", organizationId);
        command.put("createdAt", Instant.now().toString());
        command.put("estimatedRows", pivots.size());
        if (estimatedBytes != null && estimatedBytes >= 0) {
            command.put("estimatedBytes", estimatedBytes);
        }
        if (sparkRowThreshold > 0 && pivots.size() >= sparkRowThreshold) {
            command.put("executionMode", "SPARK");
        }

        int priority = workflow.getPriority() > 0 ? workflow.getPriority() : 3;
        String topic = resolveTopic(priority);
        String key = organizationId + ":" + workflow.getId();
        command.put("executionKey", key);

        if (kafkaEnabled && sourceDataTransportService != null) {
            sourceDataTransportService.publishInboundData(
                    topic,
                    key,
                    organizationId,
                    workflow.getId(),
                    execLogId,
                    pivots,
                    estimatedBytes,
                    command);
        }

        boolean published = false;
        if (kafkaEnabled) {
            publishAndAwait(topic, key, command);
            published = true;
        }
        String transport = String.valueOf(command.getOrDefault(
                "dataTransport", published ? "KAFKA_INLINE_JSON" : "NOT_PUBLISHED"));
        log.info("INBOUND remis — workflowId={} execLogId={} rows={} transport={} topic={}",
                workflow.getId(), execLogId, pivots.size(), transport, topic);
        return new InboundPublication(
                topic, key, command, transport, pivots.size(), published);
    }

    public InboundPublication publishInboundExecutionRequestedStream(
            WorkflowConfig workflow,
            String execLogId,
            String organizationId,
            String standardId,
            String sourceSystem,
            String correlationId,
            String openhimTransactionId,
            InputStream inputStream,
            Long estimatedRows,
            Long estimatedBytes,
            Long estimatedMaxRecordBytes) {
        if (!kafkaEnabled) {
            throw new IllegalStateException(
                    "Kafka doit être activé pour remettre un flux NDJSON INBOUND.");
        }
        if (sourceDataTransportService == null) {
            throw new IllegalStateException(
                    "Le service de transport des données source est indisponible.");
        }

        Map<String, Object> command = buildInboundPushStreamCommandPayload(
                workflow,
                execLogId,
                standardId,
                sourceSystem,
                correlationId,
                openhimTransactionId);
        command.put("schemaVersion", "1.0.0");
        command.put("eventId", UUID.randomUUID().toString());
        command.put("organizationId", organizationId);
        command.put("createdAt", Instant.now().toString());
        if (estimatedRows != null && estimatedRows >= 0) {
            command.put("estimatedRows", estimatedRows);
        }
        if (estimatedBytes != null && estimatedBytes >= 0) {
            command.put("estimatedBytes", estimatedBytes);
        }
        if (estimatedMaxRecordBytes != null && estimatedMaxRecordBytes >= 0) {
            command.put("estimatedMaxRecordBytes", estimatedMaxRecordBytes);
        }
        if ((estimatedRows != null && sparkRowThreshold > 0
                && estimatedRows >= sparkRowThreshold)
                || (estimatedBytes != null && sparkByteThreshold > 0
                && estimatedBytes >= sparkByteThreshold)) {
            command.put("executionMode", "SPARK");
        }

        int priority = workflow.getPriority() > 0 ? workflow.getPriority() : 3;
        String topic = resolveTopic(priority);
        String key = organizationId + ":" + workflow.getId();
        command.put("executionKey", key);

        SourceDataTransportService.InboundStreamPublication transport =
                sourceDataTransportService.publishInboundStream(
                        topic,
                        key,
                        organizationId,
                        workflow.getId(),
                        execLogId,
                        inputStream,
                        estimatedRows,
                        estimatedBytes,
                        estimatedMaxRecordBytes,
                        command);
        long recordCount = transport.recordCount();
        command.put("estimatedRows", recordCount);
        if (sparkRowThreshold > 0 && recordCount >= sparkRowThreshold) {
            command.put("executionMode", "SPARK");
        }

        publishAndAwait(topic, key, command);
        String dataTransport = String.valueOf(
                command.getOrDefault("dataTransport", "UNKNOWN"));
        log.info(
                "Flux INBOUND remis — workflowId={} execLogId={} rows={} transport={} topic={}",
                workflow.getId(), execLogId, recordCount, dataTransport, topic);
        return new InboundPublication(
                topic, key, command, dataTransport, recordCount, true);
    }

    Map<String, Object> buildOutboundDeliveryCommandPayload(
            WorkflowConfig workflow,
            String execLogId,
            String correlationId,
            String openhimTransactionId,
            List<Map<String, Object>> pivotRows) {
        List<Map<String, Object>> rows = pivotRows != null ? pivotRows : List.of();
        Map<String, Object> outboundConfig = workflow.getOutboundConfig() != null
                ? new LinkedHashMap<>(workflow.getOutboundConfig())
                : new LinkedHashMap<>();

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("eventType", EVENT_TYPE_OUTBOUND_DELIVERY_REQUESTED);
        command.put("workflowId", workflow.getId());
        command.put("execLogId", execLogId);
        command.put("workflowName", workflow.getWorkflowName());
        command.put("direction", "OUTBOUND");
        command.put("correlationId", correlationId);
        if (openhimTransactionId != null && !openhimTransactionId.isBlank()) {
            command.put("openhimTransactionId", openhimTransactionId);
        }
        command.put("outboundConfig", outboundConfig);
        command.put("targetStandardId", firstNonBlank(outboundConfig, "targetStandardId", "target_standard_id"));
        command.put("targetSystem", firstNonBlank(outboundConfig, "targetSystem", "target_system"));
        command.put("targetAdapter", firstNonBlank(outboundConfig, "targetAdapter", "target_adapter"));
        command.put("pivotRows", rows);
        command.put("rowCount", rows.size());
        command.put("requestedAt", Instant.now().toString());
        return command;
    }

    @SuppressWarnings("unchecked")
    String executionPartitionKey(WorkflowConfig workflow, Map<String, Object> command) {
        if (workflow.getDestinationConnectionId() != null
                && !workflow.getDestinationConnectionId().isBlank()) {
            return "destination:" + workflow.getDestinationConnectionId().trim();
        }
        Object rawSources = command.get("sources");
        if (rawSources instanceof List<?> sources && !sources.isEmpty()
                && sources.get(0) instanceof Map<?, ?> rawSource) {
            Map<String, Object> source = (Map<String, Object>) rawSource;
            Map<String, Object> config = mapValue(source.get("config"));
            Map<String, Object> target = mapValue(config.get("target_connection"));
            String connectionId = firstNonBlank(target, "connection_id", "connectionId");
            if (!connectionId.isBlank()) {
                return "destination:" + connectionId;
            }
            String dbType = firstNonBlank(target, "db_type", "dbType");
            String host = firstNonBlank(target, "host");
            String port = firstNonBlank(target, "port");
            String database = firstNonBlank(target, "database");
            if (!dbType.isBlank() || !host.isBlank() || !database.isBlank()) {
                return "destination:" + dbType + ":" + host + ":" + port + ":" + database;
            }
        }
        return "workflow:" + workflow.getId();
    }

    /**
     * Construit le payload de commande Kafka (extrait pour testabilité).
     *
     * Structure produite IDENTIQUE à l'historique : un tableau "sources" où chaque
     * source porte son "config" (dont target_connection). La seule évolution est la
     * PROVENANCE de target_connection : si le workflow a un destinationConnectionId,
     * la connexion nommée résolue est réinjectée dans CHAQUE source (host, port,
     * database, username et identifiant), en préservant le target_table propre à la
     * source. Sinon, le target_connection legacy par source est conservé tel quel.
     */
    Map<String, Object> buildCommandPayload(WorkflowConfig workflow, String execLogId) {
        int priority = workflow.getPriority() > 0 ? workflow.getPriority() : 3;

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("eventType",      EVENT_TYPE_PIPELINE_EXECUTION_REQUESTED);
        command.put("workflowId",     workflow.getId());
        command.put("execLogId",      execLogId);
        command.put("workflowName",   workflow.getWorkflowName());
        command.put("priority",       priority);
        command.put("schedule",       workflow.getSchedule());
        if (workflow.getDirection() != null) {
            command.put("direction", workflow.getDirection().name());
        }
        if (workflow.getStandardId() != null && !workflow.getStandardId().isBlank()) {
            command.put("standardId", workflow.getStandardId());
        }
        if (workflow.getEstimatedRows() != null) {
            command.put("estimatedRows", workflow.getEstimatedRows());
        }

        // Build the sources list as mutable maps so we can resolve the destination into each.
        List<Map<String, Object>> sources = buildSourceMaps(workflow);
        resolveSourceConnections(sources, workflow.getCreatedBy());
        injectWorkflowMappings(workflow, sources);
        if (workflow.getFields() != null && !workflow.getFields().isEmpty()) {
            command.put("fieldMappings", workflow.getFields());
        }

        // Resolve the workflow-level destination connection (if any) and inject it into every source.
        Map<String, Object> resolvedTargetConnection = resolveTargetConnection(workflow);
        if (resolvedTargetConnection != null) {
            for (Map<String, Object> source : sources) {
                injectTargetConnection(source, resolvedTargetConnection);
            }
            log.debug("Destination résolue depuis connexion '{}' injectée dans {} source(s)",
                      workflow.getDestinationConnectionId(), sources.size());
        }

        // Incremental loading — PAR SOURCE (jamais à la racine) : chaque source porte
        // son load_mode / incremental_column / write_mode / last_watermark en snake_case.
        injectIncrementalOptions(workflow, sources);
        prepareSilverStageScripts(sources);

        command.put("sources", sources);

        // Gold config global (fusion SQL for multi-source): include if present
        Map<String, Object> gold = null;
        if (workflow.getGoldConfigGlobal() != null) {
            gold = objectMapper.convertValue(
                    workflow.getGoldConfigGlobal(),
                    objectMapper.getTypeFactory().constructMapType(
                            LinkedHashMap.class, String.class, Object.class));
            prepareGoldStageScript(gold, sources);
            command.put("gold_config_global", objectMapper.convertValue(gold, GoldConfigGlobal.class));
        }

        SourceLoadEstimatorService.LoadAssessment loadAssessment = assessLoad(workflow, sources, gold);
        if (!"NOT_ASSESSED".equals(loadAssessment.reason())) {
            command.put("loadAssessment", loadAssessment.toMap());
        }
        if (loadAssessment.estimatedRows() >= 0 && workflow.getEstimatedRows() == null) {
            command.put("estimatedRows", loadAssessment.estimatedRows());
        }
        command.put("executionMode", resolveExecutionMode(workflow, sources, gold, loadAssessment));
        return command;
    }

    private SourceLoadEstimatorService.LoadAssessment assessLoad(
            WorkflowConfig workflow,
            List<Map<String, Object>> sources,
            Map<String, Object> gold) {
        String requested = workflow.getExecutionMode() != null
                ? workflow.getExecutionMode().trim().toUpperCase(Locale.ROOT)
                : "";
        if ("SPARK".equals(requested) || hasDistributedStage(sources, gold)
                || sourceLoadEstimatorService == null) {
            return SourceLoadEstimatorService.LoadAssessment.notAssessed();
        }
        return sourceLoadEstimatorService.assess(sources, sparkRowThreshold);
    }

    private String resolveExecutionMode(
            WorkflowConfig workflow,
            List<Map<String, Object>> sources,
            Map<String, Object> gold,
            SourceLoadEstimatorService.LoadAssessment loadAssessment) {
        String requested = workflow.getExecutionMode() != null
                ? workflow.getExecutionMode().trim().toUpperCase(Locale.ROOT)
                : "";
        if ("SPARK".equals(requested)) {
            return "SPARK";
        }
        if (hasDistributedStage(sources, gold)) {
            log.info("Bascule SPARK: au moins une étape Silver/Gold utilise le traitement distribué (workflowId={})",
                    workflow.getId());
            return "SPARK";
        }
        long estimatedRows = workflow.getEstimatedRows() != null ? workflow.getEstimatedRows() : 0L;
        if (sparkRowThreshold > 0 && estimatedRows > sparkRowThreshold) {
            log.info("Bascule automatique SPARK: {} lignes estimées > seuil {} (workflowId={})",
                    estimatedRows, sparkRowThreshold, workflow.getId());
            return "SPARK";
        }
        if (loadAssessment.distributedRecommended()) {
            log.info("Bascule automatique SPARK: diagnostic={} rows={} bytes={} complete={} (workflowId={})",
                    loadAssessment.reason(), loadAssessment.estimatedRows(), loadAssessment.estimatedBytes(),
                    loadAssessment.complete(), workflow.getId());
            return "SPARK";
        }
        return "LOCAL";
    }

    private boolean hasDistributedStage(List<Map<String, Object>> sources, Map<String, Object> gold) {
        if (sources != null) {
            for (Map<String, Object> source : sources) {
                Map<String, Object> config = mapValue(source != null ? source.get("config") : null);
                Map<String, Object> silver = mapValue(config.get("silver_config"));
                if (stageEnabled(silver) && "SPARK".equalsIgnoreCase(firstNonBlank(silver, "execution_engine"))) {
                    return true;
                }
            }
        }
        return stageEnabled(gold) && "SPARK".equalsIgnoreCase(firstNonBlank(gold, "execution_engine"));
    }

    @SuppressWarnings("unchecked")
    private void prepareSilverStageScripts(List<Map<String, Object>> sources) {
        for (Map<String, Object> source : sources) {
            if (!(source.get("config") instanceof Map<?, ?> rawConfig)) {
                continue;
            }
            Map<String, Object> config = (Map<String, Object>) rawConfig;
            if (!(config.get("silver_config") instanceof Map<?, ?> rawSilver)) {
                continue;
            }
            Map<String, Object> silver = new LinkedHashMap<>((Map<String, Object>) rawSilver);
            if (!stageEnabled(silver) || "SPARK".equalsIgnoreCase(firstNonBlank(silver, "execution_engine"))) {
                config.put("silver_config", silver);
                continue;
            }
            String targetTable = firstNonBlank(silver, "target_table_silver", "targetTableSilver");
            Map<String, Object> target = mapValue(config.get("target_connection"));
            silver.put("elt_scripts_silver", combinedStageSql(
                    firstNonBlank(silver, "pre_sql", "preSql"),
                    firstNonBlank(silver, "elt_scripts_silver", "eltScriptsSilver"),
                    firstNonBlank(silver, "post_sql", "postSql"),
                    targetTable,
                    target,
                    silver.get("indexes")));
            config.put("silver_config", silver);
        }
    }

    private void prepareGoldStageScript(Map<String, Object> gold, List<Map<String, Object>> sources) {
        if (!stageEnabled(gold) || "SPARK".equalsIgnoreCase(firstNonBlank(gold, "execution_engine"))) {
            return;
        }
        Map<String, Object> target = sources.isEmpty()
                ? Map.of()
                : mapValue(mapValue(sources.get(0).get("config")).get("target_connection"));
        gold.put("elt_scripts_gold", combinedStageSql(
                firstNonBlank(gold, "pre_sql", "preSql"),
                firstNonBlank(gold, "elt_scripts_gold", "eltScriptsGold"),
                firstNonBlank(gold, "post_sql", "postSql"),
                firstNonBlank(gold, "target_table_gold", "targetTableGold"),
                target,
                gold.get("indexes")));
    }

    private String combinedStageSql(
            String preSql,
            String transformationSql,
            String postSql,
            String targetTable,
            Map<String, Object> target,
            Object rawIndexes) {
        List<String> statements = new java.util.ArrayList<>();
        addSql(statements, preSql);
        addSql(statements, transformationSql);
        addSql(statements, postSql);
        statements.addAll(indexStatements(targetTable, target, rawIndexes));
        return String.join(System.lineSeparator(), statements);
    }

    private void addSql(List<String> statements, String sql) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        String trimmed = sql.trim();
        statements.add(trimmed.endsWith(";") ? trimmed : trimmed + ";");
    }

    @SuppressWarnings("unchecked")
    private List<String> indexStatements(
            String targetTable,
            Map<String, Object> target,
            Object rawIndexes) {
        if (!(rawIndexes instanceof List<?> indexes) || indexes.isEmpty()) {
            return List.of();
        }
        String dbType = normalizeDbType(firstNonBlank(target, "db_type", "dbType"));
        if ("SNOWFLAKE".equals(dbType) || "REDSHIFT".equals(dbType)) {
            log.warn("Index déclaratifs ignorés pour {}: utilisez les options de clustering/sort key du moteur.", dbType);
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        for (Object rawIndex : indexes) {
            if (!(rawIndex instanceof Map<?, ?> rawDefinition)) {
                continue;
            }
            Map<String, Object> definition = new LinkedHashMap<>((Map<String, Object>) rawDefinition);
            Object rawColumns = definition.get("columns");
            if (!(rawColumns instanceof List<?> columns) || columns.isEmpty()) {
                continue;
            }
            List<String> names = columns.stream().map(String::valueOf).toList();
            String indexName = firstNonBlank(definition, "name");
            if (indexName.isBlank()) {
                indexName = ("idx_" + targetTable.replace('.', '_') + "_" + String.join("_", names))
                        .replaceAll("[^A-Za-z0-9_$]", "_");
            }
            boolean unique = Boolean.parseBoolean(String.valueOf(definition.getOrDefault("unique", false)));
            String create = "CREATE " + (unique ? "UNIQUE " : "") + "INDEX ";
            String columnList = String.join(", ", names);
            if ("POSTGRES".equals(dbType) || "SQLITE".equals(dbType)) {
                result.add(create + "IF NOT EXISTS " + indexName + " ON " + targetTable
                        + " (" + columnList + ");");
            } else if ("MSSQL".equals(dbType)) {
                result.add("IF NOT EXISTS (SELECT 1 FROM sys.indexes WHERE name = '" + indexName
                        + "') " + create + indexName + " ON " + targetTable + " (" + columnList + ");");
            } else {
                result.add(create + indexName + " ON " + targetTable + " (" + columnList + ");");
            }
        }
        return result;
    }

    private boolean stageEnabled(Map<String, Object> stage) {
        if (stage == null || stage.isEmpty()) {
            return false;
        }
        Object enabled = stage.get("enabled");
        return enabled == null || Boolean.parseBoolean(enabled.toString());
    }

    /**
     * Construit la commande Kafka INBOUND envoyée par le médiateur OpenHIM.
     *
     * Le workflow rattaché au standard fournit toujours les métadonnées de
     * médaillon (destination, Bronze/Silver/Gold). La source est marquée PUSH et
     * transporte le message déjà normalisé au pivot IOL.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildInboundPushCommandPayload(
            WorkflowConfig workflow,
            String execLogId,
            String standardId,
            String sourceSystem,
            String correlationId,
            String openhimTransactionId,
            Map<String, Object> pivot) {
        return buildInboundPushCommandPayload(
                workflow,
                execLogId,
                standardId,
                sourceSystem,
                correlationId,
                openhimTransactionId,
                pivot != null ? List.of(pivot) : List.of());
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> buildInboundPushCommandPayload(
            WorkflowConfig workflow,
            String execLogId,
            String standardId,
            String sourceSystem,
            String correlationId,
            String openhimTransactionId,
            List<Map<String, Object>> pivots) {
        return buildInboundPushCommandPayload(
                workflow,
                execLogId,
                standardId,
                sourceSystem,
                correlationId,
                openhimTransactionId,
                pivots,
                false);
    }

    public Map<String, Object> buildInboundPushStreamCommandPayload(
            WorkflowConfig workflow,
            String execLogId,
            String standardId,
            String sourceSystem,
            String correlationId,
            String openhimTransactionId) {
        return buildInboundPushCommandPayload(
                workflow,
                execLogId,
                standardId,
                sourceSystem,
                correlationId,
                openhimTransactionId,
                List.of(),
                true);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildInboundPushCommandPayload(
            WorkflowConfig workflow,
            String execLogId,
            String standardId,
            String sourceSystem,
            String correlationId,
            String openhimTransactionId,
            List<Map<String, Object>> pivots,
            boolean streaming) {
        if (!streaming && (pivots == null || pivots.isEmpty()
                || pivots.stream().anyMatch(pivot -> pivot == null || pivot.isEmpty()))) {
            throw new IllegalArgumentException("Le lot de pivots INBOUND ne peut pas etre vide.");
        }

        Map<String, Object> command = buildCommandPayload(workflow, execLogId);
        command.put("direction", "INBOUND");
        command.put("standardId", standardId);
        if (sourceSystem != null && !sourceSystem.isBlank()) {
            command.put("sourceSystem", sourceSystem);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            command.put("correlationId", correlationId);
        }
        if (openhimTransactionId != null && !openhimTransactionId.isBlank()) {
            command.put("openhimTransactionId", openhimTransactionId);
        }

        List<Map<String, Object>> sources = (List<Map<String, Object>>) command.get("sources");
        Map<String, Object> inboundSource = selectInboundPushSource(sources);
        sources.clear();
        sources.add(inboundSource);

        Object originalName = inboundSource.get("source_name");
        if (originalName != null && !"PUSH".equalsIgnoreCase(originalName.toString())) {
            inboundSource.put("original_source_name", originalName.toString());
        }
        inboundSource.put("source_name", "PUSH");
        inboundSource.put("type", "PUSH");

        Map<String, Object> config = inboundSource.get("config") instanceof Map<?, ?> existingConfig
                ? new LinkedHashMap<>((Map<String, Object>) existingConfig)
                : new LinkedHashMap<>();
        inboundSource.put("config", config);

        Map<String, Object> sourceConfig = config.get("source_config") instanceof Map<?, ?> existingSourceConfig
                ? new LinkedHashMap<>((Map<String, Object>) existingSourceConfig)
                : new LinkedHashMap<>();
        List<Map<String, Object>> pivotCopies = (pivots != null ? pivots : List.<Map<String, Object>>of()).stream()
                .map(LinkedHashMap::new)
                .map(copy -> (Map<String, Object>) copy)
                .toList();
        sourceConfig.put("mode", "PUSH");
        sourceConfig.put("format", "JSON");
        sourceConfig.put("already_pivot", true);
        if (pivotCopies.size() == 1) {
            sourceConfig.put("data", pivotCopies.get(0));
        }
        if (!pivotCopies.isEmpty()) {
            sourceConfig.put("records", pivotCopies);
        }
        if (streaming) {
            sourceConfig.put("streaming", true);
        }
        sourceConfig.put("standard_id", standardId);
        if (sourceSystem != null && !sourceSystem.isBlank()) {
            sourceConfig.put("source_system", sourceSystem);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            sourceConfig.put("correlation_id", correlationId);
        }
        if (openhimTransactionId != null && !openhimTransactionId.isBlank()) {
            sourceConfig.put("openhim_transaction_id", openhimTransactionId);
        }
        config.put("source_config", sourceConfig);

        config.put("load_mode", "FULL");
        config.put("write_mode", "append");
        java.util.LinkedHashSet<String> fields = new java.util.LinkedHashSet<>();
        pivotCopies.forEach(record -> fields.addAll(record.keySet()));
        if (!fields.isEmpty()) {
            config.putIfAbsent("fields", new java.util.ArrayList<>(fields));
        }
        return command;
    }

    /**
     * Injecte les options de chargement incrémental PAR SOURCE, en snake_case, dans
     * chaque config. Sépare le COMBIEN (incrémental) du QUAND (schedule) :
     *   - load_mode          : FULL | INCREMENTAL (défaut FULL)
     *   - incremental_column : colonne borne (optionnelle ; absente ⇒ FULL)
     *   - write_mode         : append | replace  (défaut append — Bronze immuable)
     *   - last_watermark     : borne du dernier run réussi POUR CETTE SOURCE (clé = target_table)
     *
     * Repli legacy : d'anciens workflows portent loadMode/incrementalColumn dans
     * schedule → valeurs par défaut appliquées à toutes les sources, sans casser l'existant.
     */
    @SuppressWarnings("unchecked")
    private void injectIncrementalOptions(WorkflowConfig workflow, List<Map<String, Object>> sources) {
        // Défauts globaux legacy issus de schedule (rétrocompat mono-source)
        Map<String, Object> schedule = workflow.getSchedule();
        Object globalLoadMode = schedule != null ? schedule.get("loadMode") : null;
        Object globalIncrementalColumn = schedule != null ? schedule.get("incrementalColumn") : null;

        // Watermarks du dernier run réussi (chargés paresseusement : au plus une requête)
        boolean watermarksLoaded = false;
        Map<String, String> lastWatermarks = null;
        String legacyWatermark = null;

        for (Map<String, Object> source : sources) {
            Map<String, Object> config = (Map<String, Object>) source.get("config");
            if (config == null) {
                config = new LinkedHashMap<>();
                source.put("config", config);
            }

            // load_mode : config (snake) sinon défaut global sinon FULL
            Object loadMode = config.get("load_mode");
            if (loadMode == null) loadMode = globalLoadMode;
            config.put("load_mode", loadMode != null ? loadMode.toString().toUpperCase() : "FULL");

            // write_mode : config sinon append (Bronze immuable)
            Object writeMode = config.get("write_mode");
            config.put("write_mode", writeMode != null ? writeMode.toString().toLowerCase() : "append");

            // incremental_column : config sinon défaut global (peut rester absent ⇒ FULL)
            Object incrementalColumn = config.get("incremental_column");
            if (incrementalColumn == null) incrementalColumn = globalIncrementalColumn;
            if (incrementalColumn == null || incrementalColumn.toString().isBlank()) {
                config.remove("incremental_column");
                config.remove("last_watermark");
                continue;
            }
            config.put("incremental_column", incrementalColumn.toString());

            // last_watermark : résolu depuis le dernier run réussi POUR CETTE SOURCE.
            if (!watermarksLoaded) {
                Optional<ExecutionLog> lastRun = executionLogRepository
                        .findFirstByWorkflowIdAndStatusOrderByEndTimeDesc(workflow.getId(), ExecutionStatus.SUCCESS);
                if (lastRun.isPresent()) {
                    lastWatermarks = lastRun.get().getLastSuccessfulWatermarks();
                    legacyWatermark = lastRun.get().getLastSuccessfulWatermark();
                }
                watermarksLoaded = true;
            }

            String targetTable = resolveTargetTable(config);
            String resolved = null;
            if (lastWatermarks != null && targetTable != null) {
                resolved = lastWatermarks.get(targetTable);
            }
            if (resolved == null) {
                resolved = legacyWatermark; // rétrocompat mono-source
            }
            if (resolved != null) {
                config.put("last_watermark", resolved);
                log.debug("Incremental source target_table={} : last_watermark={} (col={})",
                          targetTable, resolved, incrementalColumn);
            } else {
                // Pas de run précédent (et pas de seed manuel conservé dans config) → bootstrap FULL.
                log.debug("Incremental source target_table={} : aucun watermark précédent (bootstrap)", targetTable);
            }
        }
    }

    /** target_table de la source (racine du config ou dans target_connection). */
    private String resolveTargetTable(Map<String, Object> config) {
        Object tt = config.get("target_table");
        if (tt == null) {
            Object tc = config.get("target_connection");
            if (tc instanceof Map<?, ?> tcMap) {
                tt = tcMap.get("target_table");
            }
        }
        return tt != null ? tt.toString() : null;
    }

    /**
     * Normalise les sources du workflow en maps mutables {source_name, config}.
     * Préserve exactement la structure historique (sources[] OU fallback legacy mono-source).
     */
    private List<Map<String, Object>> buildSourceMaps(WorkflowConfig workflow) {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        List<SourceDefinition> sources = workflow.getSources();

        if (sources != null && !sources.isEmpty()) {
            for (SourceDefinition sd : sources) {
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("source_name", sd.getSourceName());
                Map<String, Object> config = sd.getConfig() != null
                        ? new LinkedHashMap<>(sd.getConfig())
                        : new LinkedHashMap<>();
                stripPerSourceGold(config, sd.getSourceName());
                source.put("config", config);
                result.add(source);
            }
        } else {
            // Fallback: construct legacy single-source from protocol/sourceConfig for backward compatibility
            Map<String, Object> legacySource = new LinkedHashMap<>();
            legacySource.put("source_name", workflow.getProtocol() != null ? workflow.getProtocol() : "UNKNOWN");
            Map<String, Object> config = new LinkedHashMap<>(workflow.getSourceConfig() != null ? workflow.getSourceConfig() : new LinkedHashMap<>());
            config.put("fields", workflow.getFields());
            stripPerSourceGold(config, workflow.getProtocol());
            legacySource.put("config", config);
            result.add(legacySource);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void injectWorkflowMappings(WorkflowConfig workflow, List<Map<String, Object>> sources) {
        if (workflow.getFields() == null || workflow.getFields().isEmpty() || sources == null) {
            return;
        }
        for (Map<String, Object> source : sources) {
            String sourceName = String.valueOf(source.getOrDefault("source_name", ""));
            List<Map<String, Object>> matching = workflow.getFields().stream()
                    .filter(mapping -> {
                        Object configured = mapping.getOrDefault("sourceName", mapping.get("source_name"));
                        return configured == null || configured.toString().isBlank()
                                || configured.toString().equalsIgnoreCase(sourceName);
                    })
                    .<Map<String, Object>>map(mapping -> new LinkedHashMap<>(mapping))
                    .toList();
            if (matching.isEmpty()) continue;

            Map<String, Object> config = source.get("config") instanceof Map<?, ?> rawConfig
                    ? (Map<String, Object>) rawConfig : new LinkedHashMap<>();
            Map<String, Object> sourceConfig = config.get("source_config") instanceof Map<?, ?> rawSourceConfig
                    ? new LinkedHashMap<>((Map<String, Object>) rawSourceConfig) : new LinkedHashMap<>();
            sourceConfig.put("mapping_config", matching);
            config.put("source_config", sourceConfig);
            source.put("config", config);
        }
    }

    /**
     * Rétrocompatibilité: retire tout gold_config par source d'un ancien workflow.
     * Le Gold est désormais UNIQUE au niveau workflow (gold_config_global). On
     * ignore proprement le gold_config par source (sans planter), avec un warning.
     */
    private void stripPerSourceGold(Map<String, Object> config, String sourceName) {
        if (config != null && config.remove("gold_config") != null) {
            log.warn("gold_config par source déprécié et ignoré pour la source '{}'. "
                   + "Définissez le Gold au niveau workflow (gold_config_global).", sourceName);
        }
    }

    /**
     * Résout la connexion de destination au niveau workflow en map prête à injecter.
     * Retourne null si aucun destinationConnectionId (→ comportement legacy conservé).
     */
    private Map<String, Object> resolveTargetConnection(WorkflowConfig workflow) {
        String connectionId = workflow.getDestinationConnectionId();
        if (connectionId == null || connectionId.isBlank()) {
            return null;
        }
        DestinationConnection conn = destinationConnectionService.getEntityByIdForOwner(
                connectionId,
                workflow.getCreatedBy());
        Map<String, Object> tc = new LinkedHashMap<>();
        tc.put("host",     destinationConnectionService.resolveRuntimeHost(conn.getHost()));
        tc.put("port",     conn.getPort());
        tc.put("database", conn.getDatabase());
        tc.put("username", conn.getUsername());
        tc.put("schema", conn.getSchema());
        if (conn.getAdditionalProperties() != null) {
            tc.put("additional_properties", new LinkedHashMap<>(conn.getAdditionalProperties()));
        }
        String dbType = normalizeDbType(conn.getDbType());
        tc.put("db_type", dbType);
        tc.put("connection_id", conn.getId());
        tc.put("connection_name", conn.getName());
        tc.put("hop_connection_name", hopConnectionName(dbType));
        return tc;
    }

    private String hopConnectionName(String dbType) {
        return switch (dbType) {
            case "POSTGRES" -> "IOL_DESTINATION_POSTGRES";
            case "MYSQL" -> "IOL_DESTINATION_MYSQL";
            case "MARIADB" -> "IOL_DESTINATION_MARIADB";
            case "MSSQL" -> "IOL_DESTINATION_MSSQL";
            case "ORACLE" -> "IOL_DESTINATION_ORACLE";
            case "SQLITE" -> "IOL_DESTINATION_SQLITE";
            case "SNOWFLAKE" -> "IOL_DESTINATION_SNOWFLAKE";
            case "REDSHIFT" -> "IOL_DESTINATION_REDSHIFT";
            default -> throw new IllegalArgumentException(
                    "Type de destination non pris en charge par le workflow Hop: " + dbType
                            + ". Destinations supportees: POSTGRES, MYSQL, MARIADB, MSSQL, ORACLE, SQLITE, SNOWFLAKE, REDSHIFT.");
        };
    }

    @SuppressWarnings("unchecked")
    private void resolveSourceConnections(List<Map<String, Object>> sources, String workflowOwner) {
        for (Map<String, Object> source : sources) {
            Object rawConfig = source.get("config");
            if (!(rawConfig instanceof Map<?, ?>)) {
                continue;
            }
            Map<String, Object> config = (Map<String, Object>) rawConfig;
            String connectionId = firstNonBlank(config, "source_connection_id", "sourceConnectionId");
            if (connectionId.isBlank()) {
                continue;
            }

            DestinationConnection connection = destinationConnectionService.getEntityByIdForOwner(
                    connectionId,
                    workflowOwner);
            String sourceProtocol = String.valueOf(source.getOrDefault("source_name", ""))
                    .trim().toUpperCase(Locale.ROOT);
            String connectionType = normalizeDbType(connection.getDbType());
            if (!sourceProtocol.isBlank() && !connectionType.isBlank() && !sourceProtocol.equals(connectionType)) {
                throw new IllegalArgumentException(
                        "La connexion source " + connection.getName() + " est de type " + connectionType
                                + " mais la source attend " + sourceProtocol + ".");
            }

            config.put("host", destinationConnectionService.resolveRuntimeHost(connection.getHost()));
            config.put("port", connection.getPort());
            config.put("database", connection.getDatabase());
            config.put("username", connection.getUsername());
            String sourcePassword = destinationConnectionService.resolvePassword(connection);
            config.put("password", sourcePassword);
            config.put("source_db_type", connectionType);
            config.put("uri", "direct-jdbc://" + connectionType.toLowerCase(Locale.ROOT));

            Map<String, Object> sourceConfig = mapValue(config.get("source_config"));
            Map<String, Object> directConnection = new LinkedHashMap<>();
            directConnection.put("host", destinationConnectionService.resolveRuntimeHost(connection.getHost()));
            directConnection.put("port", connection.getPort());
            directConnection.put("database", connection.getDatabase());
            directConnection.put("username", connection.getUsername());
            directConnection.put("password", sourcePassword);
            directConnection.put("db_type", connectionType);
            directConnection.put("schema", connection.getSchema());
            if (connection.getAdditionalProperties() != null) {
                directConnection.put("additional_properties", new LinkedHashMap<>(connection.getAdditionalProperties()));
            }
            sourceConfig.put("source_connection", directConnection);
            sourceConfig.putIfAbsent("jdbc_chunk_rows", 50_000);
            sourceConfig.putIfAbsent("connect_timeout_seconds", 15);
            config.put("source_config", sourceConfig);
            log.debug("Connexion source '{}' résolue pour {}", connectionId, sourceProtocol);
        }
    }

    private String normalizeDbType(String dbType) {
        String normalized = dbType == null ? "" : dbType.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "POSTGRESQL", "PG" -> "POSTGRES";
            case "SQLSERVER", "SQL_SERVER" -> "MSSQL";
            case "MARIA_DB" -> "MARIADB";
            default -> normalized;
        };
    }

    /**
     * Injecte/remplace config.target_connection avec les valeurs résolues, en
     * préservant le target_table propre à la source (déjà présent dans l'ancien
     * target_connection ou à la racine du config).
     */
    @SuppressWarnings("unchecked")
    private void injectTargetConnection(Map<String, Object> source, Map<String, Object> resolvedTargetConnection) {
        Map<String, Object> config = (Map<String, Object>) source.get("config");
        if (config == null) {
            config = new LinkedHashMap<>();
            source.put("config", config);
        }

        // Préserver le target_table propre à la source
        Object targetTable = null;
        Object existingTc = config.get("target_connection");
        if (existingTc instanceof Map<?, ?> existingMap) {
            targetTable = existingMap.get("target_table");
        }
        if (targetTable == null) {
            targetTable = config.get("target_table");
        }

        Map<String, Object> newTc = new LinkedHashMap<>(resolvedTargetConnection);
        if (targetTable != null) {
            newTc.put("target_table", targetTable);
        }
        config.put("target_connection", newTc);
    }

    /**
     * Derniere barriere avant serialisation Kafka. Les credentials peuvent vivre
     * quelques millisecondes dans une map de travail pendant l'extraction source,
     * mais aucune cle sensible ne doit survivre dans la commande transportee.
     */
    private void assertNoPlaintextCredential(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                String normalized = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
                if (Set.of("password", "authorization", "token", "apikey", "secret").contains(normalized)
                        && entry.getValue() != null
                        && !String.valueOf(entry.getValue()).isBlank()) {
                    throw new IllegalStateException(
                            "Credential en clair refuse avant publication Kafka: " + path + "." + key);
                }
                assertNoPlaintextCredential(entry.getValue(), path + "." + key);
            }
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            int index = 0;
            for (Object item : iterable) {
                assertNoPlaintextCredential(item, path + "[" + index++ + "]");
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Résout le topic Kafka en fonction de la priorité.
     *   1-2 → high   (urgent, passe devant les autres)
     *   3   → normal (défaut)
     *   4-5 → low    (non urgent, traité quand les autres sont vides)
     */
    public String topicForPriority(int priority) {
        return resolveTopic(priority);
    }

    private String resolveTopic(int priority) {
        if (priority <= 2) return topicHigh;
        if (priority >= 4) return topicLow;
        return topicNormal;
    }

    private String firstNonBlank(Map<String, Object> map, String... keys) {
        if (map == null) return "";
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> selectInboundPushSource(List<Map<String, Object>> sources) {
        if (sources == null || sources.isEmpty()) {
            throw new IllegalArgumentException("Workflow INBOUND sans source configuree.");
        }

        for (Map<String, Object> source : sources) {
            Object sourceName = source.get("source_name");
            Object type = source.get("type");
            if ("PUSH".equalsIgnoreCase(String.valueOf(sourceName))
                    || "PUSH".equalsIgnoreCase(String.valueOf(type))) {
                return source;
            }
        }

        if (sources.size() == 1) {
            return sources.get(0);
        }

        throw new IllegalArgumentException(
                "Workflow INBOUND multi-source ambigu: ajoutez une source PUSH explicite.");
    }

    private void enrichWithMetadataSections(Map<String, Object> event, String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) return;
        try {
            JsonNode meta = objectMapper.readTree(metadataJson);
            if (meta.has("sources"))       event.put("sources",      objectMapper.convertValue(meta.get("sources"),       Object.class));
            if (meta.has("silver_config")) event.put("silverConfig",  objectMapper.convertValue(meta.get("silver_config"), Object.class));
            if (meta.has("gold_config"))   event.put("goldConfig",    objectMapper.convertValue(meta.get("gold_config"),   Object.class));
        } catch (Exception e) {
            log.warn("Impossible d'enrichir l'événement depuis metadataJson: {}", e.getMessage());
        }
    }

    private String publish(String topic, String key, Map<String, Object> payload) {
        if (!kafkaEnabled) {
            log.warn("Kafka désactivé — événement non publié. Topic={} Key={}", topic, key);
            return "Kafka désactivé : événement préparé mais non publié.";
        }
        try {
            KafkaTemplate<String, String> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
            if (kafkaTemplate == null) return "KafkaTemplate indisponible.";
            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(topic, key, json);
            log.info("Publié → topic={} key={}", topic, key);
            return "Commande publiée dans Kafka [" + topic + "].";
        } catch (Exception e) {
            log.error("Erreur publication Kafka topic={}: {}", topic, e.getMessage(), e);
            return "Erreur publication Kafka: " + e.getMessage();
        }
    }

    private void publishAndAwait(String topic, String key, Map<String, Object> payload) {
        try {
            KafkaTemplate<String, String> kafkaTemplate = kafkaTemplateProvider.getIfAvailable();
            if (kafkaTemplate == null) {
                throw new IllegalStateException("KafkaTemplate indisponible.");
            }
            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(topic, key, json).get(60, TimeUnit.SECONDS);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Publication Kafka INBOUND impossible: " + error.getMessage(), error);
        }
    }

    public record InboundPublication(
            String topic,
            String key,
            Map<String, Object> command,
            String dataTransport,
            long recordCount,
            boolean published) {
    }
}
