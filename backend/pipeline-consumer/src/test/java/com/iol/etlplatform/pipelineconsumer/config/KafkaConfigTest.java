package com.iol.etlplatform.pipelineconsumer.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.test.util.ReflectionTestUtils;

class KafkaConfigTest {

    @Test
    void conserveLesProprietesMtlsFourniesParSpring() {
        KafkaProperties properties = new KafkaProperties();
        properties.setBootstrapServers(List.of("kafka:9092"));
        properties.getProperties().put("security.protocol", "SSL");
        properties.getProperties().put("ssl.truststore.location", "/run/tls/truststore.p12");

        KafkaConfig config = new KafkaConfig();
        ReflectionTestUtils.setField(config, "groupId", "iol-pipeline-consumer");

        DefaultKafkaConsumerFactory<?, ?> factory = (DefaultKafkaConsumerFactory<?, ?>)
                config.consumerFactory(properties);
        Map<String, Object> actual = factory.getConfigurationProperties();

        assertThat(actual)
                .containsEntry("security.protocol", "SSL")
                .containsEntry("ssl.truststore.location", "/run/tls/truststore.p12")
                .containsEntry(ConsumerConfig.GROUP_ID_CONFIG, "iol-pipeline-consumer");
    }
}
