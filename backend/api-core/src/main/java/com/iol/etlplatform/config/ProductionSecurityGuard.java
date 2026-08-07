package com.iol.etlplatform.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import com.iol.etlplatform.service.credential.CredentialCipher;

import lombok.RequiredArgsConstructor;

/** Refuse un démarrage `prod` qui réintroduirait du plaintext ou du trafic non TLS. */
@Component
@RequiredArgsConstructor
public class ProductionSecurityGuard implements SmartInitializingSingleton {
    private final Environment environment;
    private final CredentialCipher credentialCipher;

    @Override
    public void afterSingletonsInstantiated() {
        if (!environment.acceptsProfiles(Profiles.of("prod"))) return;

        List<String> failures = new ArrayList<>();
        requireEquals(failures, "app.auth.mode", "KEYCLOAK");
        if (!"VAULT_TRANSIT".equals(credentialCipher.provider())) {
            failures.add("app.credentials.provider doit valoir VAULT_TRANSIT");
        }
        requireEquals(failures, "app.credentials.allow-legacy-plaintext", "false");
        requireEquals(failures, "server.ssl.enabled", "true");
        requireEquals(failures, "server.ssl.client-auth", "need");
        requireContains(failures, "spring.kafka.properties.security.protocol", "SSL");
        requireEquals(failures, "app.kafka.data-transport.enabled", "true");
        requireContains(failures, "spring.data.mongodb.uri", "tls=true");
        requireContains(failures, "spring.datasource.url", "sslmode=verify-full");
        requireStartsWith(failures, "app.credentials.vault.address", "https://");
        requireStartsWith(failures, "spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://");
        requireStartsWith(failures, "app.object-storage.endpoint", "https://");
        // Hibernate ne doit pas pouvoir alterer le schema de la base cible au
        // demarrage: une migration de schema est une operation planifiee, avec
        // sauvegarde et retour arriere, pas un effet de bord d'un redemarrage.
        requireEquals(failures, "spring.jpa.hibernate.ddl-auto", "validate");
        requireEquals(failures, "app.malware-scan.enabled", "true");
        requireEquals(failures, "app.malware-scan.fail-closed", "true");
        requireEquals(failures, "app.malware-scan.clamav.tls-enabled", "true");
        requireEquals(failures, "spring.mail.properties.mail.smtp.starttls.enable", "true");
        requireBlank(failures, "app.interop.internal-secret");

        String allowedOrigins = environment.getProperty("app.security.allowed-origins", "");
        if (allowedOrigins.isBlank() || allowedOrigins.contains("*")) {
            failures.add("app.security.allowed-origins doit lister explicitement les origines HTTPS");
        }

        if (!failures.isEmpty()) {
            throw new IllegalStateException(
                    "Configuration de production refusée:\n - " + String.join("\n - ", failures));
        }
    }

    private void requireEquals(List<String> failures, String key, String expected) {
        String value = environment.getProperty(key, "");
        if (!expected.equalsIgnoreCase(value.trim())) {
            failures.add(key + " doit valoir " + expected);
        }
    }

    private void requireContains(List<String> failures, String key, String expected) {
        String value = environment.getProperty(key, "");
        if (!value.toUpperCase().contains(expected.toUpperCase())) {
            failures.add(key + " doit contenir " + expected);
        }
    }

    private void requireStartsWith(List<String> failures, String key, String expectedPrefix) {
        String value = environment.getProperty(key, "").trim();
        if (!value.toLowerCase().startsWith(expectedPrefix.toLowerCase())) {
            failures.add(key + " doit commencer par " + expectedPrefix);
        }
    }

    private void requireBlank(List<String> failures, String key) {
        if (!environment.getProperty(key, "").isBlank()) {
            failures.add(key + " doit etre vide en production (OAuth2 obligatoire)");
        }
    }
}
