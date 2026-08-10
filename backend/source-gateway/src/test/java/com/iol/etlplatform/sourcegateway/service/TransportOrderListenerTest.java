package com.iol.etlplatform.sourcegateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.sourcegateway.contract.TransportOrder;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Verrouille la discipline d'acquittement, qui porte l'invariant central:
 * le pipeline-consumer ne doit jamais voir une commande dont les donnees ne
 * sont pas integralement transportees.
 */
class TransportOrderListenerTest {

    private TransportOrderListener listener;
    private RecordingGuard guard;
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private Acknowledgment ack;
    private AtomicInteger acknowledged;
    private AtomicInteger nacked;

    @BeforeEach
    void setUp() {
        guard = new RecordingGuard();
        listener = new TransportOrderListener(new ObjectMapper(), kafka, guard);
        ReflectionTestUtils.setField(listener, "dlqTopic", "iol.transport.requests.dlq");
        ReflectionTestUtils.setField(listener, "maxAttempts", 5);
        ReflectionTestUtils.setField(listener, "retryBackoffSeconds", 1L);
        org.mockito.Mockito.when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        acknowledged = new AtomicInteger();
        nacked = new AtomicInteger();
        ack = new Acknowledgment() {
            @Override
            public void acknowledge() {
                acknowledged.incrementAndGet();
            }

            @Override
            public void nack(Duration sleep) {
                nacked.incrementAndGet();
            }
        };
    }

    private ConsumerRecord<String, String> record(String payload) {
        return new ConsumerRecord<>("iol.transport.requests", 0, 0L, "iol-default:wf-1", payload);
    }

    private static String validOrder() {
        return """
            {"eventType":"TRANSPORT_REQUESTED","schemaVersion":2,
             "organizationId":"iol-default","workflowId":"wf-1",
             "workflowRevision":"legacy-unversioned",
             "execLogId":"exec-1","executionKey":"iol-default:wf-1",
                "requestedAt":"2026-08-07T10:15:00Z","priority":3}
               """;
    }

    @Test
    void acquitteSeulementApresLaPublicationDeLaCommande() {
        listener.onTransportOrder(record(validOrder()), ack);

        assertTrue(guard.transported.get(), "le transport doit avoir eu lieu");
        assertEquals(1, acknowledged.get(), "l'ordre doit etre acquitte apres publication");
        assertTrue(guard.transportPrecededAck.get(),
                "l'acquittement ne doit jamais preceder la publication de la commande");
    }

    @Test
    void unEchecDeTransportNAcquittePasEtRelacheLaReservation() {
        guard.failWith = new IllegalStateException("source injoignable");

        listener.onTransportOrder(record(validOrder()), ack);

        assertEquals(0, acknowledged.get(),
                "sans acquittement, Kafka redelivrera l'ordre");
        assertEquals(1, nacked.get(), "la reprise doit etre explicitement demandee");
        assertTrue(guard.released.get(), "la reservation doit etre relachee pour permettre la reprise");
    }

    @Test
    void unOrdreIllisiblePartEnFileDErreursEtLibereLaPartition() {
        listener.onTransportOrder(record("{ ceci n'est pas du JSON"), ack);

        verify(kafka).send(anyString(), anyString(), anyString());
        assertEquals(1, acknowledged.get(),
                "un poison doit etre acquitte, sinon il bloque la partition indefiniment");
        assertFalse(guard.transported.get(), "aucun transport ne doit demarrer sur un ordre invalide");
    }

    @Test
    void uneVersionDeContratInconnueEstRefusee() {
        String futureVersion = validOrder().replace("\"schemaVersion\":2", "\"schemaVersion\":99");

        listener.onTransportOrder(record(futureVersion), ack);

        verify(kafka).send(anyString(), anyString(), anyString());
        assertFalse(guard.transported.get(),
                "une version inconnue peut decrire des champs de securite: refuser plutot que deviner");
    }

    @Test
    void uneRedelivranceDejaTraiteeNeRelancePasLExtraction() {
        guard.claim = TransportExecutionGuard.Claim.of(
                TransportExecutionGuard.ClaimState.COMPLETED, 1);

        listener.onTransportOrder(record(validOrder()), ack);

        assertFalse(guard.transported.get(), "pas de double extraction apres un rebalance");
        assertEquals(1, acknowledged.get(), "l'ordre deja traite doit etre acquitte");
    }

    @Test
    void uneExecutionEncoreDetenueNestJamaisAcquittee() {
        guard.claim = TransportExecutionGuard.Claim.of(
                TransportExecutionGuard.ClaimState.BUSY, 1);

        listener.onTransportOrder(record(validOrder()), ack);

        assertEquals(0, acknowledged.get());
        assertEquals(1, nacked.get(), "le message doit revenir si le detenteur disparait");
        assertFalse(guard.transported.get());
    }

    @Test
    void apresLeMaximumDeTentativesLaDlqEtLEchecSontDurablesAvantAck() {
        guard.claim = TransportExecutionGuard.Claim.acquired("token-5", 5);
        guard.failWith = new IllegalStateException("source toujours injoignable");

        listener.onTransportOrder(record(validOrder()), ack);

        verify(kafka).send(anyString(), anyString(), anyString());
        assertTrue(guard.permanentlyFailed.get());
        assertEquals(1, acknowledged.get());
        assertEquals(0, nacked.get());
    }

    /** Garde instrumente: enregistre l'ordre reel des effets. */
    private static final class RecordingGuard implements TransportExecutionGuard {
        final AtomicBooleanBox transported = new AtomicBooleanBox();
        final AtomicBooleanBox released = new AtomicBooleanBox();
        final AtomicBooleanBox transportPrecededAck = new AtomicBooleanBox();
        final AtomicReference<TransportOrder> seen = new AtomicReference<>();
        TransportExecutionGuard.Claim claim = TransportExecutionGuard.Claim.acquired("token-1", 1);
        RuntimeException failWith;
        final AtomicBooleanBox permanentlyFailed = new AtomicBooleanBox();

        @Override
        public TransportExecutionGuard.Claim claim(TransportOrder order) {
            seen.set(order);
            return claim;
        }

        @Override
        public void transportAndPublish(TransportOrder order, TransportExecutionGuard.Claim claim) {
            if (failWith != null) throw failWith;
            transported.set(true);
            transportPrecededAck.set(true);
        }

        @Override
        public void release(
                TransportOrder order,
                TransportExecutionGuard.Claim claim,
                Throwable cause) {
            released.set(true);
        }

        @Override
        public void failPermanently(
                TransportOrder order,
                TransportExecutionGuard.Claim claim,
                Throwable cause) {
            permanentlyFailed.set(true);
        }
    }

    private static final class AtomicBooleanBox {
        private volatile boolean value;
        void set(boolean next) { this.value = next; }
        boolean get() { return value; }
    }
}
