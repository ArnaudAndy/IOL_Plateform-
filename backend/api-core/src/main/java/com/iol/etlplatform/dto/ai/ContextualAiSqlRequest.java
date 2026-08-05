package com.iol.etlplatform.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * DTO pour la requête IA contextuelle
 * Combine le workflow, le standard, et les colonnes découvertes
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Requête de génération SQL contextuelle avec standard")
public class ContextualAiSqlRequest {

    @NotBlank(message = "Le workflowId est obligatoire")
    @Schema(description = "ID du workflow", example = "wf_123")
    private String workflowId;

    @NotBlank(message = "Le standardId est obligatoire")
    @Schema(description = "ID du standard métier", example = "std_hl7")
    private String standardId;

    @Schema(description = "Colonnes découvertes de la source")
    private List<DiscoveredColumn> discoveredColumns;

    @Schema(description = "Type de génération SQL", example = "MAPPING")
    private SqlGenerationType generationType;

    @Schema(description = "Prompt utilisateur optionnel")
    private String userPrompt;

    @Schema(description = "Paramètres additionnels")
    private Map<String, String> additionalParams;

    /**
     * Colonne découverte par la phase Discovery
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(description = "Colonne découverte")
    public static class DiscoveredColumn {

        @NotBlank
        @Schema(description = "Nom de la colonne", example = "patient_id")
        private String columnName;

        @NotBlank
        @Schema(description = "Type SQL", example = "VARCHAR")
        private String sqlType;

        @Schema(description = "Type Java", example = "String")
        private String javaType;

        @Schema(description = "Longueur", example = "50")
        private Integer columnSize;

        @Schema(description = "Nullable", example = "false")
        private Boolean nullable;

        @Schema(description = "Description")
        private String description;

        @Schema(description = "Exemple de valeur")
        private String exampleValue;
    }

    /**
     * Type de génération SQL à effectuer
     */
    public enum SqlGenerationType {
        MAPPING,        // Mapper colonnes → termes standard
        VALIDATION,     // Générer validations
        AGGREGATION,    // Agrégation multi-sources (Gold)
        CLEANING,       // Nettoyage données
        CUSTOM          // SQL libre avec contexte
    }

    // Explicit getters to avoid reliance on Lombok annotation processing during compilation
    public String getWorkflowId() {
        return this.workflowId;
    }

    public String getStandardId() {
        return this.standardId;
    }

    public List<DiscoveredColumn> getDiscoveredColumns() {
        return this.discoveredColumns;
    }

    public SqlGenerationType getGenerationType() {
        return this.generationType;
    }

    public String getUserPrompt() {
        return this.userPrompt;
    }
}
