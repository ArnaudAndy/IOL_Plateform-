package com.iol.openhim.edfi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iol.openhim.runtime.AdaptedPayload;
import com.iol.openhim.runtime.DomainPayloadAdapter;
import com.iol.openhim.runtime.DomainValidationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class EdFiPayloadAdapter implements DomainPayloadAdapter {

    private static final Pattern RESOURCE_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9-]{1,127}");
    private static final Pattern UUID =
            Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}"
                    + "-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    private final ObjectMapper objectMapper;
    private final String dataStandardVersion;
    private final String apiVersion;
    private final int maxRecords;

    public EdFiPayloadAdapter(
            ObjectMapper objectMapper,
            @Value("${mediator.edfi.data-standard-version:6.1.0}") String dataStandardVersion,
            @Value("${mediator.edfi.api-version:8}") String apiVersion,
            @Value("${mediator.edfi.max-records-per-request:250000}") int maxRecords) {
        this.objectMapper = objectMapper;
        this.dataStandardVersion = dataStandardVersion;
        this.apiVersion = apiVersion;
        this.maxRecords = maxRecords;
    }

    @Override
    public AdaptedPayload adapt(
            byte[] body,
            String contentType,
            String requestPath,
            HttpHeaders headers) {
        if (body == null || body.length == 0) {
            throw new DomainValidationException("La charge Ed-Fi est vide.");
        }
        try {
            String resourceName = resourceName(requestPath, headers);
            List<JsonNode> resources = parseResources(body, contentType);
            if (resources.isEmpty()) {
                throw new DomainValidationException("Le lot Ed-Fi est vide.");
            }
            if (resources.size() > maxRecords) {
                throw new DomainValidationException(
                        "Le lot Ed-Fi dépasse la limite de " + maxRecords + " ressources.");
            }

            List<Map<String, Object>> records = new ArrayList<>(resources.size());
            List<String> issues = new ArrayList<>();
            for (int index = 0; index < resources.size(); index++) {
                JsonNode resource = resources.get(index);
                validateResource(resource, index, issues);
                if (resource.isObject()) {
                    records.add(toRecord(resource, resourceName));
                }
            }
            if (!issues.isEmpty()) {
                throw new DomainValidationException("Validation Ed-Fi échouée.", issues);
            }
            return new AdaptedPayload(records, Map.of(
                    "edfiResource", resourceName,
                    "edfiDataStandardVersion", dataStandardVersion,
                    "edfiApiVersion", apiVersion,
                    "edfiResourceCount", records.size()));
        } catch (DomainValidationException error) {
            throw error;
        } catch (Exception error) {
            throw new DomainValidationException(
                    "Message Ed-Fi invalide: " + rootMessage(error));
        }
    }

    @Override
    public String standardName() {
        return "Ed-Fi Data Standard " + dataStandardVersion;
    }

    private List<JsonNode> parseResources(byte[] body, String contentType) throws Exception {
        String text = new String(body, StandardCharsets.UTF_8).trim();
        if (contentType != null && contentType.toLowerCase().contains("ndjson")) {
            List<JsonNode> result = new ArrayList<>();
            for (String line : text.split("\\R")) {
                if (!line.isBlank()) result.add(objectMapper.readTree(line));
            }
            return result;
        }

        JsonNode root = objectMapper.readTree(text);
        JsonNode collection = root.isArray()
                ? root
                : root.has("records") && root.path("records").isArray()
                    ? root.path("records")
                    : root.has("resources") && root.path("resources").isArray()
                        ? root.path("resources")
                        : null;
        if (collection == null) return List.of(root);
        List<JsonNode> result = new ArrayList<>();
        collection.forEach(result::add);
        return result;
    }

    private void validateResource(JsonNode resource, int index, List<String> issues) {
        if (!resource.isObject()) {
            issues.add("resources[" + index + "] doit être un objet JSON.");
            return;
        }
        if (resource.hasNonNull("id")
                && (!resource.path("id").isTextual()
                    || !UUID.matcher(resource.path("id").asText()).matches())) {
            issues.add("resources[" + index + "].id doit être un UUID Ed-Fi valide.");
        }
        if (resource.has("_etag") && !resource.path("_etag").isTextual()) {
            issues.add("resources[" + index + "]._etag doit être une chaîne.");
        }
        resource.fields().forEachRemaining(field -> {
            if (field.getKey().endsWith("Reference")
                    && !field.getValue().isNull()
                    && !field.getValue().isObject()) {
                issues.add("resources[" + index + "]." + field.getKey()
                        + " doit être un objet de référence Ed-Fi.");
            }
        });
    }

    private Map<String, Object> toRecord(JsonNode resource, String resourceName) throws Exception {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("edfi_resource", resourceName);
        record.put("edfi_id", resource.path("id").asText(""));
        record.put("edfi_etag", resource.path("_etag").asText(""));
        record.put("edfi_data_standard_version", dataStandardVersion);
        record.put("edfi_api_version", apiVersion);
        record.put("edfi_payload_json", objectMapper.writeValueAsString(resource));
        return record;
    }

    private String resourceName(String requestPath, HttpHeaders headers) {
        String configured = headers.getFirst("X-EdFi-Resource");
        if (StringUtils.hasText(configured)) return validateResourceName(configured);
        String path = requestPath == null ? "" : requestPath.replaceAll("/+$", "");
        int slash = path.lastIndexOf('/');
        String candidate = slash >= 0 ? path.substring(slash + 1) : path;
        if ("edfi".equalsIgnoreCase(candidate) || "interop".equalsIgnoreCase(candidate)
                || candidate.isBlank()) {
            throw new DomainValidationException(
                    "Le nom de ressource Ed-Fi est requis dans le chemin ou X-EdFi-Resource.");
        }
        return validateResourceName(candidate);
    }

    private String validateResourceName(String value) {
        String normalized = value.trim();
        if (!RESOURCE_NAME.matcher(normalized).matches()) {
            throw new DomainValidationException("Nom de ressource Ed-Fi invalide.");
        }
        return normalized;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null
                ? current.getMessage() : current.getClass().getSimpleName();
    }
}
