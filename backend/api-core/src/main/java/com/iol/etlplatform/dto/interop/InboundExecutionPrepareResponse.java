package com.iol.etlplatform.dto.interop;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * Receipt returned after Kafka accepted the execution command.
 * {@code idempotentReplay} identifies a response restored from the durable
 * ledger rather than a newly created execution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InboundExecutionPrepareResponse {
    private String workflowId;
    private String execLogId;
    private String kafkaTopic;
    private String kafkaKey;
    private String organizationId;
    private String dataTransport;
    private long recordCount;
    private boolean commandPublished;
    private boolean idempotentReplay;
    private Map<String, Object> command;
}
