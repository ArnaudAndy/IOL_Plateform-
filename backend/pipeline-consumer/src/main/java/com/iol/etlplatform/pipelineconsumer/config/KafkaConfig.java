package com.iol.etlplatform.pipelineconsumer.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration Kafka pour pipeline-consumer.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  GESTION DE LA CHARGE ET DES PRIORITÉS
 * ═══════════════════════════════════════════════════════════════════
 *
 * Le pipeline-consumer écoute 3 topics de priorité différente.
 * KafkaEventListenerService les consomme dans l'ordre : HIGH → NORMAL → LOW.
 *
 * Quand plusieurs utilisateurs soumettent simultanément :
 *   → Toutes leurs demandes s'empilent dans le bon topic
 *   → pipeline-consumer traite 1 pipeline à la fois (max-poll-records=1)
 *   → Les priorités HIGH passent avant les NORMAL qui passent avant LOW
 *   → Hop n'est jamais surchargé
 *
 * Pour scaler horizontalement (plus de débit) :
 *   → Ajouter des instances de pipeline-consumer (chacune traite 1 pipeline)
 *   → Augmenter le nombre de partitions des topics Kafka
 *
 * Points clés :
 *   AckMode.MANUAL_IMMEDIATE : ack seulement après fin d'exécution Hop
 *   max-poll-records=1   : 1 pipeline à la fois par instance
 *   max-poll-interval    : dimensionne au temps maximal d'un pipeline
 */
@Configuration
public class KafkaConfig {

    @Value("${app.kafka.consumer.group:pipeline-consumer-group}")
    private String groupId;

    @Value("${app.kafka.consumer.concurrency:3}")
    private int concurrency;

    @Bean
    public ConsumerFactory<String, String> consumerFactory(KafkaProperties kafkaProperties) {
        // Partir des proprietes Spring est indispensable en production : ce
        // bloc transporte aussi security.protocol et les keystores mTLS. Une
        // map reconstruite a la main se connecterait par erreur sans SSL.
        Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
        props.put(ConsumerConfig.GROUP_ID_CONFIG,                     groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,            "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,           false);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,       StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,     StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(Math.max(1, concurrency));
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
