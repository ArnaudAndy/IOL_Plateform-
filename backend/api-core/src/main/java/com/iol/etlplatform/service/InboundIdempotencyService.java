package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.interop.InboundExecutionPrepareResponse;
import com.iol.etlplatform.entity.InboundIdempotencyRecord;
import com.iol.etlplatform.repository.InboundIdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates exactly one IOL execution for a caller-provided Idempotency-Key.
 *
 * MongoDB's unique {@code _id} makes the initial insert the concurrency gate:
 * only one API instance wins, while every later request reads the durable
 * receipt. Ambiguous or failed attempts remain blocked for reconciliation
 * instead of being replayed automatically and risking duplicate side effects.
 */
@Service
@RequiredArgsConstructor
public class InboundIdempotencyService {

    private static final String IN_PROGRESS = "IN_PROGRESS";
    private static final String COMPLETED = "COMPLETED";
    private static final String FAILED = "FAILED";

    private final InboundIdempotencyRecordRepository repository;

    @Value("${app.interop.idempotency-lease-seconds:86400}")
    private long leaseSeconds = 86400;

    public Claim claim(
            String organizationId,
            String workflowId,
            String standardId,
            String sourceSystem,
            String idempotencyKey,
            String payloadHash) {
        String normalizedKey = requiredKey(idempotencyKey);
        String normalizedHash = normalizedPayloadHash(payloadHash);
        String recordId = sha256(String.join("\n",
                nullToEmpty(organizationId),
                nullToEmpty(workflowId),
                nullToEmpty(standardId),
                nullToEmpty(sourceSystem),
                normalizedKey));
        String owner = UUID.randomUUID().toString();
        Instant now = Instant.now();

        InboundIdempotencyRecord candidate = InboundIdempotencyRecord.builder()
                .id(recordId)
                .status(IN_PROGRESS)
                .leaseOwner(owner)
                .leaseExpiresAt(now.plusSeconds(Math.max(300, leaseSeconds)))
                .organizationId(organizationId)
                .workflowId(workflowId)
                .standardId(standardId)
                .sourceSystem(sourceSystem)
                .payloadHash(normalizedHash)
                .createdAt(now)
                .updatedAt(now)
                .build();
        try {
            repository.insert(candidate);
            return Claim.claimed(recordId, owner);
        } catch (DuplicateKeyException ignored) {
            // Another API instance or an earlier delivery already owns this key.
        }

        InboundIdempotencyRecord existing = repository.findById(recordId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "La clé d'idempotence est en cours d'acquisition; réessayez."));
        rejectHashMismatch(existing.getPayloadHash(), normalizedHash);

        if (COMPLETED.equals(existing.getStatus())) {
            return Claim.replayed(recordId, response(existing));
        }

        String execution = StringUtils.hasText(existing.getExecutionLogId())
                ? " Exécution existante: " + existing.getExecutionLogId() + "."
                : "";
        String state = FAILED.equals(existing.getStatus()) ? "échouée" : "en cours";
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cette Idempotency-Key correspond déjà à une réception "
                        + state + "." + execution
                        + " Une nouvelle clé est requise seulement pour une nouvelle opération.");
    }

    public void attachExecution(Claim claim, String executionLogId) {
        InboundIdempotencyRecord record = ownedInProgress(claim);
        record.setExecutionLogId(executionLogId);
        record.setUpdatedAt(Instant.now());
        repository.save(record);
    }

    public void complete(Claim claim, InboundExecutionPrepareResponse response) {
        InboundIdempotencyRecord record = ownedInProgress(claim);
        Instant now = Instant.now();
        record.setStatus(COMPLETED);
        record.setKafkaTopic(response.getKafkaTopic());
        record.setKafkaKey(response.getKafkaKey());
        record.setDataTransport(response.getDataTransport());
        record.setRecordCount(response.getRecordCount());
        record.setCommandPublished(response.isCommandPublished());
        record.setCompletedAt(now);
        record.setUpdatedAt(now);
        record.setLeaseExpiresAt(null);
        record.setLastError(null);
        repository.save(record);
    }

    public void fail(Claim claim, Throwable error) {
        if (claim == null || claim.replayed()) return;
        repository.findById(claim.recordId()).ifPresent(record -> {
            if (!IN_PROGRESS.equals(record.getStatus())
                    || !claim.owner().equals(record.getLeaseOwner())) {
                return;
            }
            record.setStatus(FAILED);
            record.setLeaseExpiresAt(null);
            record.setUpdatedAt(Instant.now());
            record.setLastError(safeError(error));
            repository.save(record);
        });
    }

    private InboundIdempotencyRecord ownedInProgress(Claim claim) {
        InboundIdempotencyRecord record = repository.findById(claim.recordId())
                .orElseThrow(() -> new IllegalStateException(
                        "Réception idempotente introuvable."));
        if (!IN_PROGRESS.equals(record.getStatus())
                || !claim.owner().equals(record.getLeaseOwner())) {
            throw new IllegalStateException(
                    "La réception idempotente n'appartient plus à cette requête.");
        }
        return record;
    }

    private InboundExecutionPrepareResponse response(InboundIdempotencyRecord record) {
        return InboundExecutionPrepareResponse.builder()
                .workflowId(record.getWorkflowId())
                .execLogId(record.getExecutionLogId())
                .kafkaTopic(record.getKafkaTopic())
                .kafkaKey(record.getKafkaKey())
                .organizationId(record.getOrganizationId())
                .dataTransport(record.getDataTransport())
                .recordCount(record.getRecordCount())
                .commandPublished(record.isCommandPublished())
                .command(Map.of())
                .idempotentReplay(true)
                .build();
    }

    private String requiredKey(String value) {
        if (!StringUtils.hasText(value)) {
            throw new ResponseStatusException(
                    HttpStatus.PRECONDITION_REQUIRED,
                    "Idempotency-Key est obligatoire pour une réception INBOUND.");
        }
        String normalized = value.trim();
        if (normalized.length() > 255 || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key est invalide ou dépasse 255 caractères.");
        }
        return normalized;
    }

    private String normalizedPayloadHash(String value) {
        if (!StringUtils.hasText(value)) return null;
        String normalized = value.trim().toLowerCase();
        if (!normalized.matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "X-IOL-Payload-SHA256 doit contenir un SHA-256 hexadécimal.");
        }
        return normalized;
    }

    private void rejectHashMismatch(String existing, String incoming) {
        if (StringUtils.hasText(existing)
                && StringUtils.hasText(incoming)
                && !MessageDigest.isEqual(
                        existing.getBytes(StandardCharsets.US_ASCII),
                        incoming.getBytes(StandardCharsets.US_ASCII))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cette Idempotency-Key a déjà été utilisée avec un contenu différent.");
        }
    }

    private String safeError(Throwable error) {
        String message = error != null && error.getMessage() != null
                ? error.getMessage() : "Réception INBOUND échouée";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 indisponible", error);
        }
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record Claim(
            String recordId,
            String owner,
            boolean replayed,
            InboundExecutionPrepareResponse response) {

        static Claim claimed(String recordId, String owner) {
            return new Claim(recordId, owner, false, null);
        }

        static Claim replayed(
                String recordId, InboundExecutionPrepareResponse response) {
            return new Claim(recordId, null, true, response);
        }
    }
}
