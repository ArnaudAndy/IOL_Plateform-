package com.iol.etlplatform.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import com.iol.etlplatform.service.OutboundDeliveryOrchestrationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * (c) Vérifie que le message de statut publié par pipeline-consumer, contenant un objet
 * "watermarks" { target_table: valeur }, est persisté dans
 * ExecutionLog.lastSuccessfulWatermarks (par source).
 */
class KafkaStatusWatermarkPersistenceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void watermarksFromStatusMessageArePersistedPerSource() {
        ExecutionLogRepository repo = mock(ExecutionLogRepository.class);

        ExecutionLog existing = new ExecutionLog();
        existing.setId("log_1");
        existing.setWorkflowId("wf_1");
        when(repo.findById("log_1")).thenReturn(Optional.of(existing));

        KafkaStatusListenerService listener = new KafkaStatusListenerService(objectMapper, repo);

        String message = """
            {
              "execLogId": "log_1",
              "workflowId": "wf_1",
              "status": "SUCCESS",
              "durationMs": 1234,
              "watermarks": { "stg_oracle": "2026-06-15T02:00:00Z", "stg_pg": "42" }
            }
            """;

        listener.onStatusUpdate(message);

        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        verify(repo).save(captor.capture());
        Map<String, String> persisted = captor.getValue().getLastSuccessfulWatermarks();

        assertNotNull(persisted, "Les watermarks doivent être persistés");
        assertEquals("2026-06-15T02:00:00Z", persisted.get("stg_oracle"));
        assertEquals("42", persisted.get("stg_pg"));
    }

    @Test
    void existingWatermarksAreMergedNotReplaced() {
        ExecutionLogRepository repo = mock(ExecutionLogRepository.class);

        ExecutionLog existing = new ExecutionLog();
        existing.setId("log_2");
        Map<String, String> prior = new LinkedHashMap<>();
        prior.put("stg_csv", "old_value");
        existing.setLastSuccessfulWatermarks(prior);
        when(repo.findById("log_2")).thenReturn(Optional.of(existing));

        KafkaStatusListenerService listener = new KafkaStatusListenerService(objectMapper, repo);

        String message = """
            {
              "execLogId": "log_2",
              "workflowId": "wf_2",
              "status": "SUCCESS",
              "watermarks": { "stg_oracle": "100" }
            }
            """;

        listener.onStatusUpdate(message);

        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        verify(repo).save(captor.capture());
        Map<String, String> persisted = captor.getValue().getLastSuccessfulWatermarks();

        assertEquals("old_value", persisted.get("stg_csv"), "Les watermarks existants sont conservés");
        assertEquals("100", persisted.get("stg_oracle"), "Le nouveau watermark est ajouté");
    }

    @Test
    void runningProgressPersistsHeartbeatStageAndIncrementalLogsWithoutEndingExecution() {
        ExecutionLogRepository repo = mock(ExecutionLogRepository.class);
        ExecutionLog existing = new ExecutionLog();
        existing.setId("log_progress");
        existing.setDetailedLogs("preparation\n");
        when(repo.findById("log_progress")).thenReturn(Optional.of(existing));

        KafkaStatusListenerService listener = new KafkaStatusListenerService(objectMapper, repo);
        listener.onStatusUpdate("""
                {
                  "execLogId": "log_progress",
                  "workflowId": "wf_progress",
                  "status": "RUNNING",
                  "currentStage": "SILVER",
                  "heartbeatAt": "2026-07-19T12:00:00Z",
                  "logOutput": "silver en cours",
                  "stageStatuses": {"BRONZE":"SUCCESS","SILVER":"RUNNING","GOLD":"NOT_RUN"}
                }
                """);

        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        verify(repo).save(captor.capture());
        ExecutionLog saved = captor.getValue();
        assertEquals("SILVER", saved.getCurrentStage());
        assertEquals(Instant.parse("2026-07-19T12:00:00Z"), saved.getLastHeartbeatAt());
        assertNull(saved.getEndTime());
        assertTrue(saved.getDetailedLogs().contains("preparation"));
        assertTrue(saved.getDetailedLogs().contains("silver en cours"));
        assertEquals("RUNNING", saved.getStageStatuses().get("SILVER"));
    }

    @Test
    void interopContextFromStatusMessageIsMergedIntoExecutionParams() {
        ExecutionLogRepository repo = mock(ExecutionLogRepository.class);

        ExecutionLog existing = new ExecutionLog();
        existing.setId("log_3");
        existing.setExecutionParams(new LinkedHashMap<>(Map.of("direction", "INBOUND")));
        when(repo.findById("log_3")).thenReturn(Optional.of(existing));

        KafkaStatusListenerService listener = new KafkaStatusListenerService(objectMapper, repo);

        String message = """
            {
              "execLogId": "log_3",
              "workflowId": "wf_3",
              "status": "FAILED",
              "direction": "INBOUND",
              "standardId": "std_custom",
              "sourceSystem": "external",
              "correlationId": "corr-3",
              "openhimTransactionId": "tx-openhim-3"
            }
            """;

        listener.onStatusUpdate(message);

        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        verify(repo).save(captor.capture());
        Map<String, String> params = captor.getValue().getExecutionParams();

        assertEquals("INBOUND", params.get("direction"));
        assertEquals("std_custom", params.get("standardId"));
        assertEquals("external", params.get("sourceSystem"));
        assertEquals("corr-3", params.get("correlationId"));
        assertEquals("tx-openhim-3", params.get("openhimTransactionId"));
    }

    @Test
    void successStatusTriggersOutboundDeliveryOrchestrationAfterLogUpdate() {
        ExecutionLogRepository repo = mock(ExecutionLogRepository.class);
        OutboundDeliveryOrchestrationService outboundService = mock(OutboundDeliveryOrchestrationService.class);
        ObjectProvider<OutboundDeliveryOrchestrationService> provider = mock(ObjectProvider.class);

        ExecutionLog existing = new ExecutionLog();
        existing.setId("log_out");
        existing.setWorkflowId("wf_out");
        when(repo.findById("log_out")).thenReturn(Optional.of(existing));
        when(provider.getIfAvailable()).thenReturn(outboundService);

        KafkaStatusListenerService listener = new KafkaStatusListenerService(objectMapper, repo, provider);

        String message = """
            {
              "execLogId": "log_out",
              "workflowId": "wf_out",
              "status": "SUCCESS",
              "correlationId": "corr-out"
            }
            """;

        listener.onStatusUpdate(message);

        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        verify(repo).save(captor.capture());
        verify(outboundService).requestDeliveryIfEligible(eq(captor.getValue()), any());
        assertEquals(com.iol.etlplatform.entity.enums.ExecutionStatus.SUCCESS, captor.getValue().getStatus());
    }

    @Test
    void outboundDeliveryStatusMarksExecutionLogAsDelivered() {
        ExecutionLogRepository repo = mock(ExecutionLogRepository.class);

        ExecutionLog existing = new ExecutionLog();
        existing.setId("log_delivered");
        existing.setWorkflowId("wf_out");
        existing.setExecutionParams(new LinkedHashMap<>(Map.of("direction", "OUTBOUND")));
        when(repo.findById("log_delivered")).thenReturn(Optional.of(existing));

        KafkaStatusListenerService listener = new KafkaStatusListenerService(objectMapper, repo);

        String message = """
            {
              "eventType": "OUTBOUND_DELIVERY_STATUS",
              "execLogId": "log_delivered",
              "workflowId": "wf_out",
              "status": "DELIVERED",
              "correlationId": "corr-delivered",
              "targetStandardId": "std_partner",
              "targetAdapter": "generic-json",
              "destination": "outbound-client",
              "attempts": 2
            }
            """;

        listener.onStatusUpdate(message);

        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        verify(repo).save(captor.capture());
        ExecutionLog saved = captor.getValue();

        assertEquals(com.iol.etlplatform.entity.enums.ExecutionStatus.DELIVERED, saved.getStatus());
        assertEquals("DELIVERED", saved.getExecutionParams().get("deliveryStatus"));
        assertEquals("corr-delivered", saved.getExecutionParams().get("correlationId"));
        assertEquals("std_partner", saved.getExecutionParams().get("targetStandardId"));
        assertEquals("generic-json", saved.getExecutionParams().get("targetAdapter"));
        assertEquals("outbound-client", saved.getExecutionParams().get("destination"));
        assertEquals("2", saved.getExecutionParams().get("deliveryAttempts"));
    }
}
