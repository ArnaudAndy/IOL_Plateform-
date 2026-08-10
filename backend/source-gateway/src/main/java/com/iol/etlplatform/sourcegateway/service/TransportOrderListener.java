package com.iol.etlplatform.sourcegateway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.sourcegateway.contract.InvalidTransportOrderException;
import com.iol.etlplatform.sourcegateway.contract.TransportOrder;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Consomme les ordres de transport et garantit l'invariant central de la
 * plateforme.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  INVARIANT
 * ═══════════════════════════════════════════════════════════════════════════
 *  Le pipeline-consumer ne doit JAMAIS voir une commande dont les donnees ne
 *  sont pas integralement transportees et dont les identifiants source ne sont
 *  pas purges.
 *
 *  Avant l'extraction, cet invariant tenait parce que tout se passait dans un
 *  seul appel. Ici il tient parce qu'un seul service detient toujours la
 *  sequence complete — transport, purge, publication — et parce que l'offset
 *  n'est acquitte qu'apres.
 *
 * ═══════════════════════════════════════════════════════════════════════════
 *  DISCIPLINE D'ACQUITTEMENT
 * ═══════════════════════════════════════════════════════════════════════════
 *  L'acquittement arrive APRES la publication de la commande, jamais avant.
 *  Consequences voulues :
 *
 *   - crash pendant le transport      → offset non commite → redelivrance
 *   - publication de commande echouee → offset non commite → rejeu
 *   - ordre invalide (jamais valide)  → file d'erreurs + acquittement, pour que
 *                                       la partition avance malgre le poison
 *
 *  La redelivrance ne doit pas relancer une extraction deja faite: le claim
 *  idempotent par execLogId l'en empeche.
 */
@Service
public class TransportOrderListener {

    private static final Logger log = LoggerFactory.getLogger(TransportOrderListener.class);

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransportExecutionGuard executionGuard;

    @Value("${app.kafka.topics.transport-dlq:iol.transport.requests.dlq}")
    private String dlqTopic;

    @Value("${app.kafka.consumer.max-attempts:5}")
    private int maxAttempts;

    @Value("${app.kafka.consumer.retry-backoff-seconds:10}")
    private long retryBackoffSeconds;

    public TransportOrderListener(
            ObjectMapper objectMapper,
            KafkaTemplate<String, String> kafkaTemplate,
            TransportExecutionGuard executionGuard) {
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.executionGuard = executionGuard;
    }

    @KafkaListener(
            topics = "${app.kafka.topics.transport-requests:iol.transport.requests}",
            groupId = "${app.kafka.consumer.group:source-gateway-group}",
            containerFactory = "transportOrderListenerContainerFactory")
    public void onTransportOrder(ConsumerRecord<String, String> record, Acknowledgment ack) {
        TransportOrder order;
        try {
            order = objectMapper.readValue(record.value(), TransportOrder.class);
            order.validate();
        } catch (Exception poison) {
            // Un ordre illisible ou d'une version inconnue ne redeviendra jamais
            // valide: le rejouer bloquerait la partition indefiniment.
            log.error("Ordre de transport invalide, envoi en file d'erreurs: {}", poison.getMessage());
            try {
                sendToDlq(record);
                ack.acknowledge();
            } catch (Exception dlqFailure) {
                log.error("File d'erreurs inaccessible; poison non acquitte: {}",
                        dlqFailure.getMessage(), dlqFailure);
                retryLater(ack);
            }
            return;
        }

        TransportExecutionGuard.Claim claim;
        try {
            claim = executionGuard.claim(order);
        } catch (Exception claimFailure) {
            log.error("Reservation indisponible pour execLogId={}: {}",
                    order.execLogId(), claimFailure.getMessage(), claimFailure);
            retryLater(ack);
            return;
        }

        if (claim.state() == TransportExecutionGuard.ClaimState.BUSY) {
            // Ne surtout pas acquitter: l'autre instance peut disparaitre avant
            // d'avoir publie la commande. La redelivrance reconsultera Mongo.
            log.info("Ordre encore detenu par une autre instance: execLogId={}", order.execLogId());
            retryLater(ack);
            return;
        }
        if (claim.state() == TransportExecutionGuard.ClaimState.COMPLETED
                || claim.state() == TransportExecutionGuard.ClaimState.FAILED) {
            log.info("Ordre dans un etat terminal {}, acquitte: execLogId={}",
                    claim.state(), order.execLogId());
            ack.acknowledge();
            return;
        }

        try {
            executionGuard.transportAndPublish(order, claim);
            // L'acquittement vient APRES la publication de la commande: c'est
            // lui qui rend l'operation durable du point de vue de Kafka.
            ack.acknowledge();
            log.info("Transport termine et commande publiee: execLogId={}", order.execLogId());
        } catch (Exception failure) {
            if (claim.attempt() >= Math.max(1, maxAttempts)) {
                handleExhaustedRetries(record, ack, order, claim, failure);
                return;
            }

            safeRelease(order, claim, failure);
            retryLater(ack);
            log.error("Echec du transport pour execLogId={} tentative={}: {}",
                    order.execLogId(), claim.attempt(), failure.getMessage(), failure);
        }
    }

    private void handleExhaustedRetries(
            ConsumerRecord<String, String> record,
            Acknowledgment ack,
            TransportOrder order,
            TransportExecutionGuard.Claim claim,
            Exception failure) {
        try {
            // La DLQ doit etre confirmee AVANT l'etat terminal et l'offset.
            // Ainsi aucune panne Kafka ne peut faire disparaitre le diagnostic.
            sendToDlq(record);
            executionGuard.failPermanently(order, claim, failure);
            ack.acknowledge();
            log.error("Transport abandonne apres {} tentative(s): execLogId={}",
                    claim.attempt(), order.execLogId(), failure);
        } catch (Exception terminalFailure) {
            safeRelease(order, claim, terminalFailure);
            retryLater(ack);
            log.error("Echec de finalisation/DLQ pour execLogId={}: {}",
                    order.execLogId(), terminalFailure.getMessage(), terminalFailure);
        }
    }

    private void sendToDlq(ConsumerRecord<String, String> record) throws Exception {
        kafkaTemplate.send(dlqTopic, record.key(), record.value())
                .get(30, TimeUnit.SECONDS);
    }

    private void safeRelease(
            TransportOrder order,
            TransportExecutionGuard.Claim claim,
            Throwable cause) {
        try {
            executionGuard.release(order, claim, cause);
        } catch (Exception releaseFailure) {
            log.error("Reservation non relachee pour execLogId={}: {}",
                    order.execLogId(), releaseFailure.getMessage(), releaseFailure);
        }
    }

    private void retryLater(Acknowledgment ack) {
        ack.nack(Duration.ofSeconds(Math.max(1, retryBackoffSeconds)));
    }
}
