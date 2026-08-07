package com.iol.etlplatform.sourcegateway.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Ordre de transport emis par api-core.
 *
 * Contrat canonique: {@code contracts/transport-order.schema.json}.
 *
 * L'ordre ne porte aucun secret, aucune configuration et aucune donnee. Le
 * gateway resout tout depuis {@code workflowId}: c'est ce qui rend ce message
 * inoffensif dans un topic, une file d'erreurs ou un journal.
 *
 * @param eventType      discriminant, toujours TRANSPORT_REQUESTED
 * @param schemaVersion  version du contrat; une version inconnue est refusee
 * @param organizationId organisation unique de la plateforme
 * @param workflowId     seule reference metier transportee
 * @param execLogId      journal deja cree par api-core; cle du claim idempotent
 * @param executionKey   cle de partition {organizationId}:{workflowId}
 * @param requestedAt    horodatage UTC de la soumission
 * @param requestedBy    identite declenchante, ou proprietaire si planifie
 * @param priority       priorite du workflow, pour choisir le topic de sortie
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TransportOrder(
        String eventType,
        Integer schemaVersion,
        String organizationId,
        String workflowId,
        String execLogId,
        String executionKey,
        String requestedAt,
        String requestedBy,
        Integer priority) {

    public static final String EVENT_TYPE = "TRANSPORT_REQUESTED";

    /** Version produite et acceptee par cette implementation. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    /**
     * Verifie qu'un ordre est exploitable avant tout travail.
     *
     * Un ordre malforme part en file d'erreurs plutot que de bloquer la
     * partition: c'est la protection contre le message empoisonne.
     *
     * @throws InvalidTransportOrderException si l'ordre est inexploitable
     */
    public void validate() {
        require(EVENT_TYPE.equals(eventType), "eventType doit valoir " + EVENT_TYPE);
        require(schemaVersion != null, "schemaVersion est obligatoire");
        // Une version future decrit peut-etre des champs dont dependent la
        // securite ou l'integrite: mieux vaut refuser que deviner.
        require(schemaVersion == SUPPORTED_SCHEMA_VERSION,
                "schemaVersion " + schemaVersion + " non supportee (attendu "
                        + SUPPORTED_SCHEMA_VERSION + ")");
        require(hasText(organizationId), "organizationId est obligatoire");
        require(hasText(workflowId), "workflowId est obligatoire");
        require(hasText(execLogId), "execLogId est obligatoire");
        require(hasText(executionKey), "executionKey est obligatoire");
        require(executionKey.startsWith(organizationId + ":"),
                "executionKey doit etre partitionnee par l'organisation");
        require(hasText(requestedAt), "requestedAt est obligatoire");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidTransportOrderException(message);
        }
    }
}
