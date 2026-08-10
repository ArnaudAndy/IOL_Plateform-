package com.iol.etlplatform.pipelineconsumer.service;

import com.iol.etlplatform.pipelineconsumer.entity.PipelineExecutionClaim;
import com.mongodb.client.result.UpdateResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PipelineExecutionRegistryTest {

    private MongoTemplate mongo;
    private PipelineExecutionRegistry registry;

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        registry = new PipelineExecutionRegistry(mongo);
        ReflectionTestUtils.setField(registry, "leaseSeconds", 7200L);
        ReflectionTestUtils.setField(registry, "heartbeatSeconds", 30L);
    }

    @AfterEach
    void tearDown() {
        registry.stopScheduler();
    }

    @Test
    void firstCommandGetsPersistentClaim() {
        PipelineExecutionRegistry.Claim claim = registry.claim("exec-1", "wf-1", "{\"a\":1}");

        assertEquals(PipelineExecutionRegistry.ClaimState.ACQUIRED, claim.state());
        assertNotNull(claim.fencingToken());
        assertEquals(1, claim.attempt());
        verify(mongo).insert(any(PipelineExecutionClaim.class));
    }

    @Test
    void concurrentCommandIsBusyInsteadOfCompleted() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(mongo).insert(any(PipelineExecutionClaim.class));
        PipelineExecutionClaim current = document(PipelineExecutionClaim.State.IN_PROGRESS, "exec-1");
        current.setAttempts(1);
        current.setCommandHash(hashOf("{\"a\":1}"));
        when(mongo.findById("exec-1", PipelineExecutionClaim.class)).thenReturn(current);

        assertEquals(PipelineExecutionRegistry.ClaimState.BUSY,
                registry.claim("exec-1", "wf-1", "{\"a\":1}").state());
    }

    @Test
    void terminalSnapshotSurvivesRedelivery() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(mongo).insert(any(PipelineExecutionClaim.class));
        PipelineExecutionClaim current = document(PipelineExecutionClaim.State.SUCCESS, "exec-1");
        current.setAttempts(1);
        current.setCommandHash(hashOf("{\"a\":1}"));
        current.setSuccess(true);
        current.setLogOutput("termine");
        current.setDurationMs(42);
        when(mongo.findById("exec-1", PipelineExecutionClaim.class)).thenReturn(current);

        PipelineExecutionRegistry.Claim claim =
                registry.claim("exec-1", "wf-1", "{\"a\":1}");

        assertEquals(PipelineExecutionRegistry.ClaimState.SUCCESS, claim.state());
        assertEquals("termine", claim.outcome().logOutput());
        assertEquals(42, claim.outcome().durationMs());
    }

    @Test
    void completionIsWrittenBeforeKafkaAck() {
        PipelineExecutionRegistry.Claim claim = PipelineExecutionRegistry.Claim.acquired(
                "token-1", "hash-1", 1);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(PipelineExecutionClaim.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        try (PipelineExecutionRegistry.Lease lease = registry.heartbeat(claim, "exec-1")) {
            registry.complete("exec-1", claim, lease,
                    new PipelineExecutionRegistry.Outcome(true, "ok", null, 12));
        }

        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(PipelineExecutionClaim.class));
    }

    @Test
    void terminalSnapshotWinsOverRetransportedCommand() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(mongo).insert(any(PipelineExecutionClaim.class));
        PipelineExecutionClaim current = document(PipelineExecutionClaim.State.SUCCESS, "exec-1");
        current.setCommandHash(hashOf("{\"a\":1}"));
        current.setSuccess(true);
        current.setLogOutput("termine");
        when(mongo.findById("exec-1", PipelineExecutionClaim.class)).thenReturn(current);

        PipelineExecutionRegistry.Claim replay =
                registry.claim("exec-1", "wf-1", "{\"a\":2}");

        assertEquals(PipelineExecutionRegistry.ClaimState.SUCCESS, replay.state());
        assertEquals("termine", replay.outcome().logOutput());
    }

    @Test
    void activeExecLogRejectsDifferentCommand() {
        doThrow(new DuplicateKeyException("duplicate"))
                .when(mongo).insert(any(PipelineExecutionClaim.class));
        PipelineExecutionClaim current = document(PipelineExecutionClaim.State.IN_PROGRESS, "exec-1");
        current.setCommandHash(hashOf("{\"a\":1}"));
        when(mongo.findById("exec-1", PipelineExecutionClaim.class)).thenReturn(current);

        assertThrows(PipelineExecutionRegistry.CommandPayloadMismatchException.class,
                () -> registry.claim("exec-1", "wf-1", "{\"a\":2}"));
    }

    private PipelineExecutionClaim document(PipelineExecutionClaim.State state, String execLogId) {
        PipelineExecutionClaim value = new PipelineExecutionClaim();
        value.setExecutionLogId(execLogId);
        value.setState(state);
        return value;
    }

    private String hashOf(String payload) {
        PipelineExecutionRegistry.Claim first = registry.claim("hash-probe", "wf", payload);
        return first.commandHash();
    }
}
