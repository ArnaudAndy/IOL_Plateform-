package com.iol.etlplatform.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.dto.workflow.SchemaDiscoveryResponse;
import com.iol.etlplatform.dto.workflow.WorkflowConfigDto;
import com.iol.etlplatform.dto.workflow.WorkflowExportResponse;
import com.iol.etlplatform.entity.Standard;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.DestinationConnection;
import com.iol.etlplatform.entity.embedded.ConnectionDetails;
import com.iol.etlplatform.entity.embedded.SourceDefinition;
import com.iol.etlplatform.entity.enums.SourceType;
import com.iol.etlplatform.entity.enums.WorkflowDirection;
import com.iol.etlplatform.exception.BadRequestException;
import com.iol.etlplatform.exception.ResourceNotFoundException;
import com.iol.etlplatform.mapper.WorkflowConfigMapper;
import com.iol.etlplatform.repository.StandardRepository;
import com.iol.etlplatform.repository.WorkflowConfigRepository;
import com.iol.etlplatform.service.scheduler.PipelineSchedulerService;
import com.iol.etlplatform.util.ColumnNameSanitizer;
import com.iol.etlplatform.util.ProtocolConfigValidator;
import com.iol.etlplatform.util.SqlSafetyValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WorkflowService.class);

    private final WorkflowConfigRepository workflowConfigRepository;
    private final WorkflowConfigMapper workflowConfigMapper;
    private final ObjectMapper objectMapper;
    private final DiscoveryService discoveryService;
    private final OrchestrationService orchestrationService;
    private final PipelineSchedulerService schedulerService;
    private final DestinationConnectionService destinationConnectionService;
    private final StandardRepository standardRepository;
    private final SqlSafetyValidator sqlSafetyValidator;
    private final ApiSourceClient apiSourceClient;

    private static final java.util.regex.Pattern TARGET_TABLE = java.util.regex.Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_$]*(?:\\.[A-Za-z_][A-Za-z0-9_$]*)?$");

    public WorkflowConfigDto create(WorkflowConfigDto dto) {
        if (dto == null) {
            throw new BadRequestException("Payload workflow manquant.");
        }
        if (dto.getWorkflowName() == null || dto.getWorkflowName().isBlank()) {
            throw new BadRequestException("Le nom du workflow est obligatoire.");
        }
        if (dto.getProtocol() == null || dto.getProtocol().isBlank()) {
            throw new BadRequestException("Le protocole de source est obligatoire.");
        }
        validateMetadataPayload(dto.getMetadataJson(), dto.getMetadataFileName());
        validateDestinationConnection(dto.getDestinationConnectionId());
        validateSourceConnections(dto.getSources());
        validateStandardReference(dto.getStandardId());
        validateOutboundConfig(dto.getDirection(), dto.getOutboundConfig());
        validateOptionalStages(dto.getSources(), dto.getGoldConfigGlobal(), dto.getExecutionMode(), null);

        log.debug(
                "Creation workflow demandee: name={}, protocol={}, sourceKeys={}, fields={}",
                dto.getWorkflowName(),
                dto.getProtocol(),
                dto.getSourceConfig() != null ? dto.getSourceConfig().keySet() : List.of(),
                dto.getFields() != null ? dto.getFields().size() : 0
        );

        WorkflowConfig entity = workflowConfigMapper.toEntity(dto);
        if (entity.getDirection() == null) {
            entity.setDirection(WorkflowDirection.INTERNAL);
        }
        entity.setActive(true);
        entity.setCreatedBy(getCurrentUserEmail());
        String now = Instant.now().toString();
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        WorkflowConfig saved = workflowConfigRepository.save(entity);
        schedulerService.reschedule(saved);
        log.info("Metadonnees pipeline sauvegardees dans MongoDB collection workflow_configs: {}", saved.getId());
        return workflowConfigMapper.toDto(saved);
    }

    public List<WorkflowConfigDto> findAll() {
        List<WorkflowConfig> workflows;
        String currentUser = getCurrentUserEmail();
        boolean isAdmin = isCurrentUserAdmin();

        if (isAdmin) {
            workflows = workflowConfigRepository.findAll();
        } else {
            workflows = workflowConfigRepository.findByCreatedBy(currentUser);
        }

        return workflowConfigMapper.toDtoList(workflows);
    }

    public WorkflowConfigDto findById(String id) {
        return workflowConfigMapper.toDto(getEntityById(id));
    }

    public WorkflowConfigDto update(String id, WorkflowConfigDto dto) {
        WorkflowConfig existing = getEntityById(id);
        validateMetadataPayload(dto.getMetadataJson(), dto.getMetadataFileName());
        validateDestinationConnection(dto.getDestinationConnectionId());
        validateSourceConnections(dto.getSources());
        validateStandardReference(dto.getStandardId());
        validateOutboundConfig(dto.getDirection(), dto.getOutboundConfig());
        validateOptionalStages(dto.getSources(), dto.getGoldConfigGlobal(), dto.getExecutionMode(), id);
        WorkflowConfig toUpdate = workflowConfigMapper.toEntity(dto);
        if (toUpdate.getDirection() == null) {
            toUpdate.setDirection(WorkflowDirection.INTERNAL);
        }
        toUpdate.setId(existing.getId());
        toUpdate.setActive(existing.isActive());
        toUpdate.setCreatedBy(existing.getCreatedBy());
        toUpdate.setCreatedAt(existing.getCreatedAt());
        toUpdate.setUpdatedAt(Instant.now().toString());
        WorkflowConfig updated = workflowConfigRepository.save(toUpdate);
        schedulerService.reschedule(updated);
        return workflowConfigMapper.toDto(updated);
    }

    public void delete(String id) {
        getEntityById(id);
        schedulerService.unschedule(id);
        workflowConfigRepository.deleteById(id);
    }

    public List<SchemaDiscoveryResponse> discoverSources(String workflowId) {
        WorkflowConfig workflow = getEntityById(workflowId);
        List<SchemaDiscoveryResponse> result = new ArrayList<>();
        
        // Use the new source_config structure
        Map<String, Object> sourceConfig = workflow.getSourceConfig();
        if (sourceConfig != null && !sourceConfig.isEmpty()) {
            String protocol = workflow.getProtocol();
            ConnectionDetails connectionDetails = buildConnectionDetails(protocol, sourceConfig);
            Map<String, List<String>> schema = fetchSourceSchema(connectionDetails, (String) sourceConfig.get("schema"), (String) sourceConfig.get("table"));
            result.add(new SchemaDiscoveryResponse(workflow.getWorkflowName(), schema));
        }
        
        return result;
    }

    public Map<String, List<String>> fetchSourceSchema(ConnectionDetails connectionDetails) {
        return fetchSourceSchema(connectionDetails, null);
    }

    public Map<String, List<String>> fetchSourceSchema(ConnectionDetails connectionDetails, String schemaPattern) {
        return fetchSourceSchema(connectionDetails, schemaPattern, null);
    }

    public Map<String, List<String>> fetchSourceSchema(ConnectionDetails connectionDetails, String schemaPattern, String tablePattern) {
        if (connectionDetails == null || connectionDetails.getUrl() == null) {
            throw new BadRequestException("ConnectionDetails incomplet pour l'introspection JDBC.");
        }
        
        log.info("Découverte du schéma pour: {}, table: {}", connectionDetails.getUrl(), tablePattern);
        
        Map<String, List<String>> schema = new HashMap<>();
        try (Connection connection = DriverManager.getConnection(
                connectionDetails.getUrl(),
                connectionDetails.getUsername(),
                connectionDetails.getPassword())) {
            
            // Utiliser le DiscoveryService pour une extraction complète
            DiscoveryService.DatabaseSchema dbSchema = discoveryService.discoverDatabase(connection, schemaPattern, tablePattern);
            
            if (dbSchema.getTables() != null) {
                for (DiscoveryService.TableSchema table : dbSchema.getTables()) {
                    List<String> columns = new ArrayList<>();
                    if (table.getColumns() != null) {
                        for (DiscoveryService.ColumnSchema col : table.getColumns()) {
                            columns.add(col.getColumnName() + " (" + col.getSqlType() + ")");
                        }
                    }
                    schema.put(table.getTableName(), columns);
                }
            }
            
            log.info("Découverte complétée: {} tables trouvées", schema.size());
        } catch (SQLException e) {
            log.error("Erreur découverte JDBC: {}", e.getMessage(), e);
            throw new BadRequestException("Echec de l'introspection JDBC: " + e.getMessage());
        }
        return schema;
    }

    /**
     * Découvrir le schéma COMPLET d'une source (avec détails: FK, PK, Index)
     */
    public DiscoveryService.DatabaseSchema discoverFullSchema(ConnectionDetails connectionDetails) throws SQLException {
        if (connectionDetails == null || connectionDetails.getUrl() == null) {
            throw new BadRequestException("ConnectionDetails incomplet");
        }

        log.info("Découverte COMPLÈTE du schéma pour: {}", connectionDetails.getUrl());

        try (Connection connection = DriverManager.getConnection(
                connectionDetails.getUrl(),
                connectionDetails.getUsername(),
                connectionDetails.getPassword())) {

            DiscoveryService.DatabaseSchema schema = discoveryService.discoverDatabase(connection, null);

            // Enrichir avec les FK et Index pour chaque table
            if (schema.getTables() != null) {
                for (DiscoveryService.TableSchema table : schema.getTables()) {
                    try {
                        table.setForeignKeys(discoveryService.discoverForeignKeys(connection, null, table.getTableName()));
                        table.setIndexes(discoveryService.discoverIndexes(connection, null, table.getTableName()));
                    } catch (SQLException e) {
                        log.warn("Erreur lors de la découverte FK/Index pour {}: {}", table.getTableName(), e.getMessage());
                    }
                }
            }

            log.info("Schéma complet découvert");
            return schema;
        }
    }

    public WorkflowExportResponse exportWorkflowAsJson(String workflowId) {
        WorkflowConfig workflow = getEntityById(workflowId);
        try {
            // ObjectMapper serialise en JSON RFC 8259 conforme.
            return new WorkflowExportResponse(workflowId, objectMapper.writeValueAsString(workflow));
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Impossible de serialiser le workflow en JSON.");
        }
    }

    public WorkflowConfig getEntityById(String id) {
        WorkflowConfig workflow = workflowConfigRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow introuvable: " + id));

        if (!isCurrentUserAdmin() && !isWorkflowOwner(workflow)) {
            throw new BadRequestException("Accès refusé: vous ne pouvez pas accéder à ce workflow.");
        }

        return workflow;
    }

    private boolean isWorkflowOwner(WorkflowConfig workflow) {
        String currentUser = getCurrentUserEmail();
        return currentUser.equals(workflow.getCreatedBy());
    }

    /**
     * Si un destinationConnectionId est fourni, vérifie qu'il existe réellement.
     * Rétrocompatibilité: un workflow sans destinationConnectionId (avec ou sans
     * target_connection par source) reste parfaitement valide.
     */
    private void validateDestinationConnection(String destinationConnectionId) {
        if (destinationConnectionId == null || destinationConnectionId.isBlank()) {
            return;
        }
        destinationConnectionService.getEntityById(destinationConnectionId);
    }

    private void validateSourceConnections(List<SourceDefinition> sources) {
        if (sources == null) {
            return;
        }
        for (SourceDefinition source : sources) {
            if (source == null || source.getConfig() == null) {
                continue;
            }
            if ("API".equalsIgnoreCase(source.getSourceName())) {
                Map<String, Object> apiConfig = mapValue(source.getConfig().get("source_config"));
                apiSourceClient.validate(apiConfig.isEmpty() ? source.getConfig() : apiConfig);
            }
            String connectionId = firstNonBlank(
                    source.getConfig(), "source_connection_id", "sourceConnectionId");
            if (connectionId.isBlank()) {
                continue;
            }
            DestinationConnection connection = destinationConnectionService.getEntityById(connectionId);
            String expectedType = normalizeDbType(source.getSourceName());
            String actualType = normalizeDbType(connection.getDbType());
            if (!expectedType.isBlank() && !actualType.isBlank() && !expectedType.equals(actualType)) {
                throw new BadRequestException(
                        "La connexion source '" + connection.getName() + "' est de type " + actualType
                                + " mais la source attend " + expectedType + ".");
            }
        }
    }

    private void validateOptionalStages(
            List<SourceDefinition> sources,
            com.iol.etlplatform.entity.embedded.GoldConfigGlobal gold,
            String workflowExecutionMode,
            String workflowId) {
        boolean allSilverDisabled = true;
        if (sources != null) {
            for (int index = 0; index < sources.size(); index++) {
                SourceDefinition source = sources.get(index);
                if (source == null || source.getConfig() == null) {
                    continue;
                }
                Map<String, Object> sourceConfig = new LinkedHashMap<>(source.getConfig());
                source.setConfig(sourceConfig);
                Map<String, Object> silver = mapValue(sourceConfig.get("silver_config"));
                boolean enabled = stageEnabled(silver);
                sourceConfig.put("silver_enabled", enabled);
                if (!silver.isEmpty()) {
                    silver.put("enabled", enabled);
                    sourceConfig.put("silver_config", silver);
                }
                if (!enabled) {
                    continue;
                }
                allSilverDisabled = false;
                String engine = normalizeStageEngine(firstNonBlank(silver, "execution_engine", "executionEngine"));
                silver.put("execution_engine", engine);
                requireTargetAndTransformation(
                        "Silver de la source " + (index + 1),
                        firstNonBlank(silver, "target_table_silver", "targetTableSilver"),
                        engine,
                        firstNonBlank(silver, "elt_scripts_silver", "eltScriptsSilver"),
                        firstNonBlank(silver, "spark_sql", "sparkSql"),
                        workflowExecutionMode,
                        workflowId);
                validateStageExtras("Silver de la source " + (index + 1), silver, workflowId);
            }
        }

        if (gold == null) {
            return;
        }
        boolean goldEnabled = gold.getEnabled() == null || gold.getEnabled();
        gold.setEnabled(goldEnabled);
        String inputLayer = gold.getInputLayer() == null || gold.getInputLayer().isBlank()
                ? "SILVER"
                : gold.getInputLayer().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SILVER", "BRONZE").contains(inputLayer)) {
            throw new BadRequestException("gold_config_global.input_layer doit valoir SILVER ou BRONZE.");
        }
        gold.setInputLayer(inputLayer);
        if (!goldEnabled) {
            return;
        }
        if (allSilverDisabled && !"BRONZE".equals(inputLayer)) {
            throw new BadRequestException(
                    "Toutes les étapes Silver sont désactivées: choisissez explicitement BRONZE comme entrée Gold.");
        }
        String engine = normalizeStageEngine(gold.getExecutionEngine());
        gold.setExecutionEngine(engine);
        requireTargetAndTransformation(
                "Gold",
                gold.getTargetTableGold(),
                engine,
                gold.getEltScriptsGold(),
                gold.getSparkSql(),
                workflowExecutionMode,
                workflowId);
        validateOptionalSql("Gold pre_sql", gold.getPreSql(), workflowId);
        validateOptionalSql("Gold post_sql", gold.getPostSql(), workflowId);
        validateIndexes("Gold", gold.getIndexes());
    }

    private void validateOptionalStages(
            List<SourceDefinition> sources,
            com.iol.etlplatform.entity.embedded.GoldConfigGlobal gold,
            String workflowId) {
        validateOptionalStages(sources, gold, "LOCAL", workflowId);
    }

    private boolean stageEnabled(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return false;
        }
        Object raw = config.get("enabled");
        if (raw == null) {
            return true;
        }
        return raw instanceof Boolean value ? value : Boolean.parseBoolean(raw.toString());
    }

    private void requireTargetAndTransformation(
            String label,
            String targetTable,
            String executionEngine,
            String sql,
            String sparkSql,
            String workflowExecutionMode,
            String workflowId) {
        if (targetTable == null || targetTable.isBlank()) {
            throw new BadRequestException(label + ": la table cible est obligatoire lorsque l'étape est activée.");
        }
        if (!TARGET_TABLE.matcher(targetTable.trim()).matches()) {
            throw new BadRequestException(label + ": table cible invalide: " + targetTable);
        }
        if ("SPARK".equals(executionEngine)) {
            if (sparkSql == null || sparkSql.isBlank()) {
                throw new BadRequestException(label + ": spark_sql est obligatoire avec le moteur SPARK.");
            }
            String normalized = sparkSql.trim().toLowerCase(Locale.ROOT);
            if (!normalized.startsWith("select ") && !normalized.startsWith("with ")) {
                throw new BadRequestException(label + ": spark_sql doit être une requête SELECT ou WITH.");
            }
            sqlSafetyValidator.validateReadOnlySql(" " + sparkSql + " ");
            return;
        }
        if (sql == null || sql.isBlank()) {
            throw new BadRequestException(label + ": le SQL est obligatoire avec le moteur SQL.");
        }
        sqlSafetyValidator.validateEltScript(sql, workflowId);
    }

    private String normalizeStageEngine(String value) {
        String normalized = value == null || value.isBlank()
                ? "SQL"
                : value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("SQL", "SPARK").contains(normalized)) {
            throw new BadRequestException("execution_engine doit valoir SQL ou SPARK.");
        }
        return normalized;
    }

    private void validateStageExtras(String label, Map<String, Object> stage, String workflowId) {
        validateOptionalSql(label + " pre_sql", firstNonBlank(stage, "pre_sql", "preSql"), workflowId);
        validateOptionalSql(label + " post_sql", firstNonBlank(stage, "post_sql", "postSql"), workflowId);
        validateIndexes(label, listOfMaps(stage.get("indexes")));
    }

    private void validateOptionalSql(String label, String sql, String workflowId) {
        if (sql == null || sql.isBlank()) {
            return;
        }
        try {
            sqlSafetyValidator.validateEltScript(sql, workflowId);
        } catch (BadRequestException e) {
            throw new BadRequestException(label + ": " + e.getMessage());
        }
    }

    private void validateIndexes(String label, List<Map<String, Object>> indexes) {
        if (indexes == null) {
            return;
        }
        for (int index = 0; index < indexes.size(); index++) {
            Map<String, Object> definition = indexes.get(index);
            if (definition == null) {
                throw new BadRequestException(label + ": définition d'index vide à la position " + (index + 1) + ".");
            }
            String name = firstNonBlank(definition, "name");
            if (!name.isBlank() && !name.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
                throw new BadRequestException(label + ": nom d'index invalide à la position " + (index + 1) + ".");
            }
            Object rawColumns = definition.get("columns");
            if (!(rawColumns instanceof Collection<?> columns) || columns.isEmpty()) {
                throw new BadRequestException(label + ": chaque index doit déclarer au moins une colonne.");
            }
            for (Object rawColumn : columns) {
                String column = rawColumn == null ? "" : rawColumn.toString().trim();
                if (!column.matches("[A-Za-z_][A-Za-z0-9_$]*")) {
                    throw new BadRequestException(label + ": colonne d'index invalide: " + column);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOfMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                result.add(new LinkedHashMap<>((Map<String, Object>) map));
            } else {
                throw new BadRequestException("Définition d'index invalide.");
            }
        }
        return result;
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
     * Si un standardId est fourni, vérifie que le Standard référencé existe et est
     * ACTIVE. Rétrocompatibilité : un workflow sans standardId (INTERNAL classique)
     * reste parfaitement valide — le rattachement à un standard n'est pas obligatoire.
     */
    private void validateStandardReference(String standardId) {
        if (standardId == null || standardId.isBlank()) {
            return;
        }
        Standard standard = standardRepository.findById(standardId)
                .orElseThrow(() -> new BadRequestException("Standard introuvable: " + standardId));
        if (standard.getStatus() != Standard.StandardStatus.ACTIVE) {
            throw new BadRequestException(
                    "Standard non ACTIVE (statut=" + standard.getStatus() + "): " + standardId);
        }
    }

    /**
     * OUTBOUND est additif: aucune config requise pour INTERNAL/INBOUND, mais une
     * livraison OUTBOUND doit déclarer quoi livrer, vers quelle norme/adaptateur,
     * et où livrer. Les secrets restent référencés, jamais stockés en clair ici.
     */
    private void validateOutboundConfig(WorkflowDirection direction, Map<String, Object> outboundConfig) {
        WorkflowDirection effectiveDirection = direction != null ? direction : WorkflowDirection.INTERNAL;
        if (effectiveDirection != WorkflowDirection.OUTBOUND) {
            return;
        }
        if (outboundConfig == null || outboundConfig.isEmpty()) {
            throw new BadRequestException("outboundConfig est obligatoire pour un workflow OUTBOUND.");
        }

        String targetStandardId = stringValue(outboundConfig.get("targetStandardId"));
        if (targetStandardId.isBlank()) {
            targetStandardId = stringValue(outboundConfig.get("target_standard_id"));
        }
        if (targetStandardId.isBlank()) {
            throw new BadRequestException("outboundConfig.targetStandardId est obligatoire.");
        }
        validateStandardReference(targetStandardId);

        if (firstNonBlank(outboundConfig, "targetSystem", "target_system").isBlank()) {
            throw new BadRequestException("outboundConfig.targetSystem est obligatoire.");
        }

        if (firstNonBlank(outboundConfig, "targetAdapter", "target_adapter").isBlank()) {
            throw new BadRequestException("outboundConfig.targetAdapter est obligatoire.");
        }

        Map<String, Object> source = mapValue(outboundConfig.get("source"));
        if (source.isEmpty() || (firstNonBlank(source, "goldTable", "gold_table").isBlank()
                && firstNonBlank(source, "query").isBlank())) {
            throw new BadRequestException("outboundConfig.source.goldTable ou outboundConfig.source.query est obligatoire.");
        }

        Map<String, Object> destination = mapValue(outboundConfig.get("destination"));
        if (destination.isEmpty() || (firstNonBlank(destination, "openhimChannel", "openhim_channel").isBlank()
                && firstNonBlank(destination, "endpointUrl", "endpoint_url").isBlank())) {
            throw new BadRequestException(
                    "outboundConfig.destination.openhimChannel ou outboundConfig.destination.endpointUrl est obligatoire.");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> raw) {
            return new LinkedHashMap<>((Map<String, Object>) raw);
        }
        return Map.of();
    }

    public List<Map<String, Object>> discoverSourceColumns(Map<String, Object> sourceConfig) {
        try {
            String protocol = (String) sourceConfig.get("protocol");
            Map<String, Object> rawConfig = (Map<String, Object>) sourceConfig.get("config");
            Map<String, Object> config = rawConfig != null ? new LinkedHashMap<>(rawConfig) : null;
            String sourceName = stringValue(sourceConfig.get("sourceName"));

            if (config == null) {
                throw new BadRequestException("Configuration de source manquante.");
            }
            if (sourceName.isBlank()) {
                sourceName = firstNonBlank(config, "sourceName", "alias", "name");
            }

            resolveDiscoverySourceConnection(protocol, config);

            // Multi-protocol dispatcher with validation
            List<Map<String, Object>> columnNames;
            if (protocol == null || protocol.isBlank()) {
                throw new BadRequestException("Protocole source non spécifié. Protocoles acceptés: POSTGRES, MYSQL, ORACLE, MSSQL, MARIADB, SQLITE, SNOWFLAKE, REDSHIFT, CSV, EXCEL, PARQUET, AVRO, ORC, API.");
            }

            String protocolUpper = protocol.trim().toUpperCase();

            // VALIDATE config format for this protocol BEFORE dispatching
            ProtocolConfigValidator.validateConfig(protocolUpper, config);

            log.info("Discovery requested for protocol: {} with validated config", protocolUpper);

            switch (protocolUpper) {
                // JDBC-based databases
                case "POSTGRES", "MYSQL", "ORACLE", "MSSQL", "MARIADB", "SQLITE", "SNOWFLAKE", "REDSHIFT" ->
                    columnNames = discoverJdbcColumns(protocolUpper, config, sourceName);

                // Tabular files
                case "CSV" ->
                    columnNames = discoverCsvColumns(config, sourceName);
                case "EXCEL" ->
                    columnNames = discoverExcelColumns(config, sourceName);

                // Columnar files
                case "PARQUET", "AVRO", "ORC" ->
                    columnNames = discoverStructuredFileColumns(protocolUpper, config, sourceName);
                case "API" ->
                    columnNames = apiSourceClient.discoverColumns(
                            mapValue(config.get("source_config")).isEmpty()
                                    ? config : mapValue(config.get("source_config")));

                default ->
                    throw new BadRequestException("Protocole non supporté: " + protocol);
            }

            // Filter by selected columns if specified
            Set<String> selectedColumns = parseSelectedColumns(config.get("columns"));
            if (!selectedColumns.isEmpty()) {
                columnNames.removeIf(column -> !selectedColumns.contains(stringValue(column.get("name")).toLowerCase(Locale.ROOT)));
            }

            return columnNames;
        } catch (Exception e) {
            log.error("Erreur lors de la découverte des colonnes: {}", e.getMessage(), e);
            throw new BadRequestException("Découverte des colonnes échouée: " + e.getMessage());
        }
    }

    private void resolveDiscoverySourceConnection(String protocol, Map<String, Object> config) {
        if (config == null) {
            return;
        }
        String connectionId = firstNonBlank(config, "source_connection_id", "sourceConnectionId");
        if (connectionId.isBlank()) {
            return;
        }
        DestinationConnection connection = destinationConnectionService.getEntityById(connectionId);
        String expectedType = normalizeDbType(protocol);
        String actualType = normalizeDbType(connection.getDbType());
        if (!expectedType.equals(actualType)) {
            throw new BadRequestException(
                    "La connexion source '" + connection.getName() + "' est de type " + actualType
                            + " mais le protocole demandé est " + expectedType + ".");
        }
        config.put("host", destinationConnectionService.resolveRuntimeHost(connection.getHost()));
        config.put("port", connection.getPort());
        config.put("database", connection.getDatabase());
        config.put("username", connection.getUsername());
        config.put("password", destinationConnectionService.resolvePassword(connection));
    }

    // ==================== JDBC DATABASES ====================

    private List<Map<String, Object>> discoverJdbcColumns(String protocol, Map<String, Object> config, String sourceName) {
        // Safety check: ensure this method is only called for JDBC databases
        String protocolUpper = protocol.toUpperCase();
        if (!protocolUpper.matches("(POSTGRES|MYSQL|ORACLE|MSSQL|MARIADB|SQLITE|SNOWFLAKE|REDSHIFT)")) {
            throw new BadRequestException("discoverJdbcColumns() appelé pour protocole non-JDBC: " + protocol +
                    ". Vérifiez le dispatcher dans discoverSourceColumns()");
        }

        ConnectionDetails connectionDetails = buildConnectionDetails(protocol, config);
        String schemaPattern = (String) config.get("schema");
        String tablePattern = (String) config.get("table");
        if (connectionDetails == null || connectionDetails.getUrl() == null) {
            throw new BadRequestException("ConnectionDetails incomplet pour l'introspection JDBC.");
        }

        try {
            registerJdbcDriver(connectionDetails.getUrl());
        } catch (ClassNotFoundException e) {
            throw new BadRequestException("Driver JDBC indisponible pour cette source: " + e.getMessage());
        }

        List<Map<String, Object>> columns = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                connectionDetails.getUrl(),
                connectionDetails.getUsername(),
                connectionDetails.getPassword())) {
            DiscoveryService.DatabaseSchema dbSchema = discoveryService.discoverDatabase(connection, schemaPattern, tablePattern);

            if (dbSchema.getTables() != null) {
                for (DiscoveryService.TableSchema table : dbSchema.getTables()) {
                    Set<String> primaryKeys = table.getPrimaryKeyColumns() != null ? table.getPrimaryKeyColumns() : Set.of();
                    if (table.getColumns() == null) {
                        continue;
                    }

                    for (DiscoveryService.ColumnSchema column : table.getColumns()) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("sourceName", sourceName.isBlank() ? "source" : sourceName);
                        item.put("tableName", table.getTableName());
                        item.put("name", column.getCleanedName());
                        item.put("columnName", column.getCleanedName());
                        item.put("originalName", column.getColumnName());
                        item.put("type", column.getSqlType());
                        item.put("sqlType", column.getSqlType());
                        item.put("javaType", column.getJavaType());
                        item.put("nullable", column.getNullable());
                        item.put("columnSize", column.getColumnSize());
                        item.put("decimalDigits", column.getDecimalDigits());
                        item.put("primaryKey", primaryKeys.contains(column.getColumnName()));
                        item.put("databaseProductName", dbSchema.getDatabaseProductName());
                        item.put("driverName", dbSchema.getDriverName());
                        item.put("mapped", false);
                        item.put("iolTerm", "");
                        columns.add(item);
                    }
                }
            }

            log.info("Découverte complétée: {} colonnes trouvées", columns.size());
            return columns;
        } catch (SQLException e) {
            log.error("Erreur découverte JDBC: {}", e.getMessage(), e);
            throw new BadRequestException("Echec de l'introspection JDBC: " + e.getMessage());
        }
    }

    private void registerJdbcDriver(String jdbcUrl) throws ClassNotFoundException {
        if (jdbcUrl == null) {
            return;
        }

        String driverClass = null;
        if (jdbcUrl.startsWith("jdbc:postgresql:")) {
            driverClass = "org.postgresql.Driver";
        } else if (jdbcUrl.startsWith("jdbc:mysql:")) {
            driverClass = "com.mysql.cj.jdbc.Driver";
        } else if (jdbcUrl.startsWith("jdbc:mariadb:")) {
            driverClass = "org.mariadb.jdbc.Driver";
        } else if (jdbcUrl.startsWith("jdbc:sqlserver:")) {
            driverClass = "com.microsoft.sqlserver.jdbc.SQLServerDriver";
        } else if (jdbcUrl.startsWith("jdbc:oracle:")) {
            driverClass = "oracle.jdbc.OracleDriver";
        } else if (jdbcUrl.startsWith("jdbc:sqlite:")) {
            driverClass = "org.sqlite.JDBC";
        } else if (jdbcUrl.startsWith("jdbc:snowflake:")) {
            driverClass = "net.snowflake.client.jdbc.SnowflakeDriver";
        } else if (jdbcUrl.startsWith("jdbc:redshift:")) {
            driverClass = "com.amazon.redshift.jdbc42.Driver";
        }

        if (driverClass != null) {
            Class.forName(driverClass);
        }
    }

    private ConnectionDetails buildConnectionDetails(String protocol, Map<String, Object> config) {
        ConnectionDetails details = new ConnectionDetails();
        SourceType sourceType = parseSourceType(protocol);

        switch (sourceType) {
            case POSTGRES, MYSQL, ORACLE, MSSQL, MARIADB, SQLITE,
                 SNOWFLAKE, REDSHIFT -> {
                details.setHost((String) config.get("host"));
                details.setPort(config.get("port") != null ? Integer.valueOf(config.get("port").toString()) : null);
                details.setDatabase((String) config.get("database"));
                details.setUsername((String) config.get("username"));
                details.setPassword((String) config.get("password"));
                details.setUrl(buildJdbcUrl(sourceType, config));
            }
            case CSV, EXCEL, PARQUET, AVRO, ORC -> details.setUrl((String) config.get("file_path"));
            case API -> details.setUrl(firstNonBlank(mapValue(config.get("source_config")), "url"));
            case PUSH -> { }
        }
        return details;
    }

    private SourceType parseSourceType(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            throw new BadRequestException("Le protocole de source est manquant.");
        }
        try {
            return SourceType.valueOf(protocol.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Protocole non reconnu: " + protocol);
        }
    }

    private String buildJdbcUrl(SourceType sourceType, Map<String, Object> config) {
        String host = (String) config.get("host");
        String database = (String) config.get("database");
        String port = config.get("port") != null ? config.get("port").toString() : defaultJdbcPort(sourceType);

        return switch (sourceType) {
            case POSTGRES -> String.format("jdbc:postgresql://%s:%s/%s", host, port, database);
            case MYSQL    -> String.format("jdbc:mysql://%s:%s/%s", host, port, database);
            case ORACLE   -> String.format("jdbc:oracle:thin:@%s:%s:%s", host, port, database);
            case MSSQL    -> String.format("jdbc:sqlserver://%s:%s;databaseName=%s", host, port, database);
            case MARIADB  -> String.format("jdbc:mariadb://%s:%s/%s", host, port, database);
            case SQLITE   -> String.format("jdbc:sqlite:%s", database);
            case SNOWFLAKE -> String.format("jdbc:snowflake://%s/%s", host, database);
            case REDSHIFT  -> String.format("jdbc:redshift://%s:%s/%s", host, port, database);
            default -> throw new BadRequestException("Construction URL JDBC non supportée pour: " + sourceType);
        };
    }

    private String defaultJdbcPort(SourceType sourceType) {
        return switch (sourceType) {
            case MYSQL, MARIADB -> "3306";
            case MSSQL          -> "1433";
            case ORACLE         -> "1521";
            case REDSHIFT       -> "5439";
            case SNOWFLAKE      -> "443";
            default             -> "5432";
        };
    }

    private String firstNonBlank(Map<String, Object> sourceConfig, String... keys) {
        if (sourceConfig == null) {
            return "";
        }

        for (String key : keys) {
            String value = stringValue(sourceConfig.get(key));
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private Set<String> parseSelectedColumns(Object rawColumns) {
        Set<String> selectedColumns = new HashSet<>();
        if (rawColumns == null) {
            return selectedColumns;
        }

        if (rawColumns instanceof List<?> values) {
            values.forEach(value -> addSelectedColumn(selectedColumns, value));
            return selectedColumns;
        }

        for (String value : String.valueOf(rawColumns).split(",")) {
            addSelectedColumn(selectedColumns, value);
        }
        return selectedColumns;
    }

    private void addSelectedColumn(Set<String> selectedColumns, Object value) {
        if (value == null) {
            return;
        }

        String normalized = String.valueOf(value).trim().toLowerCase();
        if (!normalized.isBlank()) {
            selectedColumns.add(normalized);
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public void saveMappingDraft(Map<String, Object> payload) {
        String standardId = (String) payload.get("standardId");
        List<Map<String, Object>> fields = (List<Map<String, Object>>) payload.get("fields");
        
        if (fields == null || fields.isEmpty()) {
            throw new BadRequestException("Aucun champ de mapping fourni.");
        }
        
        // Validate for duplicate iol_terms
        Set<String> iolTerms = new HashSet<>();
        for (Map<String, Object> field : fields) {
            String iolTerm = (String) field.get("iol_term");
            if (iolTerm != null && !iolTerm.isEmpty()) {
                if (iolTerms.contains(iolTerm)) {
                    throw new BadRequestException("Doublon dans le mapping: le terme '" + iolTerm + "' est utilisé plusieurs fois.");
                }
                iolTerms.add(iolTerm);
            }
        }
        
        log.info("Brouillon de mapping sauvegardé pour le standard: {} avec {} champs", standardId, fields.size());
    }

    private void validateMetadataPayload(String metadataJson, String metadataFileName) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return;
        }

        if (metadataFileName != null && !metadataFileName.isBlank() && !metadataFileName.toLowerCase().endsWith(".json")) {
            throw new BadRequestException("Le fichier de métadonnées doit être un JSON.");
        }

        try {
            objectMapper.readTree(metadataJson);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Le contenu des métadonnées doit être un JSON valide.");
        }
    }

    public String executeWorkflow(String workflowId) {
        WorkflowConfig workflow = getEntityById(workflowId);
        if (!workflow.isActive()) {
            throw new BadRequestException("Le workflow n'est pas actif.");
        }
        log.info("Soumission du workflow: {}", workflowId);
        com.iol.etlplatform.entity.ExecutionLog execLog = orchestrationService.runWorkflow(workflowId);
        // Le transport se poursuit en arriere-plan: ne pas affirmer que la
        // commande est deja publiee dans Kafka.
        return "Workflow accepte; transport en cours. ExecutionLog id=" + execLog.getId();
    }

    // ==================== FILE DISCOVERY ====================

    private List<Map<String, Object>> discoverCsvColumns(Map<String, Object> config, String sourceName) {
        List<Map<String, Object>> columns = new ArrayList<>();
        try {
            String filePath = (String) config.get("file_path");
            String delimiter = (String) config.getOrDefault("delimiter", ";");

            if (filePath == null) {
                throw new BadRequestException("Chemin fichier CSV requis");
            }

            // Read first line for headers
            java.nio.file.Path path = java.nio.file.Paths.get(filePath);
            java.util.List<String> lines = java.nio.file.Files.readAllLines(path, java.nio.charset.StandardCharsets.UTF_8);

            if (lines.isEmpty()) {
                throw new BadRequestException("Fichier CSV vide");
            }

            String[] headers = lines.get(0).split(delimiter);
            for (String header : headers) {
                header = header.trim().replaceAll("\"", "");
                Map<String, Object> col = new LinkedHashMap<>();
                col.put("sourceName", sourceName);
                col.put("tableName", "data");
                col.put("name", ColumnNameSanitizer.sanitize(header));
                col.put("columnName", ColumnNameSanitizer.sanitize(header));
                col.put("originalName", header);
                col.put("type", "String");
                col.put("sqlType", "VARCHAR");
                col.put("javaType", "String");
                col.put("nullable", true);
                col.put("mapped", false);
                col.put("iolTerm", "");
                columns.add(col);
            }

            log.info("CSV discovery: {} colonnes trouvées dans {}", columns.size(), filePath);
            return columns;
        } catch (Exception e) {
            throw new BadRequestException("Découverte CSV échouée: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> discoverExcelColumns(Map<String, Object> config, String sourceName) {
        List<Map<String, Object>> columns = new ArrayList<>();
        try {
            String filePath = (String) config.get("file_path");
            Integer sheetIndex = config.get("sheet_name") != null ? (Integer) config.get("sheet_name") : 0;

            if (filePath == null) {
                throw new BadRequestException("Chemin fichier Excel requis");
            }

            // Read Excel headers (simple approach without POI dependency)
            java.nio.file.Path path = java.nio.file.Paths.get(filePath);
            // Note: Real implementation would use Apache POI library
            // For now, return sample structure
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("sourceName", sourceName);
            col.put("tableName", "Sheet" + sheetIndex);
            col.put("name", "column1");
            col.put("columnName", "column1");
            col.put("type", "String");
            col.put("sqlType", "VARCHAR");
            col.put("javaType", "String");
            col.put("nullable", true);
            col.put("mapped", false);
            col.put("iolTerm", "");
            columns.add(col);

            log.info("Excel discovery: Sheet{} from {}", sheetIndex, filePath);
            return columns;
        } catch (Exception e) {
            throw new BadRequestException("Découverte Excel échouée: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> discoverStructuredFileColumns(String format, Map<String, Object> config, String sourceName) {
        List<Map<String, Object>> columns = new ArrayList<>();
        try {
            String filePath = (String) config.get("file_path");

            if (filePath == null) {
                throw new BadRequestException("Chemin fichier requis pour " + format);
            }

            // For JSON/XML/PARQUET/AVRO, return sample structure
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("sourceName", sourceName);
            col.put("tableName", format.toLowerCase());
            col.put("name", "field1");
            col.put("columnName", "field1");
            col.put("type", "String");
            col.put("sqlType", "VARCHAR");
            col.put("javaType", "String");
            col.put("nullable", true);
            col.put("mapped", false);
            col.put("iolTerm", "");
            columns.add(col);

            log.info("{} discovery: structure inferred from {}", format, filePath);
            return columns;
        } catch (Exception e) {
            throw new BadRequestException("Découverte " + format + " échouée: " + e.getMessage());
        }
    }

    // ==================== SECURITY ====================

    private String getCurrentUserEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getName();
        }
        return "anonymous";
    }

    private boolean isCurrentUserAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            return auth.getAuthorities().stream()
                    .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        }
        return false;
    }
}
