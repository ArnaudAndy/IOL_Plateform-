package com.iol.etlplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.entity.SourceMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Service pour générer des métadonnées JSON pour Apache Hop
 * 
 * À partir des configurations de source (JDBC, S3, API),
 * génère un JSON standardisé pour que Hop puisse orchestrer l'extraction
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MetadataGeneratorService {

    private final ObjectMapper objectMapper;

    /**
     * Générer le JSON pour une source JDBC
     */
    public String generateJdbcMetadata(SourceMetadata metadata) {
        log.info("Génération métadonnées JDBC pour: {}", metadata.getSourceName());

        Map<String, Object> hopConfig = new LinkedHashMap<>();

        // Configuration du connecteur JDBC
        Map<String, Object> jdbcNode = new LinkedHashMap<>();
        jdbcNode.put("name", "ETL-" + metadata.getSourceName());
        jdbcNode.put("type", "TABLE_INPUT");
        
        Map<String, Object> connection = new LinkedHashMap<>();
        connection.put("driver", metadata.getJdbcConfig().getDriver());
        connection.put("url", metadata.getJdbcConfig().getUrl());
        connection.put("username", metadata.getJdbcConfig().getUsername());
        connection.put("password", metadata.getJdbcConfig().getPassword());
        connection.put("timeout", metadata.getJdbcConfig().getConnectionTimeout());
        jdbcNode.put("connection", connection);

        // Requête SQL
        Map<String, Object> query = new LinkedHashMap<>();
        if (metadata.getJdbcConfig().getCustomQuery() != null) {
            query.put("sql", metadata.getJdbcConfig().getCustomQuery());
        } else {
            query.put("table", metadata.getJdbcConfig().getTable());
            query.put("sql", "SELECT * FROM " + metadata.getJdbcConfig().getTable());
        }
        jdbcNode.put("query", query);

        // Colonnes découvertes
        Map<String, Object> fields = new LinkedHashMap<>();
        if (metadata.getDiscoveredColumns() != null) {
            metadata.getDiscoveredColumns().forEach(col -> {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("type", col.getColumnType());
                field.put("nullable", col.getNullable());
                if (col.getMaxLength() != null) {
                    field.put("max_length", col.getMaxLength());
                }
                fields.put(col.getColumnName(), field);
            });
        }
        jdbcNode.put("fields", fields);

        hopConfig.put("source", jdbcNode);
        hopConfig.put("generated_at", LocalDateTime.now().toString());
        hopConfig.put("source_type", "JDBC");

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(hopConfig);
        } catch (Exception e) {
            log.error("Erreur lors de la génération JSON JDBC", e);
            throw new RuntimeException("Erreur génération métadonnées JDBC", e);
        }
    }

    /**
     * Générer le JSON pour une source S3
     */
    public String generateS3Metadata(SourceMetadata metadata) {
        log.info("Génération métadonnées S3 pour: {}", metadata.getSourceName());

        Map<String, Object> hopConfig = new LinkedHashMap<>();

        Map<String, Object> s3Node = new LinkedHashMap<>();
        s3Node.put("name", "ETL-" + metadata.getSourceName());
        s3Node.put("type", "AWS_S3_INPUT");

        Map<String, Object> s3Conn = new LinkedHashMap<>();
        s3Conn.put("bucket", metadata.getS3Config().getBucket());
        s3Conn.put("prefix", metadata.getS3Config().getPrefix());
        s3Conn.put("file_pattern", metadata.getS3Config().getFilePattern());
        s3Conn.put("format", metadata.getS3Config().getFormat());
        s3Conn.put("region", metadata.getS3Config().getRegion());
        if (metadata.getS3Config().getUseAssumeRole() != null && metadata.getS3Config().getUseAssumeRole()) {
            s3Conn.put("role_arn", metadata.getS3Config().getRoleArn());
        }
        s3Node.put("connection", s3Conn);

        // Colonnes
        Map<String, Object> fields = new LinkedHashMap<>();
        if (metadata.getDiscoveredColumns() != null) {
            metadata.getDiscoveredColumns().forEach(col -> {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("type", col.getColumnType());
                field.put("nullable", col.getNullable());
                fields.put(col.getColumnName(), field);
            });
        }
        s3Node.put("fields", fields);

        hopConfig.put("source", s3Node);
        hopConfig.put("generated_at", LocalDateTime.now().toString());
        hopConfig.put("source_type", "S3");
        hopConfig.put("total_rows_estimate", metadata.getTotalRowsEstimate());

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(hopConfig);
        } catch (Exception e) {
            log.error("Erreur lors de la génération JSON S3", e);
            throw new RuntimeException("Erreur génération métadonnées S3", e);
        }
    }

    /**
     * Générer le JSON pour une source API
     */
    public String generateApiMetadata(SourceMetadata metadata) {
        log.info("Génération métadonnées API pour: {}", metadata.getSourceName());

        Map<String, Object> hopConfig = new LinkedHashMap<>();

        Map<String, Object> apiNode = new LinkedHashMap<>();
        apiNode.put("name", "ETL-" + metadata.getSourceName());
        apiNode.put("type", "REST_INPUT");

        Map<String, Object> apiConn = new LinkedHashMap<>();
        apiConn.put("endpoint", metadata.getApiConfig().getEndpointUrl());
        apiConn.put("method", metadata.getApiConfig().getMethod());
        apiConn.put("auth_type", metadata.getApiConfig().getAuthType());
        apiConn.put("pagination_type", metadata.getApiConfig().getPaginationType());
        apiConn.put("page_size", metadata.getApiConfig().getPageSize());

        if (metadata.getApiConfig().getHeaders() != null) {
            apiConn.put("headers", metadata.getApiConfig().getHeaders());
        }
        apiNode.put("connection", apiConn);

        // Colonnes
        Map<String, Object> fields = new LinkedHashMap<>();
        if (metadata.getDiscoveredColumns() != null) {
            metadata.getDiscoveredColumns().forEach(col -> {
                Map<String, Object> field = new LinkedHashMap<>();
                field.put("type", col.getColumnType());
                field.put("nullable", col.getNullable());
                fields.put(col.getColumnName(), field);
            });
        }
        apiNode.put("fields", fields);

        hopConfig.put("source", apiNode);
        hopConfig.put("generated_at", LocalDateTime.now().toString());
        hopConfig.put("source_type", "API");

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(hopConfig);
        } catch (Exception e) {
            log.error("Erreur lors de la génération JSON API", e);
            throw new RuntimeException("Erreur génération métadonnées API", e);
        }
    }

    /**
     * Générer le JSON approprié selon le type de source
     */
    public String generateMetadata(SourceMetadata metadata) {
        log.info("Génération métadonnées pour la source: {} ({})", metadata.getSourceName(), metadata.getSourceType());

        String json = switch (metadata.getSourceType()) {
            case JDBC -> generateJdbcMetadata(metadata);
            case S3 -> generateS3Metadata(metadata);
            case API -> generateApiMetadata(metadata);
            default -> throw new UnsupportedOperationException("Type de source non supporté: " + metadata.getSourceType());
        };

        metadata.setHopJsonSchema(json);
        metadata.setStatus(SourceMetadata.MetadataStatus.READY);

        return json;
    }

    /**
     * Générer les métadonnées pour toutes les sources d'un workflow
     */
    public Map<String, String> generateMetadataForWorkflow(List<SourceMetadata> sources) {
        Map<String, String> metadataBySource = new LinkedHashMap<>();

        for (SourceMetadata source : sources) {
            String json = generateMetadata(source);
            metadataBySource.put(source.getSourceId(), json);
        }

        return metadataBySource;
    }

    /**
     * Créer une configuration Hop complète multi-sources
     */
    public String generateHopOrchestrationConfig(List<SourceMetadata> sources, String workflowId) {
        log.info("Génération configuration Hop pour workflow: {}", workflowId);

        Map<String, Object> hopPipeline = new LinkedHashMap<>();
        hopPipeline.put("workflow_id", workflowId);
        hopPipeline.put("generated_at", LocalDateTime.now().toString());

        List<Map<String, Object>> transforms = new ArrayList<>();

        // Phase 1: Ingestion par source
        for (SourceMetadata source : sources) {
            Map<String, Object> transform = new LinkedHashMap<>();
            transform.put("name", "Ingest-" + source.getSourceName());
            transform.put("type", source.getSourceType().toString());
            transform.put("source_id", source.getSourceId());
            
            // Parser le JSON métadonnées
            try {
                Map<String, Object> config = objectMapper.readValue(source.getHopJsonSchema(), Map.class);
                transform.put("config", config.get("source"));
            } catch (Exception e) {
                log.warn("Erreur parsing métadonnées source {}: {}", source.getSourceId(), e.getMessage());
            }

            transforms.add(transform);
        }

        // Phase 2: Script Gold (Agrégation)
        Map<String, Object> goldTransform = new LinkedHashMap<>();
        goldTransform.put("name", "Aggregation-Gold");
        goldTransform.put("type", "SQL_SCRIPT");
        goldTransform.put("input_sources", sources.stream().map(SourceMetadata::getSourceId).toList());
        transforms.add(goldTransform);

        hopPipeline.put("transforms", transforms);
        hopPipeline.put("phases", List.of(
                "INGESTION",
                "CLEANING",
                "AGGREGATION"
        ));

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(hopPipeline);
        } catch (Exception e) {
            log.error("Erreur génération configuration Hop", e);
            throw new RuntimeException("Erreur génération config Hop", e);
        }
    }
}
