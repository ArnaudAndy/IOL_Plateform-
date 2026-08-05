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

/**
 * Entité Standard - Référentiel de normes métier
 * 
 * Représente un standard (ex: HEALTH, FINANCE) avec ses termes associés.
 * Les workflows utilisent les standards pour valider les mappages sémantiques.
 */
@Document(collection = "standards")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Standard {

    @Id
    private String id;

    /**
     * Nom du standard
     * Exemples: "HL7v2", "FHIR", "ISO20022", "GDPR"
     */
    @Field("name")
    private String name;

    /**
     * Description du standard et son objectif
     */
    @Field("description")
    private String description;

    /**
     * Domaine du standard
     * Énumération: HEALTH, FINANCE, EDUCATION, RETAIL, LOGISTICS, COMPLIANCE
     */
    @Field("domain")
    private StandardDomain domain;

    /**
     * Version du standard
     * Exemple: "2.5", "R4", "2022-01"
     */
    @Field("version")
    private String version;

    /**
     * Termes/Champs du standard
     * Relation avec les StandardTerm
     */
    @Field("term_count")
    private Integer termCount;

    /**
     * Statut du standard
     * ACTIVE, DEPRECATED, DRAFT
     */
    @Field("status")
    @Builder.Default
    private StandardStatus status = StandardStatus.ACTIVE;

    /**
     * URL ou documentation de référence
     */
    @Field("reference_url")
    private String referenceUrl;

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

    /**
     * Créé par (userId)
     */
    @Field("created_by")
    private String createdBy;

    public enum StandardDomain {
        HEALTH,      // Domaine santé (HL7, FHIR)
        FINANCE,     // Domaine finance (ISO20022, SWIFT)
        EDUCATION,   // Domaine éducation (Ed-Fi)
        RETAIL,      // Domaine retail (EDI, GS1)
        LOGISTICS,   // Domaine logistique (EDIFACT)
        COMPLIANCE,  // Domaine conformité (GDPR, CCPA)
        CUSTOM       // Domaine personnalisé
    }

    public enum StandardStatus {
        DRAFT,       // En cours de création
        ACTIVE,      // En production
        DEPRECATED   // Obsolète
    }
}
