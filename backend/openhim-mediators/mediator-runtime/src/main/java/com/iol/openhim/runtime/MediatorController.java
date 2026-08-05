package com.iol.openhim.runtime;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared HTTP entry point for each domain mediator.
 *
 * A domain adapter validates the standard and produces records; the generic
 * mediator then applies IOL mappings and transport. The incoming idempotency
 * key is mandatory because OpenHIM retries must resolve to the same execution.
 */
@RestController
public class MediatorController {

    private final DomainPayloadAdapter adapter;
    private final IolMediatorClient iolMediatorClient;
    private final OpenHimResponseFactory responseFactory;
    private final MediatorProperties properties;

    public MediatorController(
            DomainPayloadAdapter adapter,
            IolMediatorClient iolMediatorClient,
            OpenHimResponseFactory responseFactory,
            MediatorProperties properties) {
        this.adapter = adapter;
        this.iolMediatorClient = iolMediatorClient;
        this.responseFactory = responseFactory;
        this.properties = properties;
    }

    @PostMapping(path = {"/", "/**"})
    public ResponseEntity<Map<String, Object>> mediate(
            @RequestBody byte[] body,
            HttpServletRequest request) {
        long startedAt = System.currentTimeMillis();
        HttpHeaders headers = requestHeaders(request);
        String correlationId = firstNonBlank(
                headers.getFirst("X-Correlation-Id"),
                headers.getFirst("X-Request-Id"),
                UUID.randomUUID().toString());
        try {
            requireIdempotencyKey(headers.getFirst("Idempotency-Key"));
            if (properties.getMaxRequestBytes() > 0
                    && body.length > properties.getMaxRequestBytes()) {
                throw new DomainValidationException(
                        "La requête dépasse la taille maximale autorisée pour ce médiateur.");
            }
            AdaptedPayload adapted = adapter.adapt(
                    body,
                    headers.getFirst(HttpHeaders.CONTENT_TYPE),
                    request.getRequestURI(),
                    headers);
            if (adapted.records().isEmpty()) {
                throw new DomainValidationException("Le message ne contient aucun enregistrement.");
            }
            Map<String, Object> delegated =
                    iolMediatorClient.handOff(adapted, headers, correlationId);
            return ResponseEntity.ok()
                    .contentType(OpenHimWebConfiguration.OPENHIM_JSON)
                    .body(responseFactory.decorate(
                            delegated, adapted, adapter.standardName(), correlationId, startedAt));
        } catch (DomainValidationException error) {
            return ResponseEntity.ok()
                    .contentType(OpenHimWebConfiguration.OPENHIM_JSON)
                    .body(responseFactory.failure(
                            400, adapter.standardName(), correlationId, error.getIssues()));
        } catch (Exception error) {
            return ResponseEntity.ok()
                    .contentType(OpenHimWebConfiguration.OPENHIM_JSON)
                    .body(responseFactory.failure(
                            500,
                            adapter.standardName(),
                            correlationId,
                            List.of(error.getMessage() != null
                                    ? error.getMessage() : error.getClass().getSimpleName())));
        }
    }

    private HttpHeaders requestHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        request.getHeaderNames().asIterator()
                .forEachRemaining(name -> headers.put(name, java.util.Collections.list(
                        request.getHeaders(name))));
        return headers;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return "";
    }

    private void requireIdempotencyKey(String value) {
        if (!StringUtils.hasText(value)) {
            throw new DomainValidationException(
                    "Idempotency-Key est obligatoire pour toute réception INBOUND.");
        }
        String normalized = value.trim();
        if (normalized.length() > 255
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new DomainValidationException(
                    "Idempotency-Key est invalide ou dépasse 255 caractères.");
        }
    }
}
