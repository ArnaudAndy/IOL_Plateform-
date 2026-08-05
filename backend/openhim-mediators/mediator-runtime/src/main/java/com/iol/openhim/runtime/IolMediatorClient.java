package com.iol.openhim.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Streams domain-validated records to the generic IOL mediator as NDJSON.
 *
 * The SHA-256 covers the exact normalized bytes sent downstream. API Core uses
 * it to reject accidental reuse of an Idempotency-Key with different content.
 */
@Component
public class IolMediatorClient {

    private final MediatorProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public IolMediatorClient(
            MediatorProperties properties,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.getConnectTimeoutMs());
        requestFactory.setReadTimeout(properties.getReadTimeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(properties.getIolMediatorUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> handOff(
            AdaptedPayload payload,
            HttpHeaders inboundHeaders,
            String correlationId) {
        String standardId = firstNonBlank(
                inboundHeaders.getFirst("X-IOL-Standard-Id"),
                properties.getDefaultStandardId());
        if (!StringUtils.hasText(standardId)) {
            throw new DomainValidationException(
                    "Aucun standard IOL n'est rattaché à ce canal médiateur.");
        }

        PayloadMetrics metrics = payloadMetrics(payload.records());
        Map<String, Object> response = restClient.post()
                .uri("/")
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .header("X-IOL-Standard-Id", standardId)
                .header("X-IOL-Workflow-Id", firstNonBlank(
                        inboundHeaders.getFirst("X-IOL-Workflow-Id"),
                        properties.getDefaultWorkflowId()))
                .header("X-IOL-Source-System", properties.getSourceSystem())
                .header("X-IOL-Adapter", "generic-json")
                .header("X-Correlation-Id", correlationId)
                .header("Idempotency-Key",
                        inboundHeaders.getFirst("Idempotency-Key"))
                .header("X-IOL-Payload-SHA256", metrics.sha256())
                .header("X-IOL-Estimated-Rows",
                        String.valueOf(payload.records().size()))
                .header("X-IOL-Estimated-Bytes",
                        String.valueOf(metrics.totalBytes()))
                .header("X-IOL-Estimated-Max-Record-Bytes",
                        String.valueOf(metrics.maxRecordBytes()))
                .headers(headers -> copyIfPresent(
                        inboundHeaders, headers, "X-OpenHIM-TransactionID"))
                .body((StreamingHttpOutputMessage.Body) outputStream -> {
                    for (Map<String, Object> record : payload.records()) {
                        outputStream.write(objectMapper.writeValueAsBytes(record));
                        outputStream.write('\n');
                    }
                })
                .retrieve()
                .body(Map.class);
        if (response == null) {
            throw new IllegalStateException("Le médiateur IOL générique a retourné une réponse vide.");
        }
        return response;
    }

    private void copyIfPresent(HttpHeaders source, HttpHeaders target, String name) {
        String value = source.getFirst(name);
        if (StringUtils.hasText(value)) target.set(name, value);
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) return candidate.trim();
        }
        return "";
    }

    private PayloadMetrics payloadMetrics(List<Map<String, Object>> records) {
        long totalBytes = 0;
        long maxRecordBytes = 0;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Map<String, Object> record : records) {
                byte[] serialized = objectMapper.writeValueAsBytes(record);
                long bytes = serialized.length + 1L;
                totalBytes = Math.addExact(totalBytes, bytes);
                maxRecordBytes = Math.max(maxRecordBytes, bytes);
                digest.update(serialized);
                digest.update((byte) '\n');
            }
            return new PayloadMetrics(
                    totalBytes, maxRecordBytes, HexFormat.of().formatHex(digest.digest()));
        } catch (ArithmeticException error) {
            throw new DomainValidationException(
                    "La taille du lot dépasse la capacité de transport.");
        } catch (Exception error) {
            throw new IllegalStateException(
                    "Le lot normalisé ne peut pas être sérialisé en NDJSON.", error);
        }
    }

    private record PayloadMetrics(
            long totalBytes, long maxRecordBytes, String sha256) {
    }
}
