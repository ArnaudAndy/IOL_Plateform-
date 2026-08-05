package com.iol.etlplatform.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "outbound_delivery_ledger")
public class OutboundDeliveryRecord {
    @Id
    private String id;
    private String status;
    @Field("lease_owner")
    private String leaseOwner;
    @Field("lease_expires_at")
    private Instant leaseExpiresAt;
    private int attempts;
    @Field("created_at")
    private Instant createdAt;
    @Field("updated_at")
    private Instant updatedAt;
    @Field("delivered_at")
    private Instant deliveredAt;
    @Field("last_error")
    private String lastError;
}
