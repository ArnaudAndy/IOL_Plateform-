package com.iol.etlplatform.config.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.HttpURLConnection;
import java.net.URI;

@Component
public class ObjectStorageHealthIndicator implements HealthIndicator {

    private final boolean enabled;
    private final String endpoint;

    public ObjectStorageHealthIndicator(
            @Value("${app.object-storage.enabled:false}") boolean enabled,
            @Value("${app.object-storage.endpoint:http://localhost:9000}") String endpoint) {
        this.enabled = enabled;
        this.endpoint = endpoint;
    }

    @Override
    public Health health() {
        if (!enabled) {
            return Health.up().withDetail("status", "disabled").build();
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(
                    endpoint.replaceAll("/+$", "") + "/minio/health/ready").toURL().openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestMethod("GET");
            int status = connection.getResponseCode();
            return status >= 200 && status < 300
                    ? Health.up().withDetail("httpStatus", status).build()
                    : Health.down().withDetail("httpStatus", status).build();
        } catch (Exception exception) {
            return Health.down(exception).build();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
