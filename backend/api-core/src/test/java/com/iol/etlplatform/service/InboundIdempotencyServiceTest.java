package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.interop.InboundExecutionPrepareResponse;
import com.iol.etlplatform.entity.InboundIdempotencyRecord;
import com.iol.etlplatform.repository.InboundIdempotencyRecordRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InboundIdempotencyServiceTest {

    private final InboundIdempotencyRecordRepository repository =
            mock(InboundIdempotencyRecordRepository.class);
    private final InboundIdempotencyService service =
            new InboundIdempotencyService(repository);

    @Test
    void firstClaimUsesMongoUniqueIdAsTheConcurrencyGate() {
        InboundIdempotencyService.Claim claim = service.claim(
                "iol-default",
                "wf-1",
                "std-1",
                "hospital-a",
                "request-123",
                "a".repeat(64));

        assertFalse(claim.replayed());
        assertNotNull(claim.owner());
        verify(repository).insert(argThat((InboundIdempotencyRecord record) ->
                "IN_PROGRESS".equals(record.getStatus())
                        && record.getId().length() == 64
                        && "a".repeat(64).equals(record.getPayloadHash())
                        && record.getId().indexOf("request-123") < 0));
    }

    @Test
    void completedClaimReturnsTheStoredExecutionWithoutBusinessPayload() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(repository).insert(any(InboundIdempotencyRecord.class));
        InboundIdempotencyRecord completed = completedRecord();
        when(repository.findById(anyString())).thenReturn(Optional.of(completed));

        InboundIdempotencyService.Claim claim = service.claim(
                "iol-default",
                "wf-1",
                "std-1",
                "hospital-a",
                "request-123",
                "a".repeat(64));

        assertTrue(claim.replayed());
        assertEquals("log-1", claim.response().getExecLogId());
        assertTrue(claim.response().isIdempotentReplay());
        assertEquals(Map.of(), claim.response().getCommand());
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(repository).insert(any(InboundIdempotencyRecord.class));
        when(repository.findById(anyString())).thenReturn(Optional.of(completedRecord()));

        ResponseStatusException error = assertThrows(
                ResponseStatusException.class,
                () -> service.claim(
                        "iol-default",
                        "wf-1",
                        "std-1",
                        "hospital-a",
                        "request-123",
                        "b".repeat(64)));

        assertEquals(409, error.getStatusCode().value());
    }

    @Test
    void completionPersistsOnlyTheExecutionReceipt() {
        InboundIdempotencyRecord record = InboundIdempotencyRecord.builder()
                .id("record-1")
                .status("IN_PROGRESS")
                .leaseOwner("owner-1")
                .workflowId("wf-1")
                .organizationId("iol-default")
                .build();
        when(repository.findById("record-1")).thenReturn(Optional.of(record));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.complete(
                new InboundIdempotencyService.Claim(
                        "record-1", "owner-1", false, null),
                InboundExecutionPrepareResponse.builder()
                        .workflowId("wf-1")
                        .execLogId("log-1")
                        .kafkaTopic("iol.pipeline.commands")
                        .kafkaKey("iol-default:wf-1")
                        .organizationId("iol-default")
                        .dataTransport("KAFKA_ROW_BATCH")
                        .recordCount(3)
                        .commandPublished(true)
                        .command(Map.of())
                        .build());

        verify(repository).save(argThat(saved ->
                "COMPLETED".equals(saved.getStatus())
                        && saved.getCompletedAt() != null
                        && saved.getRecordCount() == 3
                        && saved.getLastError() == null));
    }

    private InboundIdempotencyRecord completedRecord() {
        return InboundIdempotencyRecord.builder()
                .id("record-1")
                .status("COMPLETED")
                .organizationId("iol-default")
                .workflowId("wf-1")
                .standardId("std-1")
                .sourceSystem("hospital-a")
                .payloadHash("a".repeat(64))
                .executionLogId("log-1")
                .kafkaTopic("iol.pipeline.commands")
                .kafkaKey("iol-default:wf-1")
                .dataTransport("KAFKA_ROW_BATCH")
                .recordCount(3)
                .commandPublished(true)
                .build();
    }
}
