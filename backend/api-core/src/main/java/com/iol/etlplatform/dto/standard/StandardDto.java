package com.iol.etlplatform.dto.standard;

import com.iol.etlplatform.entity.Standard;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO pour Standard
 * Utilisé pour les requêtes/réponses REST
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Représente un Standard (normes métier)")
public class StandardDto {

    @Schema(description = "ID du standard", example = "std_001")
    private String id;

    @NotBlank(message = "Le nom est obligatoire")
    @Schema(description = "Nom du standard", example = "HL7v2")
    private String name;

    @Schema(description = "Description du standard")
    private String description;

    @NotNull(message = "Le domaine est obligatoire")
    @Schema(description = "Domaine du standard", example = "HEALTH")
    private Standard.StandardDomain domain;

    @Schema(description = "Version du standard", example = "2.5")
    private String version;

    @Schema(description = "Nombre de termes", example = "150")
    private Integer termCount;

    @Schema(description = "Statut du standard", example = "ACTIVE")
    private Standard.StandardStatus status;

    @Schema(description = "URL de référence")
    private String referenceUrl;

    @Schema(description = "Date de création")
    private LocalDateTime createdAt;

    @Schema(description = "Date de modification")
    private LocalDateTime updatedAt;

    @Schema(description = "Créé par")
    private String createdBy;

    @Schema(description = "Liste des termes du standard")
    private List<StandardTermDto> terms;
}
