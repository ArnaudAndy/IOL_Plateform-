package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.interop.InboundExecutionPrepareRequest;
import com.iol.etlplatform.dto.interop.InboundExecutionPrepareResponse;
import com.iol.etlplatform.dto.interop.OutboundDenormalizeRequest;
import com.iol.etlplatform.dto.interop.OutboundDenormalizeResponse;
import com.iol.etlplatform.dto.standard.StandardTermDto;
import com.iol.etlplatform.dto.standard.StandardValidationBatchRequest;
import com.iol.etlplatform.dto.standard.StandardValidationBatchResponse;
import com.iol.etlplatform.entity.StandardTerm;
import com.iol.etlplatform.service.InternalInteropExecutionService;
import com.iol.etlplatform.service.StandardService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalInteropControllerTest {

    private final StandardService standardService = mock(StandardService.class);
    private final InternalInteropExecutionService interopExecutionService = mock(InternalInteropExecutionService.class);
    private final InternalInteropController controller = new InternalInteropController(
            standardService, interopExecutionService, "secret");

    @Test
    void getTermsRequiresInternalSecret() {
        ResponseEntity<?> response = controller.getTerms("std_1", "bad");

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(standardService);
    }

    @Test
    void getTermsReturnsTermsWhenSecretMatches() {
        when(standardService.getTermsByStandard("std_1")).thenReturn(List.of(
                StandardTermDto.builder()
                        .standardId("std_1")
                        .termName("patient_id")
                        .dataType(StandardTerm.DataType.STRING)
                        .build()));

        ResponseEntity<?> response = controller.getTerms("std_1", "secret");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(List.class, response.getBody());
        verify(standardService).getTermsByStandard("std_1");
    }

    @Test
    void validateBatchDelegatesToStandardService() {
        when(standardService.validateFieldAgainstStandard("std_1", "patient_id", "P001", "STRING"))
                .thenReturn(true);

        StandardValidationBatchRequest request = StandardValidationBatchRequest.builder()
                .fields(List.of(StandardValidationBatchRequest.FieldValidationRequest.builder()
                        .fieldName("patient_id")
                        .fieldValue("P001")
                        .dataType("STRING")
                        .build()))
                .build();

        ResponseEntity<?> response = controller.validateBatch("std_1", "secret", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        StandardValidationBatchResponse body = (StandardValidationBatchResponse) response.getBody();
        assertNotNull(body);
        assertTrue(body.isValid());
        assertEquals(1, body.getResults().size());
        verify(standardService).validateFieldAgainstStandard("std_1", "patient_id", "P001", "STRING");
    }

    @Test
    void disabledWhenNoSecretConfigured() {
        InternalInteropController disabled = new InternalInteropController(standardService, interopExecutionService, "");

        ResponseEntity<?> response = disabled.getTerms("std_1", "anything");

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
    }

    @Test
    void prepareInboundExecutionDelegatesToInteropExecutionService() {
        InboundExecutionPrepareRequest request = InboundExecutionPrepareRequest.builder()
                .workflowId("wf_1")
                .sourceSystem("external")
                .idempotencyKey("request-1")
                .pivot(java.util.Map.of("patient_id", "P001"))
                .build();
        InboundExecutionPrepareResponse prepared = InboundExecutionPrepareResponse.builder()
                .workflowId("wf_1")
                .execLogId("log_1")
                .kafkaTopic("iol.pipeline.commands")
                .kafkaKey("wf_1")
                .command(java.util.Map.of("workflowId", "wf_1"))
                .build();
        when(interopExecutionService.prepareInboundExecution("std_1", request)).thenReturn(prepared);

        ResponseEntity<?> response = controller.prepareInboundExecution("std_1", "secret", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(prepared, response.getBody());
        verify(interopExecutionService).prepareInboundExecution("std_1", request);
    }

    @Test
    void prepareInboundExecutionStreamPassesMetadataAndBody() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        byte[] body = "{\"student_id\":\"S001\"}\n".getBytes();
        request.setContent(body);
        request.setContentType("application/x-ndjson");
        request.addHeader("X-IOL-Workflow-Id", "wf_stream");
        request.addHeader("X-IOL-Source-System", "edfi");
        request.addHeader("X-Correlation-Id", "corr-stream");
        request.addHeader("Idempotency-Key", "stream-request-1");
        request.addHeader("X-IOL-Payload-SHA256", "a".repeat(64));
        request.addHeader("X-IOL-Estimated-Rows", "1");
        InboundExecutionPrepareResponse prepared =
                InboundExecutionPrepareResponse.builder()
                        .workflowId("wf_stream")
                        .execLogId("log_stream")
                        .recordCount(1)
                        .commandPublished(true)
                        .build();
        when(interopExecutionService.prepareInboundExecutionStream(
                eq("std_edfi"),
                any(InboundExecutionPrepareRequest.class),
                any(InputStream.class)))
                .thenReturn(prepared);

        ResponseEntity<?> response = controller.prepareInboundExecutionStream(
                "std_edfi", "secret", request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertSame(prepared, response.getBody());
        var metadata = org.mockito.ArgumentCaptor.forClass(
                InboundExecutionPrepareRequest.class);
        verify(interopExecutionService).prepareInboundExecutionStream(
                eq("std_edfi"), metadata.capture(), any(InputStream.class));
        assertEquals("wf_stream", metadata.getValue().getWorkflowId());
        assertEquals("edfi", metadata.getValue().getSourceSystem());
        assertEquals("stream-request-1", metadata.getValue().getIdempotencyKey());
        assertEquals("a".repeat(64), metadata.getValue().getPayloadHash());
        assertEquals(1L, metadata.getValue().getEstimatedRows());
        assertEquals((long) body.length, metadata.getValue().getEstimatedBytes());
    }

    @Test
    void denormalizeRequiresInternalSecret() {
        ResponseEntity<?> response = controller.denormalizeFromPivot("std_1", "bad",
                OutboundDenormalizeRequest.builder()
                        .targetSystem("fhir")
                        .pivotRows(List.of(java.util.Map.of("patient_id", "P001")))
                        .build());

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verify(standardService, never()).denormalizeFromPivot(any(), any(), any());
    }

    @Test
    void denormalizeDelegatesToStandardService() {
        var rows = List.of(java.util.Map.<String, Object>of("identifier", "P001"));
        when(standardService.denormalizeFromPivot("std_1", List.of(java.util.Map.of("patient_id", "P001")), "fhir"))
                .thenReturn(rows);

        ResponseEntity<?> response = controller.denormalizeFromPivot("std_1", "secret",
                OutboundDenormalizeRequest.builder()
                        .targetSystem("fhir")
                        .pivotRows(List.of(java.util.Map.of("patient_id", "P001")))
                        .build());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        OutboundDenormalizeResponse body = (OutboundDenormalizeResponse) response.getBody();
        assertNotNull(body);
        assertEquals("std_1", body.getStandardId());
        assertEquals("fhir", body.getTargetSystem());
        assertSame(rows, body.getRows());
    }
}
