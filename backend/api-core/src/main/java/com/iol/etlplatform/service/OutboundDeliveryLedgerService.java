package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.interop.OutboundDeliveryLedgerRequest;
import com.iol.etlplatform.dto.interop.OutboundDeliveryLedgerResponse;
import com.iol.etlplatform.entity.OutboundDeliveryRecord;
import com.iol.etlplatform.repository.OutboundDeliveryRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Persistent lease ledger for the Kafka OUTBOUND delivery worker.
 *
 * The raw Idempotency-Key is hashed before storage. findAndModify performs the
 * claim atomically across worker replicas and Kafka rebalances; a DELIVERED
 * record is terminal and prevents a second POST.
 */
@Service
@RequiredArgsConstructor
public class OutboundDeliveryLedgerService {

    private final MongoTemplate mongoTemplate;
    private final OutboundDeliveryRecordRepository repository;

    public OutboundDeliveryLedgerResponse claim(OutboundDeliveryLedgerRequest request) {
        String id = hash(request.getIdempotencyKey());
        Instant now = Instant.now();
        long leaseSeconds = Math.max(30L, Math.min(3600L,
                request.getLeaseSeconds() == null ? 300L : request.getLeaseSeconds()));

        Criteria claimable = new Criteria().andOperator(
                Criteria.where("_id").is(id),
                new Criteria().orOperator(
                        Criteria.where("status").exists(false),
                        Criteria.where("status").in("PENDING", "FAILED"),
                        new Criteria().andOperator(
                                Criteria.where("status").is("IN_PROGRESS"),
                                Criteria.where("lease_expires_at").lt(now)),
                        Criteria.where("lease_owner").is(request.getOwner())));

        Update update = new Update()
                .setOnInsert("created_at", now)
                .set("status", "IN_PROGRESS")
                .set("lease_owner", request.getOwner())
                .set("lease_expires_at", now.plusSeconds(leaseSeconds))
                .set("updated_at", now)
                .unset("last_error")
                .inc("attempts", 1);

        try {
            OutboundDeliveryRecord claimed = mongoTemplate.findAndModify(
                    Query.query(claimable), update,
                    FindAndModifyOptions.options().upsert(true).returnNew(true),
                    OutboundDeliveryRecord.class);
            if (claimed != null) {
                return new OutboundDeliveryLedgerResponse("CLAIMED", claimed.getAttempts());
            }
        } catch (DuplicateKeyException ignored) {
            // Un autre worker a gagné l'upsert ou un enregistrement terminal existe déjà.
        }

        OutboundDeliveryRecord existing = repository.findById(id).orElse(null);
        if (existing != null && "DELIVERED".equals(existing.getStatus())) {
            return new OutboundDeliveryLedgerResponse("ALREADY_DELIVERED", existing.getAttempts());
        }
        return new OutboundDeliveryLedgerResponse("BUSY", existing != null ? existing.getAttempts() : 0);
    }

    public OutboundDeliveryLedgerResponse complete(OutboundDeliveryLedgerRequest request) {
        OutboundDeliveryRecord record = updateOwned(request, "DELIVERED", null);
        return new OutboundDeliveryLedgerResponse(record != null ? "DELIVERED" : "NOT_OWNER",
                record != null ? record.getAttempts() : 0);
    }

    public OutboundDeliveryLedgerResponse fail(OutboundDeliveryLedgerRequest request) {
        OutboundDeliveryRecord record = updateOwned(request, "FAILED", request.getErrorMessage());
        return new OutboundDeliveryLedgerResponse(record != null ? "FAILED" : "NOT_OWNER",
                record != null ? record.getAttempts() : 0);
    }

    private OutboundDeliveryRecord updateOwned(
            OutboundDeliveryLedgerRequest request, String status, String errorMessage) {
        Instant now = Instant.now();
        Update update = new Update()
                .set("status", status)
                .set("updated_at", now)
                .unset("lease_expires_at");
        if ("DELIVERED".equals(status)) {
            update.set("delivered_at", now).unset("last_error");
        } else {
            update.set("last_error", errorMessage == null ? "" : errorMessage);
        }
        return mongoTemplate.findAndModify(
                Query.query(new Criteria().andOperator(
                        Criteria.where("_id").is(hash(request.getIdempotencyKey())),
                        Criteria.where("lease_owner").is(request.getOwner()),
                        Criteria.where("status").is("IN_PROGRESS"))),
                update,
                FindAndModifyOptions.options().returnNew(true),
                OutboundDeliveryRecord.class);
    }

    private String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 indisponible", error);
        }
    }
}
