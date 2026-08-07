package com.iol.etlplatform.sourcegateway.service;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.util.ReflectionTestUtils;

import com.iol.etlplatform.sourcegateway.contract.TransportOrder;
import com.iol.etlplatform.sourcegateway.entity.TransportClaim;
import com.mongodb.client.result.UpdateResult;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifie la protection contre la double extraction.
 *
 * Sans elle, un rebalance Kafka relancerait une extraction deja effectuee, avec
 * un double chargement dans la destination du client.
 */
class MongoTransportExecutionGuardTest {

    private MongoTemplate mongo;
    private TransportPipeline pipeline;
    private MongoTransportExecutionGuard guard;

    private static final TransportOrder ORDER = new TransportOrder(
            TransportOrder.EVENT_TYPE, 1, "iol-default", "wf-1", "exec-1",
            "iol-default:wf-1", "2026-08-07T10:15:00Z", "user@example.org", 3);

    @BeforeEach
    void setUp() {
        mongo = mock(MongoTemplate.class);
        pipeline = mock(TransportPipeline.class);
        guard = new MongoTransportExecutionGuard(mongo, pipeline);
        ReflectionTestUtils.setField(guard, "claimLeaseSeconds", 1800L);
    }

    @Test
    void unePremiereReservationEstAccordee() {
        assertTrue(guard.claim(ORDER));
        verify(mongo).insert(any(TransportClaim.class));
    }

    @Test
    void uneReservationConcurrenteEstRefusee() {
        doThrow(new DuplicateKeyException("execLogId deja reserve"))
                .when(mongo).insert(any(TransportClaim.class));
        // Aucun document au bail expire: rien a reprendre.
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(TransportClaim.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        assertFalse(guard.claim(ORDER),
                "une seconde instance ne doit pas relancer une extraction en cours");
    }

    @Test
    void uneExecutionAuBailExpireEstReprise() {
        doThrow(new DuplicateKeyException("execLogId deja reserve"))
                .when(mongo).insert(any(TransportClaim.class));
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(TransportClaim.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        assertTrue(guard.claim(ORDER),
                "un detenteur disparu ne doit pas bloquer l'execution indefiniment");
    }

    @Test
    void laReservationPasseEnTerminalApresPublication() throws Exception {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(TransportClaim.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        guard.transportAndPublish(ORDER);

        verify(pipeline).run(ORDER);
        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(TransportClaim.class));
    }

    @Test
    void unEchecDuPipelineNeMarquePasLExecutionTerminee() throws Exception {
        doThrow(new IllegalStateException("source injoignable")).when(pipeline).run(ORDER);

        try {
            guard.transportAndPublish(ORDER);
        } catch (Exception expected) {
            // attendu: le listener n'acquittera pas
        }

        // Marquer COMPLETED ici empecherait toute reprise de l'execution.
        verify(mongo, never()).updateFirst(any(Query.class), any(Update.class), eq(TransportClaim.class));
    }

    @Test
    void leRelachementRendLExecutionReprenable() {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(TransportClaim.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null));

        guard.release(ORDER, new IllegalStateException("kafka indisponible"));

        verify(mongo).updateFirst(any(Query.class), any(Update.class), eq(TransportClaim.class));
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
