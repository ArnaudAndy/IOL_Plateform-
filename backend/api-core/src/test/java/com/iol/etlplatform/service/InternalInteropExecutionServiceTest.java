package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.interop.InboundExecutionPrepareRequest;
import com.iol.etlplatform.dto.interop.InboundExecutionPrepareResponse;
import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.entity.enums.WorkflowDirection;
import com.iol.etlplatform.kafka.KafkaPipelineEventService;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import com.iol.etlplatform.repository.WorkflowConfigRepository;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalInteropExecutionServiceTest {

    private final WorkflowConfigRepository workflowRepository = mock(WorkflowConfigRepository.class);
    private final ExecutionLogRepository executionLogRepository = mock(ExecutionLogRepository.class);
    private final KafkaPipelineEventService kafkaPipelineEventService = mock(KafkaPipelineEventService.class);
    private final InboundIdempotencyService idempotencyService =
            mock(InboundIdempotencyService.class);
    private final InternalInteropExecutionService service;

    InternalInteropExecutionServiceTest() {
        when(idempotencyService.claim(
                any(), any(), any(), any(), any(), any()))
                .thenReturn(InboundIdempotencyService.Claim.claimed(
                        "idem-record-1", "api-owner-1"));
        service = new InternalInteropExecutionService(
                workflowRepository,
                executionLogRepository,
                kafkaPipelineEventService,
                idempotencyService);
    }

    @Test
    void prepareInboundExecutionCreatesLogAndReturnsKafkaCommand() {
        WorkflowConfig workflow = inboundWorkflow();
        when(workflowRepository.findById("wf_1")).thenReturn(Optional.of(workflow));
        when(executionLogRepository.save(any(ExecutionLog.class))).thenAnswer(invocation -> {
            ExecutionLog log = invocation.getArgument(0);
            if (log.getId() == null) {
                log.setId("log_1");
            }
            return log;
        });
        Map<String, Object> command = Map.of(
                "eventType", KafkaPipelineEventService.EVENT_TYPE_PIPELINE_EXECUTION_REQUESTED,
                "workflowId", "wf_1");
        when(kafkaPipelineEventService.publishInboundExecutionRequested(
                eq(workflow),
                eq("log_1"),
                eq("iol-default"),
                eq("std_1"),
                eq("external"),
                eq("corr-1"),
                eq("tx-openhim-1"),
                anyList(),
                isNull()))
                .thenReturn(new KafkaPipelineEventService.InboundPublication(
                        "iol.pipeline.commands",
                        "iol-default:wf_1",
                        command,
                        "KAFKA_ROW_BATCH",
                        1,
                        true));

        InboundExecutionPrepareRequest request = InboundExecutionPrepareRequest.builder()
                .workflowId("wf_1")
                .sourceSystem("external")
                .correlationId("corr-1")
                .openhimTransactionId("tx-openhim-1")
                .idempotencyKey("request-1")
                .pivot(Map.of("patient_id", "P001"))
                .build();

        InboundExecutionPrepareResponse response = service.prepareInboundExecution("std_1", request);

        assertEquals("wf_1", response.getWorkflowId());
        assertEquals("log_1", response.getExecLogId());
        assertEquals("iol.pipeline.commands", response.getKafkaTopic());
        assertEquals("iol-default:wf_1", response.getKafkaKey());
        assertEquals("iol-default", response.getOrganizationId());
        assertEquals(1, response.getRecordCount());
        assertEquals("KAFKA_ROW_BATCH", response.getDataTransport());
        assertTrue(response.isCommandPublished());
        assertTrue(response.getCommand().isEmpty());
        verify(executionLogRepository).save(argThat(log ->
                log.getStatus() == ExecutionStatus.RUNNING
                        && "wf_1".equals(log.getWorkflowId())
                        && "INBOUND".equals(log.getExecutionParams().get("direction"))
                        && "iol-default".equals(log.getExecutionParams().get("organizationId"))
                        && "idem-record-1".equals(
                                log.getExecutionParams().get("idempotencyRecordId"))
                        && "1".equals(log.getExecutionParams().get("recordCount"))));
        verify(idempotencyService).complete(any(), same(response));
    }

    @Test
    void explicitWorkflowMustBeInboundAndAttachedToStandard() {
        WorkflowConfig workflow = inboundWorkflow();
        workflow.setDirection(WorkflowDirection.INTERNAL);
        when(workflowRepository.findById("wf_1")).thenReturn(Optional.of(workflow));

        InboundExecutionPrepareRequest request = InboundExecutionPrepareRequest.builder()
                .workflowId("wf_1")
                .pivot(Map.of("patient_id", "P001"))
                .build();

        assertThrows(RuntimeException.class, () -> service.prepareInboundExecution("std_1", request));
        verifyNoInteractions(executionLogRepository);
    }

    @Test
    void prepareInboundExecutionStreamPersistsTheActualRecordCount() {
        WorkflowConfig workflow = inboundWorkflow();
        when(workflowRepository.findById("wf_1")).thenReturn(Optional.of(workflow));
        when(executionLogRepository.save(any(ExecutionLog.class))).thenAnswer(invocation -> {
            ExecutionLog log = invocation.getArgument(0);
            if (log.getId() == null) log.setId("log_stream");
            return log;
        });
        when(kafkaPipelineEventService.publishInboundExecutionRequestedStream(
                eq(workflow),
                eq("log_stream"),
                eq("iol-default"),
                eq("std_1"),
                eq("edfi"),
                eq("corr-stream"),
                isNull(),
                any(InputStream.class),
                eq(2L),
                eq(48L),
                isNull()))
                .thenReturn(new KafkaPipelineEventService.InboundPublication(
                        "iol.pipeline.commands",
                        "iol-default:wf_1",
                        Map.of(),
                        "KAFKA_ROW_BATCH",
                        2,
                        true));
        InboundExecutionPrepareRequest request =
                InboundExecutionPrepareRequest.builder()
                        .workflowId("wf_1")
                        .sourceSystem("edfi")
                        .correlationId("corr-stream")
                        .idempotencyKey("stream-request-1")
                        .estimatedRows(2L)
                        .estimatedBytes(48L)
                        .build();

        InboundExecutionPrepareResponse response =
                service.prepareInboundExecutionStream(
                        "std_1",
                        request,
                        new ByteArrayInputStream(
                                "{\"student_id\":\"S001\"}\n".getBytes()));

        assertEquals(2, response.getRecordCount());
        assertTrue(response.isCommandPublished());
        verify(executionLogRepository, times(2)).save(any(ExecutionLog.class));
        verify(executionLogRepository, atLeastOnce()).save(argThat(log ->
                "2".equals(log.getExecutionParams().get("recordCount"))));
        verify(idempotencyService).complete(any(), same(response));
    }

    @Test
    void completedIdempotencyReceiptReturnsTheOriginalExecution() {
        WorkflowConfig workflow = inboundWorkflow();
        when(workflowRepository.findById("wf_1")).thenReturn(Optional.of(workflow));
        InboundExecutionPrepareResponse original =
                InboundExecutionPrepareResponse.builder()
                        .workflowId("wf_1")
                        .execLogId("log_original")
                        .commandPublished(true)
                        .idempotentReplay(true)
                        .command(Map.of())
                        .build();
        when(idempotencyService.claim(
                eq("iol-default"),
                eq("wf_1"),
                eq("std_1"),
                eq("external"),
                eq("same-key"),
                isNull()))
                .thenReturn(InboundIdempotencyService.Claim.replayed(
                        "idem-record-1", original));

        InboundExecutionPrepareResponse replay =
                service.prepareInboundExecution(
                        "std_1",
                        InboundExecutionPrepareRequest.builder()
                                .workflowId("wf_1")
                                .sourceSystem("external")
                                .idempotencyKey("same-key")
                                .pivot(Map.of("patient_id", "P001"))
                                .build());

        assertSame(original, replay);
        verifyNoInteractions(executionLogRepository, kafkaPipelineEventService);
    }

    private WorkflowConfig inboundWorkflow() {
        WorkflowConfig workflow = new WorkflowConfig();
        workflow.setId("wf_1");
        workflow.setWorkflowName("Inbound CUSTOM");
        workflow.setStandardId("std_1");
        workflow.setDirection(WorkflowDirection.INBOUND);
        workflow.setActive(true);
        workflow.setPriority(3);
        return workflow;
    }
}
