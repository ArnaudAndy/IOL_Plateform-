package com.iol.etlplatform.controller;

import java.security.MessageDigest;
import java.security.Principal;
import java.nio.charset.StandardCharsets;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.iol.etlplatform.dto.connection.RuntimeCredentialLeaseRequest;
import com.iol.etlplatform.dto.connection.RuntimeCredentialLeaseResponse;
import com.iol.etlplatform.service.RuntimeCredentialService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Frontière privée entre API Core et les workers d'exécution. */
@RestController
@RequestMapping("/api/internal/runtime-credentials")
@RequiredArgsConstructor
public class InternalRuntimeCredentialController {
    private static final String INTERNAL_SECRET_HEADER = "X-IOL-Internal-Secret";

    private final RuntimeCredentialService credentialService;

    @Value("${app.interop.internal-secret:}")
    private String localInternalSecret;

    @PostMapping("/{connectionId}/leases")
    public ResponseEntity<?> issue(
            @PathVariable String connectionId,
            @Valid @RequestBody RuntimeCredentialLeaseRequest request,
            @RequestHeader(value = INTERNAL_SECRET_HEADER, required = false) String providedSecret,
            Principal principal) {
        if (!authorizedService(principal, providedSecret)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Worker non authentifié.");
        }
        String servicePrincipal = principal != null ? principal.getName() : "pipeline-consumer-local";
        RuntimeCredentialLeaseResponse response = credentialService.issue(
                connectionId, request, servicePrincipal);
        return ResponseEntity.ok(response);
    }

    private boolean authorizedService(Principal principal, String providedSecret) {
        if (principal instanceof Authentication authentication
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_SERVICE_PIPELINE".equals(authority.getAuthority()))) {
            return true;
        }
        return StringUtils.hasText(localInternalSecret)
                && StringUtils.hasText(providedSecret)
                && MessageDigest.isEqual(
                        localInternalSecret.getBytes(StandardCharsets.UTF_8),
                        providedSecret.getBytes(StandardCharsets.UTF_8));
    }
}
