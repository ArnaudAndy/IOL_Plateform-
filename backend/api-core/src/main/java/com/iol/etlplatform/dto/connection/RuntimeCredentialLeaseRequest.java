package com.iol.etlplatform.dto.connection;

import jakarta.validation.constraints.NotBlank;

public record RuntimeCredentialLeaseRequest(
        @NotBlank String executionId,
        @NotBlank String workflowId) { }
