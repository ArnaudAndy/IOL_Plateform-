package com.iol.etlplatform.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import com.iol.etlplatform.service.OutboundDeliveryOrchestrationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Écoute le topic iol.pipeline.status.
 *
 * Publié par pipeline-consumer après chaque fin d'exécution Hop
 * (succès ou échec). Met à jour le log d'exécution dans MongoDB.
 *
 * Format du message attendu :
 * {
 *   "execLogId"    : "abc123",
 *   "workflowId"   : "wf456",
 *   "status"       : "SUCCESS" | "FAILED",
 *   "rowsExtracted": 5000,
 *   "rowsCleaned"  : 4987,
 *   "rowsGold"     : 42,
 *   "durationMs"   : 12345,
 *   "logOutput"    : "...",
 *   "errorMessage" : null | "..."
 * }
 */
@Service
@ConditionalOnProperty(name = "app.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaStatusListenerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaStatusListenerService.class);

    private final ObjectMapper objectMapper;
    private final ExecutionLogRepository executionLogRepository;
    private final ObjectProvider<OutboundDeliveryOrchestrationService> outboundDeliveryServiceProvider;

    @Autowired
    public KafkaStatusListenerService(
            ObjectMapper objectMapper,
            ExecutionLogRepository executionLogRepository,
            ObjectProvider<OutboundDeliveryOrchestrationService> outboundDeliveryServiceProvider) {
        this.objectMapper = objectMapper;
        this.executionLogRepository = executionLogRepository;
        this.outboundDeliveryServiceProvider = outboundDeliveryServiceProvider;
    }

    KafkaStatusListenerService(ObjectMapper objectMapper, ExecutionLogRepository executionLogRepository) {
        this(objectMapper, executionLogRepository, null);
    }

    @KafkaListener(
        topics    = {
                "${app.kafka.topics.status:iol.pipeline.status}",
                "${app.kafka.topics.outbound-status:iol.outbound.status}"
        },
        groupId   = "${app.kafka.consumer.group.status:api-core-status-group}"
    )
    public void onStatusUpdate(String message) {
        try {
            JsonNode node = objectMapper.readTree(message);
            if ("OUTBOUND_DELIVERY_STATUS".equalsIgnoreCase(node.path("eventType").asText(""))) {
                onOutboundDeliveryStatus(node);
                return;
            }

            String execLogId  = node.path("execLogId").asText(null);
            String workflowId = node.path("workflowId").asText(null);
            String statusStr  = node.path("status").asText("FAILED");

            log.info("Réception statut pipeline — execLogId={} workflowId={} status={}",
                    execLogId, workflowId, statusStr);

            if (execLogId == null || execLogId.isBlank()) {
                log.warn("Message de statut sans execLogId ignoré.");
                return;
            }

            ExecutionLog execLog = executionLogRepository.findById(execLogId).orElse(null);
            if (execLog == null) {
                log.warn("ExecutionLog introuvable pour id={}", execLogId);
                return;
            }

            ExecutionStatus nextStatus;
            try {
                nextStatus = ExecutionStatus.valueOf(statusStr.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException invalidStatus) {
                log.warn("Statut pipeline inconnu '{}' ignore pour execLogId={}", statusStr, execLogId);
                return;
            }

            boolean terminal = nextStatus != ExecutionStatus.RUNNING;
            execLog.setStatus(nextStatus);
            execLog.setLastHeartbeatAt(parseInstant(node.path("heartbeatAt").asText(null), Instant.now()));
            String currentStage = node.path("currentStage").asText(null);
            if (currentStage != null && !currentStage.isBlank()) {
                execLog.setCurrentStage(currentStage.toUpperCase(java.util.Locale.ROOT));
            }
            if (terminal) {
                execLog.setEndTime(Instant.now());
            } else {
                execLog.setEndTime(null);
            }

            if (!node.path("rowsExtracted").isMissingNode())
                execLog.setRowsExtracted(node.path("rowsExtracted").asLong());
            if (!node.path("rowsCleaned").isMissingNode())
                execLog.setRowsCleaned(node.path("rowsCleaned").asLong());
            if (!node.path("rowsGold").isMissingNode())
                execLog.setRowsTransformed(node.path("rowsGold").asLong());
            if (!node.path("durationMs").isMissingNode())
                execLog.setIngestionDurationMs(node.path("durationMs").asLong());

            String hopLog = node.path("logOutput").asText(null);
            if (hopLog != null && !hopLog.isBlank()) {
                execLog.setDetailedLogs(appendBounded(execLog.getDetailedLogs(), hopLog));
            }

            String error = node.path("errorMessage").asText(null);
            if (error != null && !error.isBlank()) execLog.setErrorMessage(error);
            String failedStage = node.path("failedStage").asText(null);
            if (failedStage != null && !failedStage.isBlank()) execLog.setFailedStage(failedStage);

            JsonNode stageStatuses = node.path("stageStatuses");
            if (stageStatuses.isObject()) {
                Map<String, String> persistedStages = execLog.getStageStatuses() == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(execLog.getStageStatuses());
                stageStatuses.fields().forEachRemaining(entry ->
                        persistedStages.put(entry.getKey(), entry.getValue().asText()));
                execLog.setStageStatuses(persistedStages);
            }

            // Watermarks incrémentaux calculés par le moteur (par source, clé = target_table).
            // Publiés par pipeline-consumer sous la forme { "watermarks": { "stg_x": "..." } }.
            JsonNode watermarks = node.path("watermarks");
            if (watermarks.isObject() && !watermarks.isEmpty()) {
                Map<String, String> merged = execLog.getLastSuccessfulWatermarks();
                if (merged == null) merged = new java.util.LinkedHashMap<>();
                var it = watermarks.fields();
                while (it.hasNext()) {
                    var entry = it.next();
                    if (!entry.getValue().isNull()) {
                        merged.put(entry.getKey(), entry.getValue().asText());
                    }
                }
                execLog.setLastSuccessfulWatermarks(merged);
                log.info("Watermarks persistés pour execLogId={} : {}", execLogId, merged);
            }

            mergeInteropExecutionParams(execLog, node);

            executionLogRepository.save(execLog);
            log.info("ExecutionLog {} mis à jour → {}", execLogId, statusStr);
            triggerOutboundDeliveryIfNeeded(execLog, node);

        } catch (Exception e) {
            log.error("Erreur traitement message statut Kafka: {}", e.getMessage(), e);
        }
    }

    private void onOutboundDeliveryStatus(JsonNode node) {
        String execLogId = node.path("execLogId").asText(null);
        String statusStr = node.path("status").asText("FAILED");
        log.info("Reception statut livraison OUTBOUND — execLogId={} status={}", execLogId, statusStr);

        if (execLogId == null || execLogId.isBlank()) {
            log.warn("Message de statut OUTBOUND sans execLogId ignore.");
            return;
        }

        ExecutionLog execLog = executionLogRepository.findById(execLogId).orElse(null);
        if (execLog == null) {
            log.warn("ExecutionLog introuvable pour livraison OUTBOUND id={}", execLogId);
            return;
        }

        execLog.setStatus("DELIVERED".equalsIgnoreCase(statusStr)
                ? ExecutionStatus.DELIVERED
                : ExecutionStatus.FAILED);
        execLog.setEndTime(Instant.now());

        String error = node.path("errorMessage").asText(null);
        if (error != null && !error.isBlank()) {
            execLog.setErrorMessage(error);
        }
        if (execLog.getStatus() == ExecutionStatus.FAILED) {
            execLog.setFailedStage("INTEROPERABILITY");
        }

        mergeOutboundDeliveryParams(execLog, node);
        executionLogRepository.save(execLog);
        log.info("ExecutionLog {} mis a jour apres livraison OUTBOUND → {}", execLogId, execLog.getStatus());
    }

    private void triggerOutboundDeliveryIfNeeded(ExecutionLog execLog, JsonNode node) {
        if (outboundDeliveryServiceProvider == null) {
            return;
        }
        OutboundDeliveryOrchestrationService outboundDeliveryService =
                outboundDeliveryServiceProvider.getIfAvailable();
        if (outboundDeliveryService == null) {
            return;
        }
        try {
            outboundDeliveryService.requestDeliveryIfEligible(execLog, node);
        } catch (Exception e) {
            log.error("Livraison OUTBOUND non publiee pour execLogId={}: {}",
                    execLog.getId(), e.getMessage(), e);
        }
    }

    private void mergeInteropExecutionParams(ExecutionLog execLog, JsonNode node) {
        Map<String, String> params = execLog.getExecutionParams();
        if (params == null) {
            params = new LinkedHashMap<>();
        } else {
            params = new LinkedHashMap<>(params);
        }

        putIfPresent(params, "direction", node.path("direction").asText(""));
        putIfPresent(params, "standardId", node.path("standardId").asText(""));
        putIfPresent(params, "sourceSystem", node.path("sourceSystem").asText(""));
        putIfPresent(params, "correlationId", node.path("correlationId").asText(""));
        putIfPresent(params, "openhimTransactionId", node.path("openhimTransactionId").asText(""));

        if (!params.isEmpty()) {
            execLog.setExecutionParams(params);
        }
    }

    private void mergeOutboundDeliveryParams(ExecutionLog execLog, JsonNode node) {
        Map<String, String> params = execLog.getExecutionParams();
        if (params == null) {
            params = new LinkedHashMap<>();
        } else {
            params = new LinkedHashMap<>(params);
        }

        putIfPresent(params, "direction", "OUTBOUND");
        putIfPresent(params, "deliveryStatus", node.path("status").asText(""));
        putIfPresent(params, "correlationId", node.path("correlationId").asText(""));
        putIfPresent(params, "targetStandardId", node.path("targetStandardId").asText(""));
        putIfPresent(params, "targetAdapter", node.path("targetAdapter").asText(""));
        putIfPresent(params, "destination", node.path("destination").asText(""));
        putIfPresent(params, "deliveryAttempts", node.path("attempts").asText(""));

        if (!params.isEmpty()) {
            execLog.setExecutionParams(params);
        }
    }

    private void putIfPresent(Map<String, String> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }

    private Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String appendBounded(String existing, String addition) {
        String left = existing == null ? "" : existing;
        String separator = left.isBlank() || left.endsWith("\n") ? "" : "\n";
        String value = left + separator + addition;
        if (!value.endsWith("\n")) value += "\n";
        int maximum = 512_000;
        return value.length() <= maximum ? value : value.substring(value.length() - maximum);
    }
}
