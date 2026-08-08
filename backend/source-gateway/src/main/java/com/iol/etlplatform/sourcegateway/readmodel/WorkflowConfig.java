package com.iol.etlplatform.sourcegateway.readmodel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * Projection EN LECTURE SEULE de la configuration d'un workflow.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  CE N'EST PAS L'ENTITE DE REFERENCE
 * ═══════════════════════════════════════════════════════════════════════════
 *  L'entite faisant foi est {@code com.iol.etlplatform.entity.WorkflowConfig}
 *  dans api-core, seul service autorise a ecrire cette collection. Le gateway
 *  ne fait que la lire; son compte MongoDB n'a d'ailleurs aucun droit
 *  d'ecriture dessus.
 *
 *  Duplication assumee: le gateway ne depend pas d'api-core a la compilation,
 *  ce qui evite un module de domaine partage entre deux services deployes
 *  separement.
 *
 *  RISQUE DE DERIVE. Spring Data MongoDB ignore les champs inconnus: un ajout
 *  cote api-core ne casse rien, il est simplement invisible ici. En revanche,
 *  un champ RENOMME ou dont la semantique change devient silencieusement nul
 *  cote gateway. Toute modification de l'entite de reference doit donc etre
 *  repercutee ici, en particulier les noms serialises {@code @JsonProperty},
 *  qui font partie du contrat de fil avec Hop et Spark.
 */
@Data
@Document(collection = "workflow_configs")
public class WorkflowConfig {
    @Id
    private String id;
    
    // Métadonnées de base du pipeline
    private String workflowName;
    private String description;
    private String protocol;

    /**
     * @deprecated Domaine du standard en texte libre. Conservé pour la
     * rétrocompatibilité et l'usage existant (AiService). La source de vérité du
     * rattachement à un référentiel est désormais {@link #standardId}.
     */
    @Deprecated
    private String standardDomain;

    /**
     * Référence typée (nullable) vers {@link com.iol.etlplatform.entity.Standard#id}.
     * Source de vérité du rattachement à un standard. Null = workflow INTERNAL non
     * rattaché à un référentiel (comportement classique).
     */
    private String standardId;

    /**
     * Sens du workflow. Défaut INTERNAL (rétrocompatible : tout workflow existant
     * reste INTERNAL). INBOUND reçoit des données externes. OUTBOUND livre le
     * résultat Gold vers un système externe après dé-normalisation.
     */
    private WorkflowDirection direction = WorkflowDirection.INTERNAL;

    /**
     * Configuration de livraison OUTBOUND. Nullable pour tous les workflows
     * existants et requis seulement quand {@link #direction} vaut OUTBOUND.
     *
     * Structure attendue:
     * {
     *   targetStandardId, targetSystem, targetAdapter,
     *   source: { goldTable | query },
     *   destination: { openhimChannel | endpointUrl, auth }
     * }
     */
    private Map<String, Object> outboundConfig;

    // Stockage JSON flexible pour les métadonnées complètes
    private String metadataFileName;
    private String metadataJson;
    private String metadataVersion;
    
    // Configuration de planification
    private Map<String, Object> schedule = new HashMap<>();

    // Multi-source architecture (NEW) — source of truth when present
    private List<SourceDefinition> sources;

    // Gold UNIQUE du workflow (fusion SQL des Silver de toutes les sources).
    // Un seul Gold par workflow — il n'existe plus de gold_config par source.
    @JsonProperty("gold_config_global")
    private GoldConfigGlobal goldConfigGlobal;

    // Destination (data warehouse cible) au niveau workflow — référence vers
    // DestinationConnection.id. Source de vérité quand présent : la connexion
    // résolue est réinjectée dans target_connection de chaque source au moment
    // de construire le JSON Kafka. Si absent, le target_connection par source
    // (legacy) reste utilisé tel quel (rétrocompatibilité).
    private String destinationConnectionId;

    // Configuration de la source (LEGACY — kept for backward compatibility)
    private Map<String, Object> sourceConfig = new HashMap<>();

    // Champs et mappings (LEGACY — kept for backward compatibility)
    private List<Map<String, Object>> fields = new ArrayList<>();

    // Scripts d'agrégation (LEGACY — kept for backward compatibility)
    private String aggregationScripts;
    
    // Mode d'exécution : LOCAL (défaut) ou SPARK (big data)
    private String executionMode;

    /**
     * Priorité d'exécution dans la file Kafka.
     *   1 = CRITICAL  (temps réel, passe devant tout)
     *   2 = HIGH      (reporting urgent)
     *   3 = NORMAL    (défaut)
     *   4 = LOW       (batch de nuit)
     *   5 = BACKGROUND
     */
    private int priority = 3;

    // Nombre de lignes estimées (optionnel, utilisé pour le seuil Spark auto)
    private Long estimatedRows;

    // Statut du workflow
    private boolean isActive;

    // Propriétaire du workflow (séparation USER/ADMIN)
    private String createdBy;

    // Horodatage
    private String createdAt;
    private String updatedAt;
}
