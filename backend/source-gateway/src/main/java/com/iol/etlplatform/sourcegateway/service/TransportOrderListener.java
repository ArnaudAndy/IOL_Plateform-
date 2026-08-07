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
            sendToDlq(record, poison);
            ack.acknowledge();
            return;
        }

        // Une redelivrance apres rebalance ne doit pas relancer une extraction
        // deja effectuee: l'execution serait extraite et chargee deux fois.
        if (!executionGuard.claim(order)) {
            log.info("Ordre deja traite ou en cours, ignore: execLogId={}", order.execLogId());
            ack.acknowledge();
            return;
        }

        try {
            executionGuard.transportAndPublish(order);
            // L'acquittement vient APRES la publication de la commande: c'est
            // lui qui rend l'operation durable du point de vue de Kafka.
            ack.acknowledge();
            log.info("Transport termine et commande publiee: execLogId={}", order.execLogId());
        } catch (Exception failure) {
            // Pas d'acquittement: l'ordre sera redelivre. Le claim est relache
            // pour qu'une reprise soit possible.
            executionGuard.release(order, failure);
            log.error("Echec du transport pour execLogId={}: {}",
                      order.execLogId(), failure.getMessage(), failure);
        }
    }

    private void sendToDlq(ConsumerRecord<String, String> record, Exception cause) {
        try {
            kafkaTemplate.send(dlqTopic, record.key(), record.value());
        } catch (Exception dlqFailure) {
            // La file d'erreurs est inaccessible. On acquitte tout de meme:
            // conserver un poison en tete de partition couterait plus cher que
            // de perdre un ordre deja invalide, qui reste tracable par le
            // journal d'execution reste en QUEUED puis relance par le watchdog.
            log.error("File d'erreurs inaccessible pour un ordre invalide ({}): {}",
                      cause.getMessage(), dlqFailure.getMessage());
        }
    }
}
