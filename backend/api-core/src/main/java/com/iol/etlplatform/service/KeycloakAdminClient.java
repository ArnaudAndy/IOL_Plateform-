package com.iol.etlplatform.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.dto.user.UserDto;
import com.iol.etlplatform.entity.enums.UserRole;
import com.iol.etlplatform.exception.ResourceNotFoundException;

/** Client REST de l'Admin API Keycloak utilisé uniquement par les administrateurs IOL. */
@Component
public class KeycloakAdminClient {
    private final ObjectMapper objectMapper;

    @Value("${app.keycloak.admin.base-url:}")
    private String baseUrl;

    @Value("${app.keycloak.realm:iol}")
    private String realm;

    @Value("${app.keycloak.admin.token-uri:}")
    private String tokenUri;

    @Value("${app.keycloak.admin.client-id:iol-api-admin}")
    private String clientId;

    @Value("${app.keycloak.admin.client-secret:}")
    private String clientSecret;

    @Value("${app.keycloak.admin.client-secret-file:}")
    private String clientSecretFile;

    private volatile AccessToken cachedToken;

    public KeycloakAdminClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<UserDto> listUsers() {
        List<UserDto> users = new ArrayList<>();
        int first = 0;
        int pageSize = 100;
        while (true) {
            JsonNode page = get("/admin/realms/" + path(realm)
                    + "/users?first=" + first + "&max=" + pageSize);
            if (!page.isArray()) break;
            page.forEach(node -> users.add(toDto(node)));
            if (page.size() < pageSize) break;
            first += pageSize;
        }
        return users;
    }

    public UserDto getUser(String id) {
        return toDto(get("/admin/realms/" + path(realm) + "/users/" + path(id)));
    }

    public UserDto getUserByEmail(String email) {
        JsonNode response = get("/admin/realms/" + path(realm)
                + "/users?exact=true&email=" + path(email));
        if (!response.isArray() || response.isEmpty()) {
            throw new ResourceNotFoundException("Utilisateur Keycloak introuvable: " + email);
        }
        return toDto(response.get(0));
    }

    public UserDto updateRole(String userId, UserRole role) {
        String mappings = "/admin/realms/" + path(realm) + "/users/" + path(userId) + "/role-mappings/realm";
        List<JsonNode> managed = new ArrayList<>();
        JsonNode current = get(mappings);
        if (current.isArray()) {
            current.forEach(item -> {
                String name = item.path("name").asText("");
                if ("ADMIN".equalsIgnoreCase(name) || "USER".equalsIgnoreCase(name)) managed.add(item);
            });
        }
        if (!managed.isEmpty()) request(HttpMethod.DELETE, mappings, managed);
        JsonNode targetRole = get("/admin/realms/" + path(realm) + "/roles/" + path(role.name()));
        request(HttpMethod.POST, mappings, List.of(targetRole));
        return getUser(userId);
    }

    public void deleteUser(String userId) {
        request(HttpMethod.DELETE, "/admin/realms/" + path(realm) + "/users/" + path(userId), null);
    }

    private JsonNode get(String uri) {
        try {
            String raw = client().get().uri(uri)
                    .header("Authorization", "Bearer " + accessToken())
                    .retrieve().body(String.class);
            return objectMapper.readTree(raw);
        } catch (Exception error) {
            throw new IllegalStateException("Lecture Keycloak Admin impossible.", error);
        }
    }

    private void request(HttpMethod method, String uri, Object body) {
        try {
            var request = client().method(method).uri(uri)
                    .header("Authorization", "Bearer " + accessToken());
            if (body != null) request.body(body);
            request.retrieve().toBodilessEntity();
        } catch (Exception error) {
            throw new IllegalStateException("Modification Keycloak Admin impossible.", error);
        }
    }

    private synchronized String accessToken() throws Exception {
        Instant now = Instant.now();
        if (cachedToken != null && cachedToken.refreshAt().isAfter(now)) return cachedToken.value();
        String secret = readSecret();
        String form = "grant_type=client_credentials&client_id=" + path(clientId)
                + "&client_secret=" + path(secret);
        String raw = RestClient.create().post().uri(tokenUri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body(form).retrieve().body(String.class);
        JsonNode response = objectMapper.readTree(raw);
        String value = response.path("access_token").asText("");
        long expires = Math.max(60L, response.path("expires_in").asLong(300L));
        if (value.isBlank()) throw new IllegalStateException("Jeton admin Keycloak absent.");
        cachedToken = new AccessToken(value, now.plusSeconds(Math.max(30L, expires / 2L)));
        return value;
    }

    private RestClient client() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException("KEYCLOAK_ADMIN_BASE_URL est obligatoire.");
        }
        return RestClient.builder().baseUrl(baseUrl.replaceAll("/+$", "")).build();
    }

    private String readSecret() throws Exception {
        if (clientSecretFile != null && !clientSecretFile.isBlank()) {
            return Files.readString(Path.of(clientSecretFile), StandardCharsets.UTF_8).trim();
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            throw new IllegalStateException("Secret du client admin Keycloak absent.");
        }
        return clientSecret.trim();
    }

    private UserDto toDto(JsonNode user) {
        String id = user.path("id").asText("");
        if (id.isBlank()) throw new ResourceNotFoundException("Utilisateur Keycloak introuvable.");
        String email = user.path("email").asText(user.path("username").asText(""));
        String name = (user.path("firstName").asText("") + " " + user.path("lastName").asText("")).trim();
        if (name.isBlank()) name = user.path("username").asText(email);
        UserRole role = userRoles(id).contains("ADMIN") ? UserRole.ADMIN : UserRole.USER;
        return new UserDto(id, name, email, role, user.path("enabled").asBoolean(true));
    }

    private List<String> userRoles(String userId) {
        JsonNode roles = get("/admin/realms/" + path(realm) + "/users/" + path(userId) + "/role-mappings/realm");
        if (!roles.isArray()) return List.of();
        List<String> names = new ArrayList<>();
        roles.forEach(role -> names.add(role.path("name").asText("").toUpperCase()));
        return names;
    }

    private static String path(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record AccessToken(String value, Instant refreshAt) { }
}
