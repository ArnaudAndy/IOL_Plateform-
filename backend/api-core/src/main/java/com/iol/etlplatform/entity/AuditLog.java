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
 * Entité AuditLog - Trace complète des actions
 * 
 * Enregistre:
 * - Qui a fait quoi (userId)
 * - Quand (timestamp)
 * - Sur quelle ressource (resourceId, resourceType)
 * - Quelle action (CREATE, UPDATE, DELETE, EXECUTE)
 * - Détails (oldValues, newValues)
 */
@Document(collection = "audit_logs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    private String id;

    /**
     * Utilisateur qui a effectué l'action
     */
    @Field("user_id")
    private String userId;

    /**
     * Rôle de l'utilisateur au moment de l'action
     */
    @Field("user_role")
    private String userRole;

    /**
     * Type d'action effectuée
     */
    @Field("action")
    private AuditAction action;

    /**
     * Type de ressource (Standard, Workflow, ExecutionLog, etc.)
     */
    @Field("resource_type")
    private String resourceType;

    /**
     * ID de la ressource affectée
     */
    @Field("resource_id")
    private String resourceId;

    /**
     * Description de ce qui a été changé
     */
    @Field("description")
    private String description;

    /**
     * Ancienne valeur (pour UPDATE)
     */
    @Field("old_values")
    private Map<String, Object> oldValues;

    /**
     * Nouvelle valeur (pour UPDATE)
     */
    @Field("new_values")
    private Map<String, Object> newValues;

    /**
     * Statut de l'action
     */
    @Field("status")
    private AuditStatus status;

    /**
     * Message d'erreur si l'action a échoué
     */
    @Field("error_message")
    private String errorMessage;

    /**
     * Adresse IP de l'utilisateur
     */
    @Field("ip_address")
    private String ipAddress;

    /**
     * User Agent (navigateur, etc.)
     */
    @Field("user_agent")
    private String userAgent;

    /**
     * Timestamp de l'action
     */
    @Field("timestamp")
    private LocalDateTime timestamp;

    /**
     * Durée de l'action en ms
     */
    @Field("duration_ms")
    private Long durationMs;

    public enum AuditAction {
        CREATE,    // Création de ressource
        READ,      // Lecture de ressource
        UPDATE,    // Modification
        DELETE,    // Suppression
        EXECUTE,   // Exécution (workflow, génération SQL)
        ACTIVATE,  // Activation
        DEPRECATE, // Dépublication
        EXPORT,    // Export
        IMPORT     // Import
    }

    public enum AuditStatus {
        SUCCESS,   // Action réussie
        FAILURE,   // Action échouée
        PARTIAL    // Succès partiel (avec avertissements)
    }
}
