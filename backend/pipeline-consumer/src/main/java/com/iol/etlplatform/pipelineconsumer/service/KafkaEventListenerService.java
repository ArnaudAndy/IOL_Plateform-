package com.iol.etlplatform.pipelineconsumer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

/**
 * Consomme les 3 topics de commandes Kafka dans l'ordre de priorité.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  GESTION DE LA CHARGE SIMULTANÉE
 * ═══════════════════════════════════════════════════════════════════
 *
 * Scénario : 10 utilisateurs soumettent en même temps
 *
 *   api-core publie dans Kafka :
 *     iol.pipeline.high    → [wf_urgent_A]
 *     iol.pipeline.commands → [wf_B, wf_C, wf_D, wf_E, wf_F]
 *     iol.pipeline.low     → [wf_backup_G, wf_backup_H]
 *
 *   pipeline-consumer dépile 1 à la fois :
 *     1. wf_urgent_A   (HIGH   → priorité 1-2)
 *     2. wf_B          (NORMAL → priorité 3)
 *     3. wf_C          (NORMAL)
 *     ... etc
 *     8. wf_backup_G   (LOW    → priorité 4-5, traité en dernier)
 *
 *   Hop reçoit toujours 1 pipeline à la fois → jamais surchargé
 *
 * ═══════════════════════════════════════════════════════════════════
 *  ACQUITTEMENT MANUEL
 * ═══════════════════════════════════════════════════════════════════
 * Le message Kafka reste dans le topic jusqu'à ce que Hop ait terminé.
 * Si pipeline-consumer redémarre pendant une exécution → le message
 * sera traité à nouveau au redémarrage (idempotence via SHA-256).
 */
@Service
public class KafkaEventListenerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventListenerService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final PipelineOrchestrator orchestrator;
    private final KafkaDataChunkStore dataChunkStore;
    private final DistributedExecutionLockService executionLockService;

    @Value("${app.tenancy.default-organization-id:iol-default}")
    private String defaultOrganizationId = "iol-default";

    public KafkaEventListenerService(
            PipelineOrchestrator orchestrator,
            KafkaDataChunkStore dataChunkStore,
            DistributedExecutionLockService executionLockService) {
        this.orchestrator = orchestrator;
        this.dataChunkStore = dataChunkStore;
        this.executionLockService = executionLockService;
    }

    // ── Priorité HIGH : traité en premier ────────────────────────────────────
    @KafkaListener(
        topics         = "${app.kafka.topics.commands.high:iol.pipeline.high}",
        groupId        = "${app.kafka.consumer.group:pipeline-consumer-group}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onHighPriorityCommand(ConsumerRecord<String, String> record, Acknowledgment ack) {
        handleCommand(record, ack, "HIGH");
    }

    // ── Priorité NORMAL : traité après HIGH ──────────────────────────────────
    @KafkaListener(
        topics         = "${app.kafka.topics.commands:iol.pipeline.commands}",
        groupId        = "${app.kafka.consumer.group:pipeline-consumer-group}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onNormalPriorityCommand(ConsumerRecord<String, String> record, Acknowledgment ack) {
        handleCommand(record, ack, "NORMAL");
    }

    // ── Priorité LOW : traité en dernier ─────────────────────────────────────
    @KafkaListener(
        topics         = "${app.kafka.topics.commands.low:iol.pipeline.low}",
        groupId        = "${app.kafka.consumer.group:pipeline-consumer-group}",
        containerFactory = "kafkaListenerContainerFactory"
    )
    public void onLowPriorityCommand(ConsumerRecord<String, String> record, Acknowledgment ack) {
        handleCommand(record, ack, "LOW");
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void handleCommand(ConsumerRecord<String, String> record, Acknowledgment ack, String priorityLabel) {
        String workflowId = "unknown";
        String execLogId  = "unknown";
        JsonNode node = null;
        try {
            log.info("[{}] Message reçu — offset={} partition={} key={}",
                    priorityLabel, record.offset(), record.partition(), record.key());

            node = objectMapper.readTree(record.value());
            workflowId = node.path("workflowId").asText("unknown");
            execLogId  = node.path("execLogId").asText("unknown");
            String eventType = node.path("eventType").asText("UNKNOWN");
            validateInteropOrganization(node, record.key(), eventType);

            if ("PIPELINE_SOURCE_DATA_CHUNK".equals(eventType)
                    || "PIPELINE_SOURCE_ROW_BATCH".equals(eventType)) {
                dataChunkStore.accept(node);
                ack.acknowledge();
                log.debug("[{}] Données source acquittées transferId={} eventType={}",
                        priorityLabel, node.path("transferId").asText(), eventType);
                return;
            }

            if ("PIPELINE_SOURCE_TRANSFER_ABORTED".equals(eventType)) {
                dataChunkStore.abort(node);
                ack.acknowledge();
                log.warn("[{}] Transfert source abandonné et nettoyé transferId={} raison={}",
                        priorityLabel,
                        node.path("transferId").asText(),
                        node.path("reason").asText("non précisée"));
                return;
            }

            if (!"PIPELINE_EXECUTION_REQUESTED".equals(eventType)) {
                log.warn("[{}] Type non géré : {} — ignoré.", priorityLabel, eventType);
                ack.acknowledge();
                return;
            }

            log.info("[{}] Lancement pipeline — workflowId={} execLogId={}",
                    priorityLabel, workflowId, execLogId);

            String lockKey = executionLockKey(node, workflowId);
            boolean successful;
            try (DistributedExecutionLockService.LockHandle ignored =
                         executionLockService.acquire(lockKey)) {
                successful = orchestrator.execute(node, workflowId, execLogId);
            }

            ack.acknowledge();
            log.info("[{}] Message acquitté après exécution Hop — workflowId={}", priorityLabel, workflowId);
            if (successful) {
                try {
                    orchestrator.cleanupTransferredObjects(node);
                } catch (Exception cleanupError) {
                    log.warn("[{}] Nettoyage RustFS différé pour workflowId={}: {}",
                            priorityLabel, workflowId, cleanupError.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("[{}] Erreur workflowId={}: {}", priorityLabel, workflowId, e.getMessage(), e);
            orchestrator.publishFailure(node, execLogId, workflowId, e.getMessage());
            // Acquitter même en erreur — l'erreur est publiée en DLQ
            ack.acknowledge();
        }
    }

    String executionLockKey(JsonNode command, String workflowId) {
        String explicit = command.path("executionKey").asText("").trim();
        if (!explicit.isBlank()) {
            return explicit;
        }
        JsonNode target = command.path("sources").path(0).path("config").path("target_connection");
        String connectionId = target.path("connection_id").asText("").trim();
        if (!connectionId.isBlank()) return "destination:" + connectionId;

        String dbType = target.path("db_type").asText("").trim();
        String host = target.path("host").asText("").trim();
        String port = target.path("port").asText("").trim();
        String database = target.path("database").asText("").trim();
        if (!dbType.isBlank() || !host.isBlank() || !database.isBlank()) {
            return "destination:" + dbType + ":" + host + ":" + port + ":" + database;
        }
        return "workflow:" + workflowId;
    }

    /**
     * Refuse tout événement interop qui ne porte pas l'organisation unique de la
     * plateforme, et dont la clé Kafka n'est pas partitionnée par cette organisation.
     * La vérification est inconditionnelle : la plateforme est mono-organisation et
     * le producteur émet toujours une clé de la forme {organizationId}:{workflowId}.
     */
    private void validateInteropOrganization(JsonNode event, String kafkaKey, String eventType) {
        boolean interopEvent = "INBOUND".equalsIgnoreCase(event.path("direction").asText(""))
                || event.hasNonNull("organizationId");
        if (!interopEvent) return;

        String organizationId = event.path("organizationId").asText("");
        if (!defaultOrganizationId.equals(organizationId)) {
            throw new IllegalArgumentException(
                    "Organisation Kafka refusée pour l'événement " + eventType + ".");
        }
        if (kafkaKey == null || !kafkaKey.startsWith(organizationId + ":")) {
            throw new IllegalArgumentException(
                    "La clé Kafka n'est pas partitionnée par l'organisation attendue.");
        }
    }
}
