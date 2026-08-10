package com.iol.etlplatform.pipelineconsumer.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Refuse une execution distribuee `prod` avec une dependance non chiffree. */
@Component
public class ProductionSecurityGuard implements SmartInitializingSingleton {
    private final Environment environment;

    public ProductionSecurityGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;

        List<String> failures = new ArrayList<>();
        requireContains(failures, "spring.data.mongodb.uri", "replicaSet=");
        requireContains(failures, "spring.data.mongodb.uri", "tls=true");
        requireContains(failures, "spring.kafka.properties.security.protocol", "SSL");
        requireEquals(failures, "app.execution-lock.mode", "POSTGRES");
        requireContains(failures, "app.execution-lock.jdbc-url", "sslmode=verify-full");
        requireEquals(failures, "app.object-storage.enabled", "true");
        requireStartsWith(failures, "app.object-storage.endpoint", "https://");
        requireStartsWith(failures, "app.workflow.service.url", "https://");
        requireStartsWith(failures, "app.security.oauth2.token-uri", "https://");
        requireReadable(failures, "app.security.oauth2.client-secret-file");
        requireBlank(failures, "app.security.internal-secret");
        requireEquals(failures, "app.spark.distributed.ready", "true");
        requireReadable(failures, "SPARK_AUTH_SECRET_FILE");
        requireReadable(failures, "SPARK_TLS_KEYSTORE_PATH");

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Configuration de production du pipeline-consumer refusee:\n - "
                            + String.join("\n - ", failures));
        }
    }

    private void requireEquals(List<String> failures, String key, String expected) {
        if (!expected.equalsIgnoreCase(environment.getProperty(key, "").trim())) {
            failures.add(key + " doit valoir " + expected);
        }
    }

    private void requireContains(List<String> failures, String key, String expected) {
        if (!environment.getProperty(key, "").toLowerCase().contains(expected.toLowerCase())) {
            failures.add(key + " doit contenir " + expected);
        }
    }

    private void requireStartsWith(List<String> failures, String key, String prefix) {
        if (!environment.getProperty(key, "").trim().toLowerCase().startsWith(prefix.toLowerCase())) {
            failures.add(key + " doit commencer par " + prefix);
        }
    }

    private void requireReadable(List<String> failures, String key) {
        String value = environment.getProperty(key, "").trim();
        if (value.isBlank() || !Files.isReadable(Path.of(value))) {
            failures.add(key + " doit designer un fichier lisible");
        }
    }

    private void requireBlank(List<String> failures, String key) {
        if (!environment.getProperty(key, "").isBlank()) {
            failures.add(key + " doit etre vide en production");
        }
    }
}
