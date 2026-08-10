package com.iol.etlplatform.sourcegateway.service;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

import com.iol.etlplatform.sourcegateway.contract.TransportOrder;
import com.iol.etlplatform.sourcegateway.entity.TransportClaim;
import com.mongodb.client.result.UpdateResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifie le bail persistant et le fencing contre la double extraction. */
class MongoTransportExecutionGuardTest {

    private MongoTemplate mongo;
    private TransportPipeline pipeline;
    private TransportStatusPublisher statusPublisher;
    private MongoTransportExecutionGuard guard;

    private static final TransportOrder ORDER = new TransportOrder(
            TransportOrder.EVENT_TYPE, 2, "iol-default", "wf-1", "legacy-unversioned", "exec-1",
            "iol-default:wf-1", "2026-08-07T10:15:00Z", "user@example.org", 3);

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        pipeline = mock(TransportPipeline.class);
        statusPublisher = mock(TransportStatusPublisher.class);
        guard = new MongoTransportExecutionGuard(mongo, pipeline, statusPublisher);
        ReflectionTestUtils.setField(guard, "claimLeaseSeconds", 1800L);
        ReflectionTestUtils.setField(guard, "claimHeartbeatSeconds", 30L);
    }

    @AfterEach
    void tearDown() {
        guard.stopLeaseScheduler();
    }

    @Test
    void unePremiereReservationEstAccordeeAvecUnJeton() {
        TransportExecutionGuard.Claim claim = guard.claim(ORDER);

        assertEquals(TransportExecutionGuard.ClaimState.ACQUIRED, claim.state());
        assertEquals(1, claim.attempt());
        assertTrue(claim.fencingToken() != null && !claim.fencingToken().isBlank());
        verify(mongo).insert(any(TransportClaim.class));
    }

    @Test
    void uneReservationConcurrenteEstSignaleeBusyEtNonTerminee() {
        doThrow(new DuplicateKeyException("execLogId deja reserve"))
                .when(mongo).insert(any(TransportClaim.class));
        when(mongo.findById("exec-1", TransportClaim.class)).thenReturn(
                TransportClaim.builder()
                        .executionLogId("exec-1")
                        .status(TransportClaim.Status.IN_PROGRESS)
                        .attempts(1)
                        .build());

        TransportExecutionGuard.Claim claim = guard.claim(ORDER);

        assertEquals(TransportExecutionGuard.ClaimState.BUSY, claim.state());
    }

    @Test
    void uneExecutionAuBailExpireEstRepriseAvecUnNouveauJeton() {
        doThrow(new DuplicateKeyException("execLogId deja reserve"))
                .when(mongo).insert(any(TransportClaim.class));
        when(mongo.findAndModify(
                any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                eq(TransportClaim.class)))
                .thenReturn(TransportClaim.builder()
                        .executionLogId("exec-1")
                        .status(TransportClaim.Status.IN_PROGRESS)
                        .attempts(2)
                        .build());

        TransportExecutionGuard.Claim claim = guard.claim(ORDER);

        assertEquals(TransportExecutionGuard.ClaimState.ACQUIRED, claim.state());
        assertEquals(2, claim.attempt());
        assertTrue(claim.fencingToken() != null && !claim.fencingToken().isBlank());
    }

    @Test
    void unEtatTerminalEstDistingueDunTravailEnCours() {
        doThrow(new DuplicateKeyException("execLogId deja reserve"))
                .when(mongo).insert(any(TransportClaim.class));
        when(mongo.findById("exec-1", TransportClaim.class)).thenReturn(
                TransportClaim.builder()
                        .executionLogId("exec-1")
                        .status(TransportClaim.Status.COMPLETED)
                        .attempts(2)
                        .build());

        assertEquals(TransportExecutionGuard.ClaimState.COMPLETED, guard.claim(ORDER).state());
    }

    @Test
    void laReservationPasseEnTerminalApresPublication() throws Exception {
        TransportExecutionGuard.Claim claim = TransportExecutionGuard.Claim.acquired("token-1", 1);
        when(mongo.exists(any(Query.class), eq(TransportClaim.class))).thenReturn(true);
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(TransportClaim.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        guard.transportAndPublish(ORDER, claim);

        verify(pipeline).run(eq(ORDER), any(Runnable.class));
        verify(statusPublisher).started(ORDER, 1);
        verify(statusPublisher).waitingForEngine(ORDER, 1);
    }

    @Test
    void unePerteDeBailAvantPublicationBloqueLaCommande() throws Exception {
        TransportExecutionGuard.Claim claim = TransportExecutionGuard.Claim.acquired("ancien-token", 1);
        when(mongo.exists(any(Query.class), eq(TransportClaim.class))).thenReturn(false);

        doThrow(new AssertionError("le pipeline doit appeler le controle de fencing"))
                .when(pipeline).run(eq(ORDER), any(Runnable.class));

        // Remplace le mock ci-dessus par un pipeline qui execute vraiment le controle.
        TransportPipeline checkingPipeline = (order, ownershipCheck) -> ownershipCheck.run();
        MongoTransportExecutionGuard checkingGuard =
                new MongoTransportExecutionGuard(mongo, checkingPipeline, statusPublisher);
        ReflectionTestUtils.setField(checkingGuard, "claimLeaseSeconds", 1800L);
        ReflectionTestUtils.setField(checkingGuard, "claimHeartbeatSeconds", 30L);
        try {
            assertThrows(MongoTransportExecutionGuard.LeaseLostException.class,
                    () -> checkingGuard.transportAndPublish(ORDER, claim));
        } finally {
            checkingGuard.stopLeaseScheduler();
        }
    }

    @Test
    void unEchecDuPipelineNeMarquePasLExecutionTerminee() throws Exception {
        TransportExecutionGuard.Claim claim = TransportExecutionGuard.Claim.acquired("token-1", 1);
        doThrow(new IllegalStateException("source injoignable"))
                .when(pipeline).run(eq(ORDER), any(Runnable.class));

        assertThrows(IllegalStateException.class, () -> guard.transportAndPublish(ORDER, claim));

        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(TransportClaim.class));
    }

    @Test
    void leRelachementUtiliseLeClaimAcquis() {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(TransportClaim.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));
        TransportExecutionGuard.Claim claim = TransportExecutionGuard.Claim.acquired("token-1", 1);

        guard.release(ORDER, claim, new IllegalStateException("kafka indisponible"));

        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(TransportClaim.class));
        verify(statusPublisher).retryScheduled(eq(ORDER), eq(1), any(Throwable.class));
    }

    @Test
    void leBailExpireEstDetecteCorrectement() {
        Instant now = Instant.now();
        TransportClaim fresh = TransportClaim.builder().leaseExpiresAt(now.plusSeconds(60)).build();
        TransportClaim stale = TransportClaim.builder().leaseExpiresAt(now.minusSeconds(60)).build();

        assertFalse(fresh.leaseExpired(now));
        assertTrue(stale.leaseExpired(now));
    }
}
