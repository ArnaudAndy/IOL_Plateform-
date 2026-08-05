package com.iol.etlplatform.entity;

import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.annotation.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Entité SourceMetadata - Métadonnées JSON générées pour Apache Hop
 * 
 * Spécifique à chaque source (JDBC, S3, API), générée avant l'orchestration
 */
@Document(collection = "source_metadata")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceMetadata {

    @Id
    private String id;

    /**
     * ID du workflow auquel appartient cette source
     */
    @Field("workflow_id")
    private String workflowId;

    /**
     * ID de la source de données
     */
    @Field("source_id")
    private String sourceId;

    /**
     * Type de source
     * JDBC, S3, API, FILE, KAFKA, etc.
     */
    @Field("source_type")
    private SourceType sourceType;

    /**
     * Nom descriptif de la source
     */
    @Field("source_name")
    private String sourceName;

    // ==================== CONFIGURATION SPÉCIFIQUE ====================

    /**
     * Pour JDBC:
     * - driver
     * - url
     * - username
     * - password
     * - table
     * - custom_query
     */
    @Field("jdbc_config")
    private JdbcConfig jdbcConfig;

    /**
     * Pour S3:
     * - bucket
     * - prefix
     * - file_pattern
     * - format (CSV, PARQUET, JSON)
     * - region
     */
    @Field("s3_config")
    private S3Config s3Config;

    /**
     * Pour API:
     * - endpoint_url
     * - method (GET, POST)
     * - headers
     * - pagination_type
     * - auth_type (NONE, BASIC, BEARER, OAUTH2)
     */
    @Field("api_config")
    private ApiConfig apiConfig;

    // ==================== COLONNES DÉCOUVERTES ====================

    /**
     * Colonnes extraites par la phase Discovery
     */
    @Field("discovered_columns")
    private List<ColumnMetadata> discoveredColumns;

    /**
     * Nombre total de lignes (estimé)
     */
    @Field("total_rows_estimate")
    private Long totalRowsEstimate;

    /**
     * Schéma du fichier JSON généré pour Hop
     */
    @Field("hop_json_schema")
    private String hopJsonSchema;

    /**
     * Statut de la métadonnées
     */
    @Field("status")
    private MetadataStatus status;

    /**
     * Créé le
     */
    @Field("created_at")
    private LocalDateTime createdAt;

    /**
     * Mise à jour
     */
    @Field("updated_at")
    private LocalDateTime updatedAt;

    public enum SourceType {
        JDBC,    // Base de données relationnelle
        S3,      // Amazon S3 ou compatible
        API,     // API REST
        FILE,    // Fichier local
        KAFKA,   // Topic Kafka
        FTP,     // Serveur FTP
        CUSTOM   // Source personnalisée
    }

    public enum MetadataStatus {
        DRAFT,       // En cours de création
        READY,       // Prêt pour orchestration
        IN_PROGRESS, // Utilisé dans une exécution
        ARCHIVED     // Archivé
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class JdbcConfig {
        private String driver;
        private String url;
        private String username;
        private String password;
        private String table;
        private String customQuery;
        private Integer connectionTimeout;
        private Integer fetchSize;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class S3Config {
        private String bucket;
        private String prefix;
        private String filePattern;
        private String format;  // CSV, PARQUET, JSON
        private String region;
        private String accessKey;
        private String secretKey;
        private Boolean useAssumeRole;
        private String roleArn;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ApiConfig {
        private String endpointUrl;
        private String method;  // GET, POST
        private Map<String, String> headers;
        private String paginationType;  // OFFSET, CURSOR, NONE
        private String authType;        // NONE, BASIC, BEARER, OAUTH2
        private String authToken;
        private String requestBody;
        private Integer pageSize;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ColumnMetadata {
        private String columnName;
        private String columnType;      // STRING, INTEGER, DECIMAL, DATE, etc.
        private Boolean nullable;
        private Integer maxLength;
        private String description;
        private String standardTermId;  // Référence au StandardTerm s'il existe
    }
}
