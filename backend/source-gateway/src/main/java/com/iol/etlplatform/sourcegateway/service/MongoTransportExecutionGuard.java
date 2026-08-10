package com.iol.etlplatform.sourcegateway.service;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.iol.etlplatform.sourcegateway.contract.TransportOrder;
import com.iol.etlplatform.sourcegateway.entity.TransportClaim;

import jakarta.annotation.PreDestroy;

/**
 * Reservation persistante d'une execution, avec bail renouvele et fencing.
 *
 * L'unicite MongoDB de l'execLogId arbitre la premiere acquisition. Chaque
 * reprise recoit ensuite un nouveau fencingToken. Toutes les mises a jour et la
 * publication finale sont conditionnees par ce jeton: un ancien worker qui se
 * reveille apres l'expiration de son bail ne peut plus produire d'effet.
 */
@Service
public class MongoTransportExecutionGuard implements TransportExecutionGuard {

    private static final Logger log = LoggerFactory.getLogger(MongoTransportExecutionGuard.class);

    private final MongoTemplate mongoTemplate;
    private final TransportPipeline transportPipeline;
    private final TransportStatusPublisher statusPublisher;
    private final ScheduledExecutorService leaseScheduler;
    private final String owner;

    @Value("${app.execution.claim-lease-seconds:1800}")
    private long claimLeaseSeconds = 1800;

    @Value("${app.execution.claim-heartbeat-seconds:30}")
    private long claimHeartbeatSeconds = 30;

    public MongoTransportExecutionGuard(
            MongoTemplate mongoTemplate,
            TransportPipeline transportPipeline,
            TransportStatusPublisher statusPublisher) {
        this.mongoTemplate = mongoTemplate;
        this.transportPipeline = transportPipeline;
        this.statusPublisher = statusPublisher;
        this.owner = resolveOwner();
        this.leaseScheduler = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "source-gateway-lease-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public Claim claim(TransportOrder order) {
        Instant now = Instant.now();
        String token = UUID.randomUUID().toString();
        TransportClaim claim = TransportClaim.builder()
                .executionLogId(order.execLogId())
                .workflowId(order.workflowId())
                .organizationId(order.organizationId())
                .owner(owner)
                .fencingToken(token)
                .attempts(1)
                .status(TransportClaim.Status.IN_PROGRESS)
                .claimedAt(now)
                .leaseExpiresAt(leaseExpiry(now))
                .build();
        try {
            mongoTemplate.insert(claim);
            return Claim.acquired(token, 1);
        } catch (DuplicateKeyException alreadyClaimed) {
            return reclaimOrInspect(order, now);
        }
    }

    private Claim reclaimOrInspect(TransportOrder order, Instant now) {
        String nextToken = UUID.randomUUID().toString();
        Query abandoned = Query.query(Criteria
                .where("_id").is(order.execLogId())
                .and("status").is(TransportClaim.Status.IN_PROGRESS)
                .and("leaseExpiresAt").lt(now));
        Update takeOver = new Update()
                .set("owner", owner)
                .set("fencingToken", nextToken)
                .set("claimedAt", now)
                .set("leaseExpiresAt", leaseExpiry(now))
                .inc("attempts", 1);

        TransportClaim reclaimed = mongoTemplate.findAndModify(
                abandoned,
                takeOver,
                FindAndModifyOptions.options().returnNew(true),
                TransportClaim.class);
        if (reclaimed != null) {
            int attempt = Math.max(1, reclaimed.getAttempts());
            log.warn("Reprise d'une execution au bail expire: execLogId={} tentative={}",
                    order.execLogId(), attempt);
            return Claim.acquired(nextToken, attempt);
        }

        TransportClaim current = mongoTemplate.findById(order.execLogId(), TransportClaim.class);
        if (current == null || current.getStatus() == TransportClaim.Status.IN_PROGRESS) {
            return Claim.of(ClaimState.BUSY, current == null ? 0 : current.getAttempts());
        }
        ClaimState state = current.getStatus() == TransportClaim.Status.COMPLETED
                ? ClaimState.COMPLETED
                : ClaimState.FAILED;
        return Claim.of(state, current.getAttempts());
    }

    @Override
    public void transportAndPublish(TransportOrder order, Claim claim) throws Exception {
        requireAcquired(claim);
        statusPublisher.started(order, claim.attempt());

        AtomicBoolean leaseValid = new AtomicBoolean(true);
        long heartbeatSeconds = effectiveHeartbeatSeconds();
        ScheduledFuture<?> heartbeat = leaseScheduler.scheduleAtFixedRate(
                () -> renewLeaseAndReport(order, claim, leaseValid),
                heartbeatSeconds,
                heartbeatSeconds,
                TimeUnit.SECONDS);
        try {
            Runnable ownershipCheck = () -> assertOwnership(order, claim, leaseValid);
            transportPipeline.run(order, ownershipCheck);
            ownershipCheck.run();

            // Le statut est durablement publie avant de rendre le claim
            // terminal. Si Kafka est indisponible, l'ordre reste reprenable et
            // l'interface ne reste pas figee sans explication.
            statusPublisher.waitingForEngine(order, claim.attempt());
            markCompleted(order, claim);
        } finally {
            heartbeat.cancel(false);
        }
    }

    @Override
    public void release(TransportOrder order, Claim claim, Throwable cause) {
        if (claim == null || !claim.acquired()) return;
        // Liberer d'abord le bail. Si Kafka est momentanement indisponible, la
        // publication du statut peut echouer, mais cela ne doit pas condamner
        // les rejeux suivants a attendre toute l'expiration du lease.
        mongoTemplate.updateFirst(
                ownedClaimQuery(order, claim),
                new Update()
                        .set("leaseExpiresAt", Instant.now())
                        .set("failureReason", rootMessage(cause)),
                TransportClaim.class);
        statusPublisher.retryScheduled(order, claim.attempt(), cause);
    }

    @Override
    public void failPermanently(TransportOrder order, Claim claim, Throwable cause) throws Exception {
        requireAcquired(claim);
        statusPublisher.failed(order, claim.attempt(), cause);

        long modified = mongoTemplate.updateFirst(
                ownedClaimQuery(order, claim),
                new Update()
                        .set("status", TransportClaim.Status.FAILED)
                        .set("failedAt", Instant.now())
                        .set("failureReason", rootMessage(cause))
                        .unset("leaseExpiresAt"),
                TransportClaim.class).getModifiedCount();
        if (modified != 1) {
            throw new LeaseLostException(order.execLogId());
        }
    }

    private void renewLeaseAndReport(TransportOrder order, Claim claim, AtomicBoolean leaseValid) {
        try {
            Instant now = Instant.now();
            Query renewable = ownedClaimQuery(order, claim);
            renewable.addCriteria(Criteria.where("leaseExpiresAt").gt(now));
            long modified = mongoTemplate.updateFirst(
                    renewable,
                    new Update().set("leaseExpiresAt", leaseExpiry(now)),
                    TransportClaim.class).getModifiedCount();
            if (modified != 1) {
                leaseValid.set(false);
                log.error("Bail de transport perdu: execLogId={} token={}",
                        order.execLogId(), claim.fencingToken());
                return;
            }
            statusPublisher.heartbeat(order, claim.attempt());
        } catch (Exception failure) {
            leaseValid.set(false);
            log.error("Renouvellement du bail impossible pour execLogId={}: {}",
                    order.execLogId(), failure.getMessage(), failure);
        }
    }

    private void assertOwnership(TransportOrder order, Claim claim, AtomicBoolean leaseValid) {
        if (!leaseValid.get()) throw new LeaseLostException(order.execLogId());

        Query validLease = ownedClaimQuery(order, claim);
        validLease.addCriteria(Criteria.where("leaseExpiresAt").gt(Instant.now()));
        if (!mongoTemplate.exists(validLease, TransportClaim.class)) {
            leaseValid.set(false);
            throw new LeaseLostException(order.execLogId());
        }
    }

    private void markCompleted(TransportOrder order, Claim claim) {
        long modified = mongoTemplate.updateFirst(
                ownedClaimQuery(order, claim),
                new Update()
                        .set("status", TransportClaim.Status.COMPLETED)
                        .set("completedAt", Instant.now())
                        .unset("leaseExpiresAt")
                        .unset("failureReason"),
                TransportClaim.class).getModifiedCount();
        if (modified != 1) throw new LeaseLostException(order.execLogId());
    }

    private Query ownedClaimQuery(TransportOrder order, Claim claim) {
        return Query.query(Criteria.where("_id").is(order.execLogId())
                .and("status").is(TransportClaim.Status.IN_PROGRESS)
                .and("owner").is(owner)
                .and("fencingToken").is(claim.fencingToken()));
    }

    private Instant leaseExpiry(Instant from) {
        return from.plus(Duration.ofSeconds(Math.max(2, claimLeaseSeconds)));
    }

    private long effectiveHeartbeatSeconds() {
        long lease = Math.max(2, claimLeaseSeconds);
        return Math.max(1, Math.min(claimHeartbeatSeconds, Math.max(1, lease / 3)));
    }

    private static void requireAcquired(Claim claim) {
        if (claim == null || !claim.acquired() || claim.fencingToken() == null) {
            throw new IllegalArgumentException("Une reservation acquise avec fencingToken est requise");
        }
    }

    private static String rootMessage(Throwable cause) {
        if (cause == null) return "cause inconnue";
        Throwable current = cause;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static String resolveOwner() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception unresolved) {
            host = "source-gateway";
        }
        return host + ":" + UUID.randomUUID();
    }

    @PreDestroy
    void stopLeaseScheduler() {
        leaseScheduler.shutdownNow();
    }

    static final class LeaseLostException extends IllegalStateException {
        LeaseLostException(String execLogId) {
            super("Bail de transport perdu pour execLogId=" + execLogId);
        }
    }
}
