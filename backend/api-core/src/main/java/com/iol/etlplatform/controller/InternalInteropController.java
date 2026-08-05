package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.interop.InboundExecutionPrepareRequest;
import com.iol.etlplatform.dto.interop.OutboundDenormalizeRequest;
import com.iol.etlplatform.dto.interop.OutboundDenormalizeResponse;
import com.iol.etlplatform.dto.interop.OutboundDeliveryLedgerRequest;
import com.iol.etlplatform.dto.standard.StandardTermDto;
import com.iol.etlplatform.dto.standard.StandardValidationBatchRequest;
import com.iol.etlplatform.dto.standard.StandardValidationBatchResponse;
import com.iol.etlplatform.service.InternalInteropExecutionService;
import com.iol.etlplatform.service.OutboundDeliveryLedgerService;
import com.iol.etlplatform.service.StandardService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Private boundary used by the OpenHIM mediators.
 *
 * These endpoints are intentionally separate from the user-facing API. The
 * shared internal secret authenticates the mediator, while OpenHIM remains
 * responsible for authenticating the external system. Streaming requests are
 * forwarded as an InputStream so API Core never buffers a multi-gigabyte lot.
 */
@RestController
@RequestMapping("/api/internal/interop")
@Slf4j
public class InternalInteropController {

    private static final String INTERNAL_SECRET_HEADER = "X-IOL-Internal-Secret";

    private final StandardService standardService;
    private final InternalInteropExecutionService interopExecutionService;
    private final OutboundDeliveryLedgerService outboundDeliveryLedgerService;
    private final String internalSecret;

    @Autowired
    public InternalInteropController(
            StandardService standardService,
            InternalInteropExecutionService interopExecutionService,
            OutboundDeliveryLedgerService outboundDeliveryLedgerService,
            @Value("${app.interop.internal-secret:}") String internalSecret) {
        this.standardService = standardService;
        this.interopExecutionService = interopExecutionService;
        this.outboundDeliveryLedgerService = outboundDeliveryLedgerService;
        this.internalSecret = internalSecret;
    }

    InternalInteropController(
            StandardService standardService,
            InternalInteropExecutionService interopExecutionService,
            String internalSecret) {
        this(standardService, interopExecutionService, null, internalSecret);
    }

    @GetMapping("/standards/{standardId}/terms")
    public ResponseEntity<?> getTerms(
            @PathVariable String standardId,
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String providedSecret) {
        ResponseEntity<String> authError = authorize(providedSecret);
        if (authError != null) {
            return authError;
        }

        List<StandardTermDto> terms = standardService.getTermsByStandard(standardId);
        return ResponseEntity.ok(terms);
    }

    @PostMapping("/standards/{standardId}/validate-batch")
    public ResponseEntity<?> validateBatch(
            @PathVariable String standardId,
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String providedSecret,
            @Valid @RequestBody StandardValidationBatchRequest request) {
        ResponseEntity<String> authError = authorize(providedSecret);
        if (authError != null) {
            return authError;
        }

        List<StandardValidationBatchResponse.FieldValidationResult> results = request.getFields().stream()
                .map(field -> validateField(standardId, field))
                .toList();

        boolean valid = results.stream().allMatch(StandardValidationBatchResponse.FieldValidationResult::isValid);
        return ResponseEntity.ok(StandardValidationBatchResponse.builder()
                .valid(valid)
                .results(results)
                .build());
    }

    @PostMapping("/standards/{standardId}/inbound-executions")
    public ResponseEntity<?> prepareInboundExecution(
            @PathVariable String standardId,
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String providedSecret,
            @RequestBody InboundExecutionPrepareRequest request) {
        ResponseEntity<String> authError = authorize(providedSecret);
        if (authError != null) {
            return authError;
        }

        return ResponseEntity.ok(interopExecutionService.prepareInboundExecution(standardId, request));
    }

    @PostMapping(
            value = "/standards/{standardId}/inbound-executions/stream",
            consumes = {"application/x-ndjson", "application/ndjson"})
    public ResponseEntity<?> prepareInboundExecutionStream(
            @PathVariable String standardId,
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String providedSecret,
            HttpServletRequest servletRequest) throws IOException {
        ResponseEntity<String> authError = authorize(providedSecret);
        if (authError != null) {
            return authError;
        }

        long contentLength = servletRequest.getContentLengthLong();
        Long estimatedBytes = nonNegativeLongHeader(
                servletRequest, "X-IOL-Estimated-Bytes");
        if (estimatedBytes == null && contentLength >= 0) {
            estimatedBytes = contentLength;
        }
        InboundExecutionPrepareRequest metadata = InboundExecutionPrepareRequest.builder()
                .workflowId(trimmedHeader(servletRequest, "X-IOL-Workflow-Id"))
                .sourceSystem(trimmedHeader(servletRequest, "X-IOL-Source-System"))
                .correlationId(trimmedHeader(servletRequest, "X-Correlation-Id"))
                .openhimTransactionId(firstNonBlank(
                        trimmedHeader(servletRequest, "X-OpenHIM-TransactionID"),
                        trimmedHeader(servletRequest, "X-OpenHIM-Transaction-Id")))
                .idempotencyKey(trimmedHeader(servletRequest, "Idempotency-Key"))
                .payloadHash(trimmedHeader(servletRequest, "X-IOL-Payload-SHA256"))
                .estimatedRows(nonNegativeLongHeader(
                        servletRequest, "X-IOL-Estimated-Rows"))
                .estimatedBytes(estimatedBytes)
                .estimatedMaxRecordBytes(nonNegativeLongHeader(
                        servletRequest, "X-IOL-Estimated-Max-Record-Bytes"))
                .build();

        return ResponseEntity.ok(interopExecutionService.prepareInboundExecutionStream(
                standardId, metadata, servletRequest.getInputStream()));
    }

    @PostMapping("/standards/{standardId}/denormalize")
    public ResponseEntity<?> denormalizeFromPivot(
            @PathVariable String standardId,
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String providedSecret,
            @RequestBody OutboundDenormalizeRequest request) {
        ResponseEntity<String> authError = authorize(providedSecret);
        if (authError != null) {
            return authError;
        }

        var rows = standardService.denormalizeFromPivot(
                standardId,
                request != null ? request.getPivotRows() : null,
                request != null ? request.getTargetSystem() : null);

        return ResponseEntity.ok(OutboundDenormalizeResponse.builder()
                .standardId(standardId)
                .targetSystem(request != null ? request.getTargetSystem() : null)
                .rows(rows)
                .build());
    }

    @PostMapping("/outbound-deliveries/claim")
    public ResponseEntity<?> claimOutboundDelivery(
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String providedSecret,
            @Valid @RequestBody OutboundDeliveryLedgerRequest request) {
        ResponseEntity<String> authError = authorize(providedSecret);
        if (authError != null) return authError;
        return ResponseEntity.ok(outboundDeliveryLedgerService.claim(request));
    }

    @PostMapping("/outbound-deliveries/complete")
    public ResponseEntity<?> completeOutboundDelivery(
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String providedSecret,
            @Valid @RequestBody OutboundDeliveryLedgerRequest request) {
        ResponseEntity<String> authError = authorize(providedSecret);
        if (authError != null) return authError;
        return ResponseEntity.ok(outboundDeliveryLedgerService.complete(request));
    }

    @PostMapping("/outbound-deliveries/fail")
    public ResponseEntity<?> failOutboundDelivery(
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String providedSecret,
            @Valid @RequestBody OutboundDeliveryLedgerRequest request) {
        ResponseEntity<String> authError = authorize(providedSecret);
        if (authError != null) return authError;
        return ResponseEntity.ok(outboundDeliveryLedgerService.fail(request));
    }

    private StandardValidationBatchResponse.FieldValidationResult validateField(
            String standardId,
            StandardValidationBatchRequest.FieldValidationRequest field) {
        try {
            String value = Objects.toString(field.getFieldValue(), "");
            boolean valid = standardService.validateFieldAgainstStandard(
                    standardId,
                    field.getFieldName(),
                    value,
                    field.getDataType());

            return StandardValidationBatchResponse.FieldValidationResult.builder()
                    .fieldName(field.getFieldName())
                    .dataType(field.getDataType())
                    .valid(valid)
                    .message(valid ? "Validation reussie" : "La valeur ne respecte pas les regles du standard")
                    .build();
        } catch (Exception e) {
            log.warn("Validation interop echouee pour {}: {}", field.getFieldName(), e.getMessage());
            return StandardValidationBatchResponse.FieldValidationResult.builder()
                    .fieldName(field.getFieldName())
                    .dataType(field.getDataType())
                    .valid(false)
                    .message(e.getMessage())
                    .build();
        }
    }

    private ResponseEntity<String> authorize(String providedSecret) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SERVICE_MEDIATOR".equals(authority.getAuthority()))) {
            return null;
        }
        if (!StringUtils.hasText(internalSecret)) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Interop internal API is disabled: app.interop.internal-secret is not configured");
        }
        if (!internalSecret.equals(providedSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid internal interop secret");
        }
        return null;
    }

    private Long nonNegativeLongHeader(HttpServletRequest request, String name) {
        String value = trimmedHeader(request, name);
        if (!StringUtils.hasText(value)) return null;
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 0) throw new NumberFormatException("negative");
            return parsed;
        } catch (NumberFormatException error) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "En-tête " + name + " invalide.");
        }
    }

    private String trimmedHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) return value.trim();
        }
        return null;
    }
}
