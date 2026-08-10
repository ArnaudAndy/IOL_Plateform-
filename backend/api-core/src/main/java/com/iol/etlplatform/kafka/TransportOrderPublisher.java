package com.iol.etlplatform.kafka;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.entity.WorkflowConfig;

import lombok.RequiredArgsConstructor;

/**
 * Delegue la lecture de la source et le transport au source-gateway.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  CE QUE CET ORDRE NE CONTIENT PAS
 * ═══════════════════════════════════════════════════════════════════════════
 *  Aucun secret, aucune configuration, aucune donnee. Seulement l'identifiant du
 *  workflow: le gateway resout tout le reste depuis MongoDB et Vault.
 *
 *  C'est ce qui rend le message inoffensif dans un topic, dans une file
 *  d'erreurs ou dans un journal — et c'est aussi ce qui permet a api-core de
 *  perdre tout acces aux identifiants source, puisqu'il n'a plus besoin de les
 *  dechiffrer pour declencher une execution.
 *
 *  Contrat canonique: {@code contracts/transport-order.schema.json}.
 */
@Service
@RequiredArgsConstructor
public class TransportOrderPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransportOrderPublisher.class);

    public static final String EVENT_TYPE = "TRANSPORT_REQUESTED";

    /** Version du contrat produite ici; le gateway refuse ce qu'il ne connait pas. */
    private static final int SCHEMA_VERSION = 2;
    private static final String LEGACY_REVISION = "legacy-unversioned";

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.kafka.topics.transport-requests:iol.transport.requests}")
    private String transportTopic;

    @Value("${app.tenancy.default-organization-id:iol-default}")
    private String organizationId;

    /**
     * Publie l'ordre et attend son acquittement.
     *
     * L'attente est deliberee: sans elle, une panne Kafka laisserait l'execution
     * en QUEUED sans que personne ne le sache. Ici l'echec remonte a l'appelant,
     * qui marque le journal en echec.
     *
     * @return un message de tracabilite pour le journal d'execution
     */
    public String publishTransportRequested(WorkflowConfig workflow, String execLogId, String requestedBy) {
        int priority = workflow.getPriority() > 0 ? workflow.getPriority() : 3;
        String executionKey = organizationId + ":" + workflow.getId();

        Map<String, Object> order = new LinkedHashMap<>();
        order.put("eventType", EVENT_TYPE);
        order.put("schemaVersion", SCHEMA_VERSION);
        order.put("organizationId", organizationId);
        order.put("workflowId", workflow.getId());
        order.put("workflowRevision", revisionOf(workflow));
        order.put("execLogId", execLogId);
        order.put("executionKey", executionKey);
        order.put("requestedAt", Instant.now().toString());
        if (requestedBy != null && !requestedBy.isBlank()) {
            order.put("requestedBy", requestedBy);
        }
        order.put("priority", priority);

        try {
            // La cle de partition est la meme que celle de la commande finale:
            // l'ordre et les lots de donnees restent ainsi ordonnes entre eux.
            kafkaTemplate.send(transportTopic, executionKey, objectMapper.writeValueAsString(order))
                    .get(30, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            // Restaure le drapeau: un appelant plus haut doit pouvoir constater
            // l'interruption et arreter son propre travail.
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Publication de l'ordre de transport interrompue pour l'execution "
                            + execLogId, interrupted);
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Ordre de transport non publie pour l'execution " + execLogId + ": "
                            + error.getMessage(), error);
        }

        log.info("Ordre de transport publie: workflow='{}' execLogId={} topic={}",
                 workflow.getWorkflowName(), execLogId, transportTopic);
        return "Ordre de transport publie dans " + transportTopic
                + "; lecture de la source deleguee au source-gateway.";
    }

    private String revisionOf(WorkflowConfig workflow) {
        return workflow.getUpdatedAt() == null || workflow.getUpdatedAt().isBlank()
                ? LEGACY_REVISION
                : workflow.getUpdatedAt();
    }
}
