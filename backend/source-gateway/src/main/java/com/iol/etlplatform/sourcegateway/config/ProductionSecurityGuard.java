package com.iol.etlplatform.sourcegateway.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import com.iol.etlplatform.sourcegateway.credential.CredentialCipher;

/** Refuse un gateway `prod` sans chiffrement, TLS mutuel ou stockage provisionne. */
@Component
public class ProductionSecurityGuard implements SmartInitializingSingleton {
    private final Environment environment;
    private final CredentialCipher credentialCipher;

    public ProductionSecurityGuard(Environment environment, CredentialCipher credentialCipher) {
        this.environment = environment;
        this.credentialCipher = credentialCipher;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;

        List<String> failures = new ArrayList<>();
        if (!"VAULT_TRANSIT".equals(credentialCipher.provider())) {
            failures.add("app.credentials.provider doit valoir VAULT_TRANSIT");
        }
        requireEquals(failures, "app.credentials.environment", "production");
        requireEquals(failures, "app.credentials.allow-legacy-plaintext", "false");
        requireStartsWith(failures, "app.credentials.vault.address", "https://");
        requireReadable(failures, "app.credentials.vault.role-id-file");
        requireReadable(failures, "app.credentials.vault.secret-id-file");

        requireContains(failures, "spring.data.mongodb.uri", "replicaSet=");
        requireContains(failures, "spring.data.mongodb.uri", "tls=true");
        requireContains(failures, "spring.kafka.properties.security.protocol", "SSL");

        requireEquals(failures, "server.ssl.enabled", "true");
        requireEquals(failures, "server.ssl.client-auth", "need");
        requireEquals(failures, "app.object-storage.enabled", "true");
        requireEquals(failures, "app.object-storage.create-bucket-if-missing", "false");
        requireStartsWith(failures, "app.object-storage.endpoint", "https://");
        requireNonBlank(failures, "app.object-storage.access-key");
        requireNonBlank(failures, "app.object-storage.secret-key");

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Configuration de production du source-gateway refusee:\n - "
                            + String.join("\n - ", failures));
        }
    }

    private void requireEquals(List<String> failures, String key, String expected) {
        if (!expected.equalsIgnoreCase(environment.getProperty(key, "").trim())) {
            failures.add(key + " doit valoir " + expected);
        }
    }

    private void requireContains(List<String> failures, String key, String expected) {
        String value = environment.getProperty(key, "");
        if (!value.toLowerCase().contains(expected.toLowerCase())) {
            failures.add(key + " doit contenir " + expected);
        }
    }

    private void requireStartsWith(List<String> failures, String key, String prefix) {
        if (!environment.getProperty(key, "").trim().toLowerCase().startsWith(prefix.toLowerCase())) {
            failures.add(key + " doit commencer par " + prefix);
        }
    }

    private void requireNonBlank(List<String> failures, String key) {
        if (environment.getProperty(key, "").isBlank()) failures.add(key + " est obligatoire");
    }

    private void requireReadable(List<String> failures, String key) {
        String value = environment.getProperty(key, "").trim();
        if (value.isBlank() || !Files.isReadable(Path.of(value))) {
            failures.add(key + " doit designer un secret lisible");
        }
    }
}
