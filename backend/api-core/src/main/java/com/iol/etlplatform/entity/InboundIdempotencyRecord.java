package com.iol.etlplatform.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

/**
 * Durable receipt for an INBOUND interoperability request.
 *
 * The document deliberately contains only routing metadata and hashes. Neither
 * the Idempotency-Key nor the business payload is persisted in this ledger.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "inbound_idempotency_ledger")
public class InboundIdempotencyRecord {
    @Id
    private String id;
    private String status;
    @Field("lease_owner")
    private String leaseOwner;
    @Field("lease_expires_at")
    private Instant leaseExpiresAt;
    @Field("organization_id")
    private String organizationId;
    @Field("workflow_id")
    private String workflowId;
    @Field("standard_id")
    private String standardId;
    @Field("source_system")
    private String sourceSystem;
    @Field("payload_hash")
    private String payloadHash;
    @Field("execution_log_id")
    private String executionLogId;
    @Field("kafka_topic")
    private String kafkaTopic;
    @Field("kafka_key")
    private String kafkaKey;
    @Field("data_transport")
    private String dataTransport;
    @Field("record_count")
    private long recordCount;
    @Field("command_published")
    private boolean commandPublished;
    @Field("created_at")
    private Instant createdAt;
    @Field("updated_at")
    private Instant updatedAt;
    @Field("completed_at")
    private Instant completedAt;
    @Field("last_error")
    private String lastError;
}
