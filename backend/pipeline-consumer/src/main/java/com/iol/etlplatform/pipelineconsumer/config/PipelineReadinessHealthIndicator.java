package com.iol.etlplatform.pipelineconsumer.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class PipelineReadinessHealthIndicator implements HealthIndicator {

    private final KafkaListenerEndpointRegistry listenerRegistry;
    private final String kafkaBootstrapServers;
    private final String lockMode;
    private final String lockJdbcUrl;
    private final String lockUsername;
    private final String lockPassword;
    private final boolean objectStorageEnabled;
    private final String objectStorageEndpoint;

    public PipelineReadinessHealthIndicator(
            KafkaListenerEndpointRegistry listenerRegistry,
            @Value("${spring.kafka.bootstrap-servers}") String kafkaBootstrapServers,
            @Value("${app.execution-lock.mode:POSTGRES}") String lockMode,
            @Value("${app.execution-lock.jdbc-url}") String lockJdbcUrl,
            @Value("${app.execution-lock.username}") String lockUsername,
            @Value("${app.execution-lock.password}") String lockPassword,
            @Value("${app.object-storage.enabled:false}") boolean objectStorageEnabled,
            @Value("${app.object-storage.endpoint:http://localhost:9000}") String objectStorageEndpoint) {
        this.listenerRegistry = listenerRegistry;
        this.kafkaBootstrapServers = kafkaBootstrapServers;
        this.lockMode = lockMode;
        this.lockJdbcUrl = lockJdbcUrl;
        this.lockUsername = lockUsername;
        this.lockPassword = lockPassword;
        this.objectStorageEnabled = objectStorageEnabled;
        this.objectStorageEndpoint = objectStorageEndpoint;
    }

    @Override
    public Health health() {
        try {
            assertListenersRunning();
            assertKafkaReady();
            assertExecutionLockReady();
            assertObjectStorageReady();
            return Health.up()
                    .withDetail("listeners", listenerRegistry.getListenerContainers().size())
                    .build();
        } catch (Exception exception) {
            return Health.down(exception).build();
        }
    }

    private void assertListenersRunning() {
        if (listenerRegistry.getListenerContainers().isEmpty()
                || listenerRegistry.getListenerContainers().stream().anyMatch(container -> !container.isRunning())) {
            throw new IllegalStateException("Les listeners Kafka ne sont pas tous demarres.");
        }
    }

    private void assertKafkaReady() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers,
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 3000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 3000))) {
            admin.describeCluster().clusterId()
                    .get(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private void assertExecutionLockReady() throws Exception {
        if (!"POSTGRES".equalsIgnoreCase(lockMode)) return;
        try (Connection connection = DriverManager.getConnection(lockJdbcUrl, lockUsername, lockPassword)) {
            if (!connection.isValid(3)) {
                throw new IllegalStateException("PostgreSQL de verrouillage indisponible.");
            }
        }
    }

    private void assertObjectStorageReady() throws Exception {
        if (!objectStorageEnabled) return;
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                objectStorageEndpoint.replaceAll("/+$", "") + "/minio/health/ready").toURL().openConnection();
        try {
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("RustFS indisponible: HTTP " + status);
            }
        } finally {
            connection.disconnect();
        }
    }
}
