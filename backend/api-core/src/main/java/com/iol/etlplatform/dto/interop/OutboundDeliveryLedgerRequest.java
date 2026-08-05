package com.iol.etlplatform.dto.interop;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class OutboundDeliveryLedgerRequest {
    @NotBlank
    private String idempotencyKey;
    @NotBlank
    private String owner;
    private Long leaseSeconds;
    private String errorMessage;
}
