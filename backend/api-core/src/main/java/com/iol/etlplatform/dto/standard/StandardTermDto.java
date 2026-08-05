package com.iol.etlplatform.dto.standard;

import com.iol.etlplatform.entity.StandardTerm;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DTO pour StandardTerm
 * Représente un terme/champ d'un standard
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Représente un terme d'un Standard")
public class StandardTermDto {

    @Schema(description = "ID du terme", example = "st_001")
    private String id;

    @NotBlank(message = "L'ID du standard est obligatoire")
    @Schema(description = "ID du standard parent", example = "std_001")
    private String standardId;

    @NotBlank(message = "Le nom du terme est obligatoire")
    @Schema(description = "Nom du terme", example = "PATIENT_ID")
    private String termName;

    @Schema(description = "Description du terme")
    private String description;

    @NotNull(message = "Le type de données est obligatoire")
    @Schema(description = "Type de données", example = "STRING")
    private StandardTerm.DataType dataType;

    @Schema(description = "Règle de format (regex ou pattern)", example = "^[0-9]{3}-[0-9]{2}-[0-9]{4}$")
    private String formatRule;

    @Schema(description = "Longueur minimale", example = "1")
    private Integer minLength;

    @Schema(description = "Longueur maximale", example = "50")
    private Integer maxLength;

    @Schema(description = "Est obligatoire ?", example = "true")
    private Boolean required;

    @Schema(description = "Valeurs énumérées possibles")
    private List<String> enumValues;

    @Schema(description = "Précision (pour DECIMAL)", example = "10")
    private Integer precision;

    @Schema(description = "Échelle (pour DECIMAL)", example = "2")
    private Integer scale;

    @Schema(description = "Mappages alternatifs par système source")
    private Map<String, String> systemMappings;

    @Schema(description = "Exemple de valeur", example = "123-45-6789")
    private String exampleValue;

    @Schema(description = "Notes additionnelles")
    private String notes;

    @Schema(description = "Date de création")
    private LocalDateTime createdAt;

    @Schema(description = "Date de modification")
    private LocalDateTime updatedAt;
}
