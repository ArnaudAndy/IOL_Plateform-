package com.iol.etlplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.entity.enums.WorkflowDirection;
import com.iol.etlplatform.kafka.KafkaPipelineEventService;
import com.iol.etlplatform.repository.WorkflowConfigRepository;
import com.iol.etlplatform.util.SqlSafetyValidator;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutboundDeliveryOrchestrationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void successStatusForOutboundWorkflowReadsGoldAndPublishesDeliveryRequest() throws Exception {
        WorkflowConfigRepository workflowRepo = mock(WorkflowConfigRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SqlSafetyValidator sqlSafetyValidator = mock(SqlSafetyValidator.class);
        KafkaPipelineEventService kafkaService = mock(KafkaPipelineEventService.class);

        WorkflowConfig workflow = outboundWorkflow(Map.of(
                "targetStandardId", "std_partner",
                "targetAdapter", "generic-json",
                "maxRows", 1000,
                "source", Map.of("goldTable", "gold.client_orders"),
                "destination", Map.of("openhimChannel", "outbound-client")));
        when(workflowRepo.findById("wf_out")).thenReturn(Optional.of(workflow));

        List<Map<String, Object>> rows = List.of(Map.of("client_id", "C001", "amount", 42));
        when(jdbcTemplate.queryForList("select * from gold.client_orders limit 1000")).thenReturn(rows);

        OutboundDeliveryOrchestrationService service = new OutboundDeliveryOrchestrationService(
                workflowRepo, jdbcTemplate, sqlSafetyValidator, kafkaService);
        ExecutionLog log = executionLog("log_1", "wf_out", ExecutionStatus.SUCCESS);

        boolean requested = service.requestDeliveryIfEligible(log, objectMapper.readTree("""
                {"correlationId":"corr-1","openhimTransactionId":"tx-1"}
                """));

        assertTrue(requested);
        verify(kafkaService).publishOutboundDeliveryRequested(workflow, "log_1", "corr-1", "tx-1", rows);
    }

    @Test
    void nonOutboundWorkflowDoesNotReadGoldOrPublish() throws Exception {
        WorkflowConfigRepository workflowRepo = mock(WorkflowConfigRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SqlSafetyValidator sqlSafetyValidator = mock(SqlSafetyValidator.class);
        KafkaPipelineEventService kafkaService = mock(KafkaPipelineEventService.class);

        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_internal");
        workflow.setDirection(WorkflowDirection.INTERNAL);
        when(workflowRepo.findById("wf_internal")).thenReturn(Optional.of(workflow));

        OutboundDeliveryOrchestrationService service = new OutboundDeliveryOrchestrationService(
                workflowRepo, jdbcTemplate, sqlSafetyValidator, kafkaService);

        boolean requested = service.requestDeliveryIfEligible(
                executionLog("log_2", "wf_internal", ExecutionStatus.SUCCESS),
                objectMapper.readTree("{}"));

        assertFalse(requested);
        verifyNoInteractions(jdbcTemplate, kafkaService);
    }

    @Test
    void querySourceIsValidatedAndWrappedBeforeReadingRows() {
        WorkflowConfigRepository workflowRepo = mock(WorkflowConfigRepository.class);
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        SqlSafetyValidator sqlSafetyValidator = mock(SqlSafetyValidator.class);
        KafkaPipelineEventService kafkaService = mock(KafkaPipelineEventService.class);

        WorkflowConfig workflow = outboundWorkflow(Map.of(
                "targetStandardId", "std_partner",
                "targetAdapter", "generic-json",
                "maxRows", 5,
                "source", Map.of("query", "select client_id from gold.client_orders"),
                "destination", Map.of("endpointUrl", "https://partner.example/receive")));

        List<Map<String, Object>> rows = List.of(Map.of("client_id", "C001"));
        when(jdbcTemplate.queryForList(
                "select * from (select client_id from gold.client_orders) outbound_source limit 5"))
                .thenReturn(rows);

        OutboundDeliveryOrchestrationService service = new OutboundDeliveryOrchestrationService(
                workflowRepo, jdbcTemplate, sqlSafetyValidator, kafkaService);

        assertEquals(rows, service.readPivotRows(workflow));
        verify(sqlSafetyValidator).validateReadOnlySql("select client_id from gold.client_orders");
    }

    private WorkflowConfig outboundWorkflow(Map<String, Object> outboundConfig) {
        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_out");
        workflow.setWorkflowName("Outbound clients");
        workflow.setDirection(WorkflowDirection.OUTBOUND);
        workflow.setOutboundConfig(new LinkedHashMap<>(outboundConfig));
        return workflow;
    }

    private ExecutionLog executionLog(String id, String workflowId, ExecutionStatus status) {
        ExecutionLog log = new ExecutionLog();
        log.setId(id);
        log.setWorkflowId(workflowId);
        log.setStatus(status);
        return log;
    }
}
