package com.iol.etlplatform.dto.interop;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Metadata and normalized pivots handed from a trusted mediator to API Core.
 * Streaming calls carry the same metadata in HTTP headers and the pivots in
 * the NDJSON request body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboundExecutionPrepareRequest {
    private String workflowId;
    private String sourceSystem;
    private String correlationId;
    private String openhimTransactionId;
    private String idempotencyKey;
    private String payloadHash;
    private Map<String, Object> pivot;
    private List<Map<String, Object>> pivots;
    private Long estimatedRows;
    private Long estimatedBytes;
    private Long estimatedMaxRecordBytes;
}
