package com.iol.etlplatform.sourcegateway.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

import com.iol.etlplatform.sourcegateway.credential.CredentialCipher;
import com.iol.etlplatform.sourcegateway.service.ObjectStorageService;

/** Readiness des dependances qui ne sont pas couvertes par la probe MongoDB. */
@Component("sourceGatewayDependencies")
public class SourceGatewayDependenciesHealthIndicator implements HealthIndicator {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final KafkaProperties kafkaProperties;
    private final CredentialCipher credentialCipher;
    private final ObjectStorageService objectStorageService;

    public SourceGatewayDependenciesHealthIndicator(
            KafkaListenerEndpointRegistry listenerRegistry,
            KafkaProperties kafkaProperties,
            CredentialCipher credentialCipher,
            ObjectStorageService objectStorageService) {
        this.listenerRegistry = listenerRegistry;
        this.kafkaProperties = kafkaProperties;
        this.credentialCipher = credentialCipher;
        this.objectStorageService = objectStorageService;
    }

    @Override
    public Health health() {
        try {
            assertListenersRunning();
            assertKafkaReady();
            credentialCipher.assertReady();
            if (objectStorageService.isEnabled()) objectStorageService.assertReady();
            return Health.up()
                    .withDetail("listeners", listenerRegistry.getListenerContainers().size())
                    .withDetail("credentialProvider", credentialCipher.provider())
                    .build();
        } catch (Exception error) {
            return Health.down(error).build();
        }
    }

    private void assertListenersRunning() {
        if (listenerRegistry.getListenerContainers().isEmpty()
                || listenerRegistry.getListenerContainers().stream()
                        .anyMatch(container -> !container.isRunning())) {
            throw new IllegalStateException("Le listener des ordres de transport n'est pas demarre.");
        }
    }

    private void assertKafkaReady() throws Exception {
        Map<String, Object> properties = new HashMap<>(kafkaProperties.buildAdminProperties(null));
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, Math.toIntExact(TIMEOUT.toMillis()));
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, Math.toIntExact(TIMEOUT.toMillis()));
        try (AdminClient admin = AdminClient.create(properties)) {
            admin.describeCluster().clusterId().get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }
}
