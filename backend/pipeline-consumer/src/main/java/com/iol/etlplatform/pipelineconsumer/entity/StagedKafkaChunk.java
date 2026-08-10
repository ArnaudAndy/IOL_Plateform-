package com.iol.etlplatform.pipelineconsumer.entity;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Lot Kafka conserve jusqu'a la fin durable de son execution. */
@Document(collection = "pipeline_data_chunks")
public class StagedKafkaChunk {

    @Id
    private String id;
    private String transferId;
    private int sequence;
    private String payload;
    private String sha256;
    private Instant expiresAt;

    public StagedKafkaChunk() {
    }

    public StagedKafkaChunk(
            String id,
            String transferId,
            int sequence,
            String payload,
            String sha256,
            Instant expiresAt) {
        this.id = id;
        this.transferId = transferId;
        this.sequence = sequence;
        this.payload = payload;
        this.sha256 = sha256;
        this.expiresAt = expiresAt;
    }

    public String getId() { return id; }
    public String getTransferId() { return transferId; }
    public int getSequence() { return sequence; }
    public String getPayload() { return payload; }
    public String getSha256() { return sha256; }
    public Instant getExpiresAt() { return expiresAt; }
}
