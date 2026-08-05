package com.iol.etlplatform.pipelineconsumer.service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Obtient le mot de passe de destination juste avant le lancement Hop/Spark.
 * Le secret n'est ni écrit dans le JSON temporaire, ni publié dans Kafka.
 */
@Service
public class RuntimeCredentialClient {
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${app.workflow.service.url:http://localhost:8084}")
    private String apiBaseUrl;

    @Value("${app.security.oauth2.token-uri:}")
    private String tokenUri;

    @Value("${app.security.oauth2.client-id:iol-pipeline-consumer}")
    private String clientId;

    @Value("${app.security.oauth2.client-secret:}")
    private String clientSecret;

    @Value("${app.security.oauth2.client-secret-file:}")
    private String clientSecretFile;

    @Value("${app.security.internal-secret:}")
    private String internalSecret;

    @Value("${app.security.internal-secret-file:}")
    private String internalSecretFile;

    private volatile AccessToken cachedToken;

    public RuntimeCredentialClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String resolve(String connectionId, String executionId, String workflowId) {
        try {
            String body = objectMapper.writeValueAsString(new LeaseRequest(executionId, workflowId));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(stripSlash(apiBaseUrl)
                            + "/api/internal/runtime-credentials/" + url(connectionId) + "/leases"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));

            String bearer = accessToken();
            if (!bearer.isBlank()) {
                builder.header("Authorization", "Bearer " + bearer);
            } else {
                String sharedSecret = secretValue(internalSecret, internalSecretFile);
                if (sharedSecret.isBlank()) {
                    throw new IllegalStateException(
                            "Aucune authentification service-à-service n'est configurée pour le worker.");
                }
                builder.header("X-IOL-Internal-Secret", sharedSecret);
            }

            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Délivrance du credential refusée par API Core (HTTP " + response.statusCode() + ").");
            }
            String password = objectMapper.readTree(response.body()).path("password").asText("");
            if (password.isBlank()) {
                throw new IllegalStateException("API Core a retourné un credential vide.");
            }
            return password;
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("Credential de destination indisponible.", error);
        }
    }

    private synchronized String accessToken() throws Exception {
        if (tokenUri == null || tokenUri.isBlank()) return "";
        Instant now = Instant.now();
        if (cachedToken != null && cachedToken.refreshAt().isAfter(now)) return cachedToken.value();

        String secret = secretValue(clientSecret, clientSecretFile);
        if (secret.isBlank()) {
            throw new IllegalStateException("Client secret Keycloak du worker absent.");
        }
        String form = "grant_type=client_credentials&client_id=" + url(clientId)
                + "&client_secret=" + url(secret);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Authentification Keycloak du worker refusée.");
        }
        JsonNode json = objectMapper.readTree(response.body());
        String value = json.path("access_token").asText("");
        long expiresIn = Math.max(60L, json.path("expires_in").asLong(300L));
        if (value.isBlank()) throw new IllegalStateException("Jeton Keycloak du worker absent.");
        cachedToken = new AccessToken(value, now.plusSeconds(Math.max(30L, expiresIn / 2L)));
        return value;
    }

    private String secretValue(String direct, String file) {
        if (file != null && !file.isBlank()) {
            try {
                return Files.readString(Path.of(file), StandardCharsets.UTF_8).trim();
            } catch (Exception error) {
                throw new IllegalStateException("Lecture du secret monté impossible: " + file, error);
            }
        }
        return direct == null ? "" : direct.trim();
    }

    private static String stripSlash(String value) {
        return value == null ? "" : value.replaceAll("/+$", "");
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record LeaseRequest(String executionId, String workflowId) { }
    private record AccessToken(String value, Instant refreshAt) { }
}
