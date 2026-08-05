package com.iol.etlplatform.entity;

import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.annotation.Id;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Entité StandardTerm - Termes/Champs d'un Standard
 * 
 * Représente un champ ou terme spécifique d'un standard.
 * Exemples:
 * - Standard: HL7v2 -> StandardTerm: PATIENT_ID (MRN)
 * - Standard: FHIR -> StandardTerm: identifier.value
 * - Standard: ISO20022 -> StandardTerm: AccountIdentifier
 */
@Document(collection = "standard_terms")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StandardTerm {

    @Id
    private String id;

    /**
     * ID du Standard parent
     * Référence vers Standard.id
     */
    @Field("standard_id")
    private String standardId;

    /**
     * Nom du terme/champ
     * Exemples: PATIENT_ID, SSN, MRN, EMAIL, ACCOUNT_NUMBER
     */
    @Field("term_name")
    private String termName;

    /**
     * Description du terme
     */
    @Field("description")
    private String description;

    /**
     * Type de données
     * STRING, INTEGER, DECIMAL, DATE, DATETIME, BOOLEAN, JSON, BINARY
     */
    @Field("data_type")
    private DataType dataType;

    /**
     * Format/Pattern du champ
     * Exemples:
     * - "^[0-9]{3}-[0-9]{2}-[0-9]{4}$" pour SSN (USA)
     * - "^[A-Z0-9]{8,}$" pour code identifiant
     * - "YYYY-MM-DD" pour date
     */
    @Field("format_rule")
    private String formatRule;

    /**
     * Longueur minimale
     */
    @Field("min_length")
    private Integer minLength;

    /**
     * Longueur maximale
     */
    @Field("max_length")
    private Integer maxLength;

    /**
     * Est obligatoire ?
     */
    @Field("required")
    @Builder.Default
    private Boolean required = true;

    /**
     * Valeurs énumérées possibles
     */
    @Field("enum_values")
    private java.util.List<String> enumValues;

    /**
     * Précision (pour DECIMAL)
     */
    @Field("precision")
    private Integer precision;

    /**
     * Échelle (pour DECIMAL)
     */
    @Field("scale")
    private Integer scale;

    /**
     * Mappages alternatifs
     * Clé: nom du système source
     * Valeur: nom du champ dans ce système
     */
    @Field("system_mappings")
    private Map<String, String> systemMappings;

    /**
     * Exemple de valeur
     */
    @Field("example_value")
    private String exampleValue;

    /**
     * Notes additionnelles
     */
    @Field("notes")
    private String notes;

    /**
     * Créé le
     */
    @Field("created_at")
    private LocalDateTime createdAt;

    /**
     * Modifié le
     */
    @Field("updated_at")
    private LocalDateTime updatedAt;

    public enum DataType {
        STRING,      // Texte
        INTEGER,     // Nombre entier
        DECIMAL,     // Nombre décimal
        DATE,        // Date (YYYY-MM-DD)
        DATETIME,    // Date + heure
        TIME,        // Heure
        BOOLEAN,     // Booléen (true/false)
        JSON,        // Objet JSON
        BINARY,      // Données binaires
        UUID,        // UUID unique
        TIMESTAMP    // Timestamp Unix
    }
}
