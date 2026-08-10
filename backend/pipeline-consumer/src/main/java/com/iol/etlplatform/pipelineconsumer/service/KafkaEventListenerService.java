package com.iol.etlplatform.pipelineconsumer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final PipelineExecutionRegistry executionRegistry;

    @Value("${app.tenancy.default-organization-id:iol-default}")
    private String defaultOrganizationId = "iol-default";

    @Value("${app.kafka.consumer.retry-backoff-seconds:10}")
    private long retryBackoffSeconds = 10;

    @Value("${app.kafka.consumer.max-attempts:5}")
    private int maxAttempts = 5;

    public KafkaEventListenerService(
            PipelineOrchestrator orchestrator,
            KafkaDataChunkStore dataChunkStore,
            DistributedExecutionLockService executionLockService,
            PipelineExecutionRegistry executionRegistry) {
        this.orchestrator = orchestrator;
        this.dataChunkStore = dataChunkStore;
        this.executionLockService = executionLockService;
        this.executionRegistry = executionRegistry;
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
                acceptDataEvent(node, ack, priorityLabel, workflowId, execLogId, eventType);
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

            if ("unknown".equals(execLogId) || execLogId.isBlank()) {
                throw new IllegalArgumentException("Commande pipeline sans execLogId.");
            }

            PipelineExecutionRegistry.Claim claim =
                    executionRegistry.claim(execLogId, workflowId, record.value());
            if (claim.state() == PipelineExecutionRegistry.ClaimState.BUSY) {
                log.info("[{}] Execution encore detenue, redelivraison differee: execLogId={}",
                        priorityLabel, execLogId);
                retryLater(ack);
                return;
            }
            if (claim.state() == PipelineExecutionRegistry.ClaimState.SUCCESS
                    || claim.state() == PipelineExecutionRegistry.ClaimState.FAILED) {
                orchestrator.replayTerminalStatus(node, execLogId, workflowId, claim.outcome());
                if (claim.state() == PipelineExecutionRegistry.ClaimState.SUCCESS) {
                    cleanupTransferredData(node, priorityLabel, workflowId);
                }
                ack.acknowledge();
                log.info("[{}] Resultat terminal rejoue sans reexecution: execLogId={}",
                        priorityLabel, execLogId);
                return;
            }

            executeClaimed(node, ack, priorityLabel, workflowId, execLogId, claim);
        } catch (Exception e) {
            log.error("[{}] Erreur workflowId={}: {}", priorityLabel, workflowId, e.getMessage(), e);
            // Une erreur avant toute reservation est soit un poison de contrat,
            // soit une panne de l'inbox. Le poison n'avance qu'apres statut+DLQ;
            // une panne d'infrastructure reste redelivrable.
            if (e instanceof IllegalArgumentException || e instanceof JsonProcessingException) {
                try {
                    orchestrator.publishFailure(node, execLogId, workflowId, e.getMessage());
                    ack.acknowledge();
                } catch (Exception publicationFailure) {
                    retryLater(ack);
                }
            } else {
                retryLater(ack);
            }
        }
    }

    private void executeClaimed(
            JsonNode node,
            Acknowledgment ack,
            String priorityLabel,
            String workflowId,
            String execLogId,
            PipelineExecutionRegistry.Claim claim) {
        AtomicBoolean terminalRecorded = new AtomicBoolean(false);
        try (PipelineExecutionRegistry.Lease lease = executionRegistry.heartbeat(claim, execLogId)) {
            String lockKey = executionLockKey(node, workflowId);
            boolean successful;
            try (DistributedExecutionLockService.LockHandle ignored =
                         executionLockService.acquire(lockKey)) {
                successful = orchestrator.execute(node, workflowId, execLogId, outcome -> {
                    executionRegistry.complete(execLogId, claim, lease, outcome);
                    terminalRecorded.set(true);
                });
            }

            if (successful) cleanupTransferredData(node, priorityLabel, workflowId);
            ack.acknowledge();
            log.info("[{}] Message acquitte apres resultat durable — workflowId={} execLogId={}",
                    priorityLabel, workflowId, execLogId);
        } catch (Exception failure) {
            if (terminalRecorded.get()) {
                // Hop/Spark ne doit surtout pas repartir: le snapshot terminal
                // existe deja. La redelivrance ne fera que republier son statut.
                retryLater(ack);
                return;
            }

            if (claim.attempt() >= Math.max(1, maxAttempts)) {
                failClaimPermanently(node, ack, workflowId, execLogId, claim, failure);
                return;
            }
            executionRegistry.release(execLogId, claim, failure);
            retryLater(ack);
            log.error("[{}] Execution reprenable execLogId={} tentative={}: {}",
                    priorityLabel, execLogId, claim.attempt(), failure.getMessage(), failure);
        }
    }

    private void failClaimPermanently(
            JsonNode node,
            Acknowledgment ack,
            String workflowId,
            String execLogId,
            PipelineExecutionRegistry.Claim claim,
            Exception failure) {
        try (PipelineExecutionRegistry.Lease lease = executionRegistry.heartbeat(claim, execLogId)) {
            PipelineExecutionRegistry.Outcome outcome = new PipelineExecutionRegistry.Outcome(
                    false, "", failure.getMessage(), 0);
            executionRegistry.complete(execLogId, claim, lease, outcome);
            orchestrator.replayTerminalStatus(node, execLogId, workflowId, outcome);
            ack.acknowledge();
        } catch (Exception terminalFailure) {
            executionRegistry.release(execLogId, claim, terminalFailure);
            retryLater(ack);
        }
    }

    private void acceptDataEvent(
            JsonNode node,
            Acknowledgment ack,
            String priorityLabel,
            String workflowId,
            String execLogId,
            String eventType) {
        try {
            dataChunkStore.accept(node);
            ack.acknowledge();
            log.debug("[{}] Donnees source durablement acquittees transferId={} eventType={}",
                    priorityLabel, node.path("transferId").asText(), eventType);
        } catch (IllegalArgumentException | IllegalStateException poison) {
            try {
                orchestrator.publishFailure(node, execLogId, workflowId, poison.getMessage());
                ack.acknowledge();
            } catch (Exception publicationFailure) {
                retryLater(ack);
            }
        } catch (Exception infrastructureFailure) {
            log.error("Inbox Kafka indisponible pour transferId={}: {}",
                    node.path("transferId").asText(), infrastructureFailure.getMessage());
            retryLater(ack);
        }
    }

    private void cleanupTransferredData(JsonNode command, String priorityLabel, String workflowId) {
        try {
            dataChunkStore.cleanup(command);
            orchestrator.cleanupTransferredObjects(command);
        } catch (Exception cleanupError) {
            // Les lots Mongo ont un TTL et RustFS son nettoyage differe: une
            // panne de maintenance ne doit jamais relancer une destination deja ecrite.
            log.warn("[{}] Nettoyage differe pour workflowId={}: {}",
                    priorityLabel, workflowId, cleanupError.getMessage());
        }
    }

    private void retryLater(Acknowledgment ack) {
        ack.nack(Duration.ofSeconds(Math.max(1, retryBackoffSeconds)));
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
