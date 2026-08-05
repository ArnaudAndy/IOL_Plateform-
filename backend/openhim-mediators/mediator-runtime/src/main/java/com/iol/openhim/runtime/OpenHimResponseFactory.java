package com.iol.openhim.runtime;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenHimResponseFactory {

    private final MediatorProperties properties;

    public OpenHimResponseFactory(MediatorProperties properties) {
        this.properties = properties;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> decorate(
            Map<String, Object> delegated,
            AdaptedPayload adapted,
            String standardName,
            String correlationId,
            long startedAt) {
        Map<String, Object> response = new LinkedHashMap<>(delegated);
        response.put("x-mediator-urn", properties.getUrn());

        Map<String, Object> domainResponse = Map.of(
                "status", 200,
                "headers", Map.of("Content-Type", "application/json"),
                "body", "{\"validation\":\"passed\",\"recordCount\":" + adapted.records().size() + "}",
                "timestamp", Instant.now().toEpochMilli());
        Map<String, Object> orchestration = Map.of(
                "name", standardName + " structural validation",
                "request", Map.of(
                        "method", "POST",
                        "path", properties.getRoutePath(),
                        "headers", Map.of(),
                        "body", "",
                        "timestamp", startedAt),
                "response", withoutBody(domainResponse));

        Object existing = response.get("orchestrations");
        List<Map<String, Object>> orchestrations = new java.util.ArrayList<>();
        orchestrations.add(orchestration);
        if (existing instanceof List<?> list) {
            list.stream().filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .map(this::withoutBodies)
                    .forEach(orchestrations::add);
        }
        response.put("orchestrations", orchestrations);

        Map<String, Object> responseProperties = response.get("properties") instanceof Map<?, ?> raw
                ? new LinkedHashMap<>((Map<String, Object>) raw)
                : new LinkedHashMap<>();
        responseProperties.put("mediator", properties.getName());
        responseProperties.put("domainStandard", standardName);
        responseProperties.put("domainValidation", "passed");
        responseProperties.put("recordCount", adapted.records().size());
        responseProperties.put("correlationId", correlationId);
        responseProperties.putAll(adapted.metadata());
        response.put("properties", responseProperties);
        return response;
    }

    public Map<String, Object> failure(
            int status,
            String standardName,
            String correlationId,
            List<String> issues) {
        long now = Instant.now().toEpochMilli();
        String body = "{\"validation\":\"failed\",\"issues\":" + jsonStrings(issues) + "}";
        return Map.of(
                "x-mediator-urn", properties.getUrn(),
                "status", "Failed",
                "response", Map.of(
                        "status", status,
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", body,
                        "timestamp", now),
                "orchestrations", List.of(),
                "properties", Map.of(
                        "mediator", properties.getName(),
                        "domainStandard", standardName,
                        "domainValidation", "failed",
                        "correlationId", correlationId));
    }

    private String jsonStrings(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> withoutBodies(Map<String, Object> orchestration) {
        Map<String, Object> sanitized = new LinkedHashMap<>(orchestration);
        for (String exchange : List.of("request", "response")) {
            if (sanitized.get(exchange) instanceof Map<?, ?> raw) {
                Map<String, Object> details =
                        new LinkedHashMap<>((Map<String, Object>) raw);
                details.put("body", "");
                sanitized.put(exchange, details);
            }
        }
        return sanitized;
    }

    private Map<String, Object> withoutBody(Map<String, Object> exchange) {
        Map<String, Object> sanitized = new LinkedHashMap<>(exchange);
        sanitized.put("body", "");
        return sanitized;
    }
}
