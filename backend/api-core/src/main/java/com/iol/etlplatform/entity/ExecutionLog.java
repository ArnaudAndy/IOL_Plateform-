package com.iol.etlplatform.entity;

import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.entity.enums.WorkflowDirection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Entité ExecutionLog - Suivi détaillé des exécutions
 * 
 * Étend le logging avec:
 * - Durées par phase (Ingestion, Transformation, Agrégation)
 * - Détails des sources traitées
 * - Métriques de performance
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "execution_logs")
public class ExecutionLog {
    
    @Id
    private String id;
    
    /**
     * ID du workflow exécuté
     */
    private String workflowId;

    private String workflowName;

    private WorkflowDirection direction;

    private String correlationId;
    
    /**
     * Status de l'exécution
     */
    private ExecutionStatus status;
    
    /**
     * Date/heure de démarrage
     */
    private Instant startTime;
    
    /**
     * Date/heure de fin
     */
    private Instant endTime;

    /** Etape réellement active, mise à jour par les battements du worker. */
    @Field("current_stage")
    private String currentStage;

    /** Dernier signe de vie reçu du worker d'exécution. */
    @Field("last_heartbeat_at")
    private Instant lastHeartbeatAt;
    
    @Field("logOutput")
    private String logOutput;

    // ==================== PHASES ====================

    /**
     * Phase 1: Ingestion/Extraction
     * Durée en ms pour chaque source
     */
    @Field("ingestion_duration_ms")
    private Long ingestionDurationMs;

    /**
     * Nombre de lignes extraites
     */
    @Field("rows_extracted")
    private Long rowsExtracted;

    /**
     * Phase 2: Nettoyage par source
     * Durée en ms pour le nettoyage
     */
    @Field("cleaning_duration_ms")
    private Long cleaningDurationMs;

    /**
     * Nombre de lignes nettoyées
     */
    @Field("rows_cleaned")
    private Long rowsCleaned;

    /**
     * Phase 3: Transformation/Agrégation (Script Gold)
     * Durée en ms pour la transformation
     */
    @Field("transformation_duration_ms")
    private Long transformationDurationMs;

    /**
     * Nombre de lignes finales produites
     */
    @Field("rows_transformed")
    private Long rowsTransformed;

    // ==================== SOURCES ====================

    /**
     * Détails par source
     */
    @Field("source_metrics")
    private List<SourceMetric> sourceMetrics;

    /**
     * Etat final de chaque couche, notamment SKIPPED pour une étape désactivée.
     * Les clés Silver par source utilisent SILVER:&lt;index&gt;.
     */
    @Field("stage_statuses")
    private Map<String, String> stageStatuses;

    /**
     * Métadonnées générées pour Apache Hop
     */
    @Field("hop_metadata_generated")
    private Boolean hopMetadataGenerated;

    /**
     * Chemin du fichier de métadonnées JSON
     */
    @Field("metadata_file_path")
    private String metadataFilePath;

    // ==================== ERREURS ====================

    /**
     * Message d'erreur si l'exécution a échoué
     */
    @Field("error_message")
    private String errorMessage;

    @Field("failed_stage")
    private String failedStage;

    /**
     * Stack trace complet en cas d'erreur
     */
    @Field("error_stacktrace")
    private String errorStacktrace;

    /**
     * Nombre d'erreurs non-bloquantes
     */
    @Field("warning_count")
    private Integer warningCount;

    // ==================== AUDIT ====================

    /**
     * Lancé par (userId)
     */
    @Field("triggered_by")
    private String triggeredBy;

    /**
     * Paramètres d'exécution
     */
    @Field("execution_params")
    private Map<String, String> executionParams;

    /**
     * Logs détaillés (peut être volumineux)
     */
    @Field("detailed_logs")
    private String detailedLogs;

    /**
     * Créé le
     */
    @Field("created_at")
    private LocalDateTime createdAt;

    // ==================== INCREMENTAL LOADING ====================

    /**
     * @deprecated Watermark unique (hypothèse mono-source). Conservé pour la
     * rétrocompatibilité des anciens ExecutionLog. Utiliser désormais
     * {@link #lastSuccessfulWatermarks} (par source, clé = target_table).
     *
     * Valeur maximale de la colonne incrémentale au dernier run réussi.
     * Format: depends on column type (timestamp ISO-8601, numeric, etc.)
     */
    @Deprecated
    @Field("last_successful_watermark")
    private String lastSuccessfulWatermark;

    /**
     * Watermarks du dernier run réussi, PAR SOURCE.
     * Clé = target_table de la source, Valeur = borne haute atteinte
     * (MAX(incremental_column)) au dernier chargement Bronze réussi.
     * Réinjectée par source dans le payload Kafka du run suivant.
     */
    @Field("last_successful_watermarks")
    private Map<String, String> lastSuccessfulWatermarks;

    /**
     * Classe imbriquée pour les métriques par source
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class SourceMetric {
        private String sourceId;
        private String sourceName;
        private String sourceType;  // JDBC, S3, API
        private Long rowsRead;
        private Long rowsValid;
        private Long rowsRejected;
        private Long durationMs;
        private String status;
        private String errorMessage;
    }
}
