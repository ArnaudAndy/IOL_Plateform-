package com.iol.etlplatform.sourcegateway.credential;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.sourcegateway.readmodel.CredentialEnvelope;

/** Client minimal de Vault Transit. Vault ne conserve jamais le plaintext envoyé. */
public final class VaultTransitCredentialCipher implements CredentialCipher {
    private static final String PROVIDER = "VAULT_TRANSIT";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String transitMount;
    private final String keyName;
    private final String namespace;
    private final Path tokenFile;
    private final Path roleIdFile;
    private final Path secretIdFile;

    private volatile CachedToken cachedToken;

    public VaultTransitCredentialCipher(
            String address,
            String transitMount,
            String keyName,
            String namespace,
            String tokenFile,
            String roleIdFile,
            String secretIdFile,
            ObjectMapper objectMapper) {
        this.restClient = RestClient.builder().baseUrl(stripTrailingSlash(address)).build();
        this.objectMapper = objectMapper;
        this.transitMount = cleanPath(transitMount);
        this.keyName = keyName;
        this.namespace = namespace == null ? "" : namespace.trim();
        this.tokenFile = optionalPath(tokenFile);
        this.roleIdFile = optionalPath(roleIdFile);
        this.secretIdFile = optionalPath(secretIdFile);
    }

    // La methode encrypt() de l'implementation d'api-core est volontairement
    // absente: la politique Vault du gateway n'accorde que transit/decrypt.
    // Enregistrer ou faire tourner un secret reste une operation d'api-core.

    @Override
    public String decrypt(CredentialEnvelope envelope, CredentialContext context) {
        if (envelope == null || !PROVIDER.equals(envelope.getProvider())
                || envelope.getCiphertext() == null || envelope.getCiphertext().isBlank()) {
            throw new CredentialCryptoException("Enveloppe incompatible avec Vault Transit.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ciphertext", envelope.getCiphertext());
        body.put("context", base64(context.associatedData()));
        JsonNode response = post("/v1/" + transitMount + "/decrypt/" + envelope.getKeyName(), body);
        String encoded = requiredText(response, "/data/plaintext");
        try {
            return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new CredentialCryptoException("Vault a retourné un plaintext Base64 invalide.", error);
        }
    }

    private JsonNode post(String path, Map<String, Object> body) {
        try {
            String raw = restClient.post()
                    .uri(path)
                    .header("X-Vault-Token", token())
                    .headers(headers -> addNamespace(headers))
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return objectMapper.readTree(raw);
        } catch (Exception error) {
            throw new CredentialCryptoException("Appel Vault Transit refusé pour " + path + ".", error);
        }
    }

    private synchronized String token() {
        if (tokenFile != null) {
            return readSecretFile(tokenFile, "token Vault");
        }
        Instant now = Instant.now();
        if (cachedToken != null && cachedToken.refreshAt().isAfter(now)) {
            return cachedToken.value();
        }
        if (roleIdFile == null || secretIdFile == null) {
            throw new CredentialCryptoException(
                    "Vault requiert VAULT_TOKEN_FILE ou le couple VAULT_ROLE_ID_FILE/VAULT_SECRET_ID_FILE.");
        }
        Map<String, Object> login = Map.of(
                "role_id", readSecretFile(roleIdFile, "role_id Vault"),
                "secret_id", readSecretFile(secretIdFile, "secret_id Vault"));
        try {
            String raw = restClient.post()
                    .uri("/v1/auth/approle/login")
                    .headers(this::addNamespace)
                    .body(login)
                    .retrieve()
                    .body(String.class);
            JsonNode response = objectMapper.readTree(raw);
            String value = requiredText(response, "/auth/client_token");
            long leaseSeconds = Math.max(60L, response.at("/auth/lease_duration").asLong(300L));
            cachedToken = new CachedToken(value, now.plusSeconds(Math.max(30L, leaseSeconds / 2L)));
            return value;
        } catch (Exception error) {
            throw new CredentialCryptoException("Authentification AppRole auprès de Vault impossible.", error);
        }
    }

    private void addNamespace(HttpHeaders headers) {
        if (!namespace.isBlank()) {
            headers.set("X-Vault-Namespace", namespace);
        }
    }

    private String readSecretFile(Path path, String label) {
        try {
            String value = Files.readString(path, StandardCharsets.UTF_8).trim();
            if (value.isBlank()) throw new CredentialCryptoException(label + " vide: " + path);
            return value;
        } catch (CredentialCryptoException error) {
            throw error;
        } catch (Exception error) {
            throw new CredentialCryptoException("Lecture du " + label + " impossible: " + path, error);
        }
    }

    private static String requiredText(JsonNode root, String pointer) {
        String value = root == null ? "" : root.at(pointer).asText("");
        if (value.isBlank()) throw new CredentialCryptoException("Réponse Vault incomplète: " + pointer);
        return value;
    }

    private static int parseVaultVersion(String ciphertext) {
        try {
            String version = ciphertext.split(":", 3)[1];
            return Integer.parseInt(version.substring(1));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String base64(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new CredentialCryptoException("VAULT_ADDR est obligatoire avec Vault Transit.");
        }
        return value.replaceAll("/+$", "");
    }

    private static String cleanPath(String value) {
        String cleaned = value == null ? "transit" : value.replaceAll("^/+|/+$", "");
        return cleaned.isBlank() ? "transit" : cleaned;
    }

    private static Path optionalPath(String value) {
        return value == null || value.isBlank() ? null : Path.of(value).toAbsolutePath().normalize();
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public void assertReady() {
        try {
            restClient.get()
                    .uri("/v1/auth/token/lookup-self")
                    .header("X-Vault-Token", token())
                    .headers(this::addNamespace)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception error) {
            throw new CredentialCryptoException("Vault Transit n'est pas pret.", error);
        }
    }

    private record CachedToken(String value, Instant refreshAt) { }
}
