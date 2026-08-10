package com.iol.etlplatform.sourcegateway.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

/**
 * Consommation des ordres de transport.
 *
 * Deux choix portent l'atomicite :
 *
 *  - {@code AckMode.MANUAL_IMMEDIATE} : l'offset n'avance que lorsque le listener
 *    acquitte, c'est-a-dire apres la publication de la commande. Un crash en
 *    cours de transport laisse donc l'ordre redelivrable.
 *
 *  - {@code enable.auto.commit = false} : sans cela, Kafka commiterait l'offset
 *    periodiquement en tache de fond et un transport interrompu serait
 *    silencieusement considere comme traite.
 *
 * La concurrence est volontairement faible: un transport est une operation
 * longue et gourmande, pas une requete courte. La montee en charge se fait en
 * ajoutant des instances, pas des threads dans une meme instance.
 */
@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Value("${app.kafka.consumer.concurrency:2}")
    private int concurrency;

    @Value("${app.kafka.consumer.max-poll-records:1}")
    private int maxPollRecords;

    @Value("${app.kafka.consumer.max-poll-interval-ms:1800000}")
    private int maxPollIntervalMs;

    @Bean
    public ConsumerFactory<String, String> transportOrderConsumerFactory(KafkaProperties properties) {
        Map<String, Object> config = new HashMap<>(properties.buildConsumerProperties(null));
        config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        config.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Un seul ordre a la fois: un lot de N ordres partagerait un unique
        // acquittement, donc un echec sur le dernier rejouerait aussi les autres.
        config.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, maxPollRecords);
        // Un transport volumineux dure longtemps; sans cette marge, le broker
        // considererait le consumer mort et declencherait un rebalance en plein
        // milieu de l'extraction.
        config.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, maxPollIntervalMs);
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(config);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> transportOrderListenerContainerFactory(
            ConsumerFactory<String, String> transportOrderConsumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(transportOrderConsumerFactory);
        factory.setConcurrency(concurrency);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        return factory;
    }
}
