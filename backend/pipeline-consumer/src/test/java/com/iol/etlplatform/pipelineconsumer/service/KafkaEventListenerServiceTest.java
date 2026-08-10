package com.iol.etlplatform.pipelineconsumer.service;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaEventListenerServiceTest {

    private PipelineOrchestrator orchestrator;
    private KafkaDataChunkStore chunks;
    private DistributedExecutionLockService locks;
    private PipelineExecutionRegistry registry;
    private KafkaEventListenerService listener;
    private RecordingAck ack;

    @BeforeEach
    void setUp() {
        orchestrator = mock(PipelineOrchestrator.class);
        chunks = mock(KafkaDataChunkStore.class);
        locks = mock(DistributedExecutionLockService.class);
        registry = mock(PipelineExecutionRegistry.class);
        listener = new KafkaEventListenerService(orchestrator, chunks, locks, registry);
        ReflectionTestUtils.setField(listener, "retryBackoffSeconds", 1L);
        ack = new RecordingAck();
    }

    @Test
    void terminalRedeliveryDoesNotExecuteEngineAgain() {
        String payload = command();
        PipelineExecutionRegistry.Outcome outcome =
                new PipelineExecutionRegistry.Outcome(true, "ok", null, 10);
        when(registry.claim("exec-1", "wf-1", payload)).thenReturn(
                PipelineExecutionRegistry.Claim.terminal(
                        PipelineExecutionRegistry.ClaimState.SUCCESS, "hash", 1, outcome));

        listener.onNormalPriorityCommand(record(payload), ack);

        verify(orchestrator, never()).execute(any(), anyString(), anyString(), any());
        verify(orchestrator).replayTerminalStatus(any(), anyString(), anyString(), any());
        assertEquals(1, ack.acknowledged.get());
        assertEquals(0, ack.nacked.get());
    }

    @Test
    void busyCommandIsNotAcknowledged() {
        String payload = command();
        when(registry.claim("exec-1", "wf-1", payload)).thenReturn(
                PipelineExecutionRegistry.Claim.busy("hash", 1));

        listener.onNormalPriorityCommand(record(payload), ack);

        verify(orchestrator, never()).execute(any(), anyString(), anyString(), any());
        assertEquals(0, ack.acknowledged.get());
        assertEquals(1, ack.nacked.get());
    }

    private ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("iol.pipeline.commands", 0, 0L, "wf-1", payload);
    }

    private String command() {
        return "{\"eventType\":\"PIPELINE_EXECUTION_REQUESTED\","
                + "\"workflowId\":\"wf-1\",\"execLogId\":\"exec-1\"}";
    }

    private static final class RecordingAck implements Acknowledgment {
        private final AtomicInteger acknowledged = new AtomicInteger();
        private final AtomicInteger nacked = new AtomicInteger();

        @Override
        public void acknowledge() {
            acknowledged.incrementAndGet();
        }

        @Override
        public void nack(Duration sleep) {
            nacked.incrementAndGet();
        }
    }
}
