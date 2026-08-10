package com.iol.etlplatform.sourcegateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.etlplatform.sourcegateway.exception.BadRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ApiSourceClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public List<Map<String, Object>> discoverColumns(Map<String, Object> config) {
        Page page = fetchPage(config, 0, null);
        if (page.rows().isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        page.rows().get(0).fields().forEachRemaining(entry -> {
            Map<String, Object> column = new LinkedHashMap<>();
            column.put("name", entry.getKey());
            column.put("originalName", entry.getKey());
            column.put("type", jsonType(entry.getValue()));
            column.put("nullable", entry.getValue().isNull());
            column.put("mapped", false);
            column.put("iolTerm", "");
            columns.add(column);
        });
        return columns;
    }

    /** Parcourt les pages et transmet chaque objet JSON sans conversion intermediaire. */
    public long streamRows(Map<String, Object> config, RowConsumer consumer) {
        Pagination pagination = Pagination.from(map(config.get("pagination")));
        long rowCount = 0;
        String cursor = string(pagination.initialCursor());

        try {
            for (int pageIndex = 0; pageIndex < pagination.maxPages(); pageIndex++) {
                Page page = fetchPage(config, pageIndex, cursor);
                if (page.rows().isEmpty()) {
                    break;
                }
                for (JsonNode row : page.rows()) {
                    if (!row.isObject() || row.isEmpty()) {
                        throw new BadRequestException(
                                "La réponse API contient une ligne sans colonne exploitable.");
                    }
                    consumer.accept(row);
                    rowCount++;
                }
                if (pagination.type().equals("NONE")) {
                    break;
                }
                if (pagination.type().equals("CURSOR")) {
                    if (page.nextCursor().isBlank() || page.nextCursor().equals(cursor)) {
                        break;
                    }
                    cursor = page.nextCursor();
                } else if (page.rows().size() < pagination.pageSize()) {
                    break;
                }
            }
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Extraction API impossible: " + e.getMessage());
        }
        if (rowCount == 0) {
            throw new BadRequestException("La source API n'a retourné aucune donnée.");
        }
        return rowCount;
    }

    private Page fetchPage(Map<String, Object> config, int pageIndex, String cursor) {
        validate(config);
        Pagination pagination = Pagination.from(map(config.get("pagination")));
        Map<String, Object> query = new LinkedHashMap<>(map(config.get("query_params")));
        switch (pagination.type()) {
            case "PAGE" -> {
                query.put(pagination.pageParam(), pagination.startPage() + pageIndex);
                query.put(pagination.sizeParam(), pagination.pageSize());
            }
            case "OFFSET" -> {
                query.put(pagination.offsetParam(), (long) pageIndex * pagination.pageSize());
                query.put(pagination.limitParam(), pagination.pageSize());
            }
            case "CURSOR" -> {
                if (cursor != null && !cursor.isBlank()) query.put(pagination.cursorParam(), cursor);
                query.put(pagination.sizeParam(), pagination.pageSize());
            }
            default -> { }
        }

        try {
            URI uri = URI.create(withQuery(string(config.get("url")), query));
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(number(config.get("timeout_seconds"), 60)))
                    .header("Accept", "application/json");
            map(config.get("headers")).forEach((name, value) -> builder.header(name, string(value)));
            applyAuthentication(builder, map(config.get("auth")));

            String method = string(config.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
            if ("POST".equals(method)) {
                Object configuredBody = config.getOrDefault("body", Map.of());
                String body = configuredBody instanceof String text ? text : objectMapper.writeValueAsString(configuredBody);
                builder.header("Content-Type", "application/json");
                builder.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            } else {
                builder.GET();
            }

            HttpResponse<String> response = httpClient.send(
                    builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new BadRequestException("API HTTP " + response.statusCode() + ": " + abbreviate(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode records = atPath(root, string(config.get("records_path")));
            List<JsonNode> rows = new ArrayList<>();
            if (records.isArray()) {
                records.forEach(item -> rows.add(asObject(item)));
            } else if (records.isObject()) {
                rows.add(records);
            } else if (!records.isNull() && !records.isMissingNode()) {
                rows.add(objectMapper.createObjectNode().put("value", records.asText()));
            }
            String nextCursor = pagination.type().equals("CURSOR")
                    ? atPath(root, pagination.nextCursorPath()).asText("") : "";
            return new Page(rows, nextCursor);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("Appel API impossible: " + e.getMessage());
        }
    }

    public void validate(Map<String, Object> config) {
        String url = string(config.get("url"));
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw new BadRequestException("API: url HTTP/HTTPS obligatoire.");
        }
        String method = string(config.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
        if (!List.of("GET", "POST").contains(method)) {
            throw new BadRequestException("API: seules les méthodes GET et POST sont supportées.");
        }
        Pagination.from(map(config.get("pagination")));
    }

    private void applyAuthentication(HttpRequest.Builder builder, Map<String, Object> auth) {
        String type = string(auth.getOrDefault("type", "NONE")).toUpperCase(Locale.ROOT);
        switch (type) {
            case "NONE", "" -> { }
            case "BEARER" -> builder.header("Authorization", "Bearer " + resolveSecret(auth.get("secret_ref")));
            case "BASIC" -> {
                String username = resolveSecret(auth.get("username_ref"));
                String password = resolveSecret(auth.get("password_ref"));
                String token = Base64.getEncoder().encodeToString(
                        (username + ":" + password).getBytes(StandardCharsets.UTF_8));
                builder.header("Authorization", "Basic " + token);
            }
            case "API_KEY" -> builder.header(
                    string(auth.getOrDefault("header", "X-API-Key")),
                    string(auth.getOrDefault("prefix", "")) + resolveSecret(auth.get("secret_ref")));
            default -> throw new BadRequestException("Type d'authentification API non supporté: " + type);
        }
    }

    private String resolveSecret(Object reference) {
        String name = string(reference).replaceFirst("^env:", "");
        if (name.isBlank()) throw new BadRequestException("Référence de secret API manquante.");
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Secret API non résolu dans l'environnement: " + name);
        }
        return value;
    }

    private JsonNode asObject(JsonNode value) {
        return value.isObject() ? value : objectMapper.createObjectNode().put("value", value.asText());
    }

    private JsonNode atPath(JsonNode root, String path) {
        JsonNode current = root;
        if (path == null || path.isBlank() || "$".equals(path)) return current;
        for (String segment : path.replaceFirst("^\\$\\.?", "").split("\\.")) {
            if (segment.isBlank()) continue;
            if (current.isArray() && segment.matches("\\d+")) current = current.path(Integer.parseInt(segment));
            else current = current.path(segment);
        }
        return current;
    }

    private String withQuery(String url, Map<String, Object> query) {
        if (query.isEmpty()) return url;
        List<String> parts = new ArrayList<>();
        query.forEach((key, value) -> parts.add(encode(key) + "=" + encode(string(value))));
        return url + (url.contains("?") ? "&" : "?") + String.join("&", parts);
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String jsonType(JsonNode value) {
        if (value.isIntegralNumber()) return "BIGINT";
        if (value.isFloatingPointNumber()) return "DECIMAL";
        if (value.isBoolean()) return "BOOLEAN";
        if (value.isObject() || value.isArray()) return "JSON";
        return "VARCHAR";
    }

    private String abbreviate(String value) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        return text.length() > 300 ? text.substring(0, 300) + "..." : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> raw ? new LinkedHashMap<>((Map<String, Object>) raw) : new LinkedHashMap<>();
    }

    private String string(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private int number(Object value, int fallback) {
        try { return Integer.parseInt(string(value)); } catch (Exception ignored) { return fallback; }
    }

    private record Page(List<JsonNode> rows, String nextCursor) { }

    @FunctionalInterface
    public interface RowConsumer {
        void accept(JsonNode row) throws Exception;
    }

    private record Pagination(
            String type, int pageSize, int maxPages, int startPage,
            String pageParam, String sizeParam, String offsetParam, String limitParam,
            String cursorParam, String nextCursorPath, Object initialCursor) {
        static Pagination from(Map<String, Object> raw) {
            String type = text(raw.getOrDefault("type", "NONE")).toUpperCase(Locale.ROOT);
            if (!List.of("NONE", "PAGE", "OFFSET", "CURSOR").contains(type)) {
                throw new BadRequestException("Pagination API non supportée: " + type);
            }
            int size = integer(raw.get("page_size"), 100);
            int max = integer(raw.get("max_pages"), 100);
            if (size < 1 || size > 10_000 || max < 1 || max > 10_000) {
                throw new BadRequestException("Pagination API: page_size/max_pages hors limites.");
            }
            return new Pagination(type, size, max, integer(raw.get("start_page"), 1),
                    or(raw.get("page_param"), "page"), or(raw.get("size_param"), "size"),
                    or(raw.get("offset_param"), "offset"), or(raw.get("limit_param"), "limit"),
                    or(raw.get("cursor_param"), "cursor"), or(raw.get("next_cursor_path"), "next_cursor"),
                    raw.get("initial_cursor"));
        }
        private static int integer(Object value, int fallback) {
            try { return Integer.parseInt(text(value)); } catch (Exception ignored) { return fallback; }
        }
        private static String or(Object value, String fallback) {
            String text = text(value); return text.isBlank() ? fallback : text;
        }
        private static String text(Object value) { return value == null ? "" : value.toString().trim(); }
    }
}
