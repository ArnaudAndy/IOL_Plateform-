package com.iol.etlplatform.sourcegateway.service;

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
import com.iol.etlplatform.sourcegateway.contract.TransportOrder;

/** Publie la progression du transport dans le meme journal que le moteur. */
@Service
public class TransportStatusPublisher {

    private static final Logger log = LoggerFactory.getLogger(TransportStatusPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${app.kafka.topics.status:iol.pipeline.status}")
    private String statusTopic;

    @Value("${app.kafka.producer.status-timeout-seconds:30}")
    private long sendTimeoutSeconds;

    public TransportStatusPublisher(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void started(TransportOrder order, int attempt) throws Exception {
        publish(order, "RUNNING", "TRANSPORT", Map.of("TRANSPORT", "RUNNING"),
                "Transport pris en charge (tentative " + attempt + ").\n", null, null, attempt);
    }

    /** Un battement manque peut etre rattrape par le suivant; il ne casse pas le transport. */
    public void heartbeat(TransportOrder order, int attempt) {
        try {
            publish(order, "RUNNING", "TRANSPORT", Map.of("TRANSPORT", "RUNNING"),
                    "", null, null, attempt);
        } catch (Exception failure) {
            log.warn("Battement de transport non publie pour execLogId={}: {}",
                    order.execLogId(), failure.getMessage());
        }
    }

    public void waitingForEngine(TransportOrder order, int attempt) throws Exception {
        Map<String, String> stages = new LinkedHashMap<>();
        stages.put("TRANSPORT", "SUCCESS");
        stages.put("PREPARATION", "RUNNING");
        publish(order, "RUNNING", "PREPARATION", stages,
                "Donnees transportees; commande remise au moteur.\n", null, null, attempt);
    }

    public void retryScheduled(TransportOrder order, int attempt, Throwable cause) {
        try {
            publish(order, "RUNNING", "TRANSPORT", Map.of("TRANSPORT", "RUNNING"),
                    "Transport interrompu; reprise planifiee: " + rootMessage(cause) + "\n",
                    null, null, attempt);
        } catch (Exception failure) {
            log.warn("Statut de reprise non publie pour execLogId={}: {}",
                    order.execLogId(), failure.getMessage());
        }
    }

    public void failed(TransportOrder order, int attempt, Throwable cause) throws Exception {
        publish(order, "FAILED", "TRANSPORT", Map.of("TRANSPORT", "FAILED"),
                "Echec terminal du transport apres " + attempt + " tentative(s).\n",
                rootMessage(cause), "TRANSPORT", attempt);
    }

    private void publish(
            TransportOrder order,
            String status,
            String currentStage,
            Map<String, String> stageStatuses,
            String logOutput,
            String errorMessage,
            String failedStage,
            int attempt) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventType", "TRANSPORT_STATUS");
        payload.put("execLogId", order.execLogId());
        payload.put("workflowId", order.workflowId());
        payload.put("organizationId", order.organizationId());
        payload.put("status", status);
        payload.put("currentStage", currentStage);
        payload.put("heartbeatAt", Instant.now().toString());
        payload.put("stageStatuses", stageStatuses);
        payload.put("transportAttempt", attempt);
        payload.put("logOutput", logOutput);
        if (errorMessage != null) payload.put("errorMessage", errorMessage);
        if (failedStage != null) payload.put("failedStage", failedStage);

        kafkaTemplate.send(statusTopic, order.executionKey(), objectMapper.writeValueAsString(payload))
                .get(sendTimeoutSeconds, TimeUnit.SECONDS);
    }

    private static String rootMessage(Throwable cause) {
        if (cause == null) return "cause inconnue";
        Throwable current = cause;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
