package com.iol.etlplatform.pipelineconsumer.service;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
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

import com.iol.etlplatform.pipelineconsumer.entity.PipelineExecutionClaim;

import jakarta.annotation.PreDestroy;

/** Registre Mongo qui remplace la deduplication en memoire du moteur. */
@Service
public class PipelineExecutionRegistry {

    private static final Logger log = LoggerFactory.getLogger(PipelineExecutionRegistry.class);
    private static final int MAX_PERSISTED_LOG_CHARS = 512_000;

    private final MongoTemplate mongoTemplate;
    private final String owner = resolveOwner();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "pipeline-execution-lease-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    @Value("${app.execution-registry.lease-seconds:7200}")
    private long leaseSeconds = 7200;

    @Value("${app.execution-registry.heartbeat-seconds:30}")
    private long heartbeatSeconds = 30;

    public PipelineExecutionRegistry(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public Claim claim(String execLogId, String workflowId, String commandPayload) {
        String commandHash = sha256(commandPayload);
        String token = UUID.randomUUID().toString();
        Instant now = Instant.now();
        PipelineExecutionClaim document = new PipelineExecutionClaim();
        document.setExecutionLogId(execLogId);
        document.setWorkflowId(workflowId);
        document.setCommandHash(commandHash);
        document.setOwner(owner);
        document.setFencingToken(token);
        document.setState(PipelineExecutionClaim.State.IN_PROGRESS);
        document.setAttempts(1);
        document.setClaimedAt(now);
        document.setLeaseExpiresAt(expiry(now));
        try {
            mongoTemplate.insert(document);
            return Claim.acquired(token, commandHash, 1);
        } catch (DuplicateKeyException duplicate) {
            return reclaimOrInspect(execLogId, commandHash, now);
        }
    }

    private Claim reclaimOrInspect(String execLogId, String commandHash, Instant now) {
        String token = UUID.randomUUID().toString();
        Query expired = Query.query(Criteria.where("_id").is(execLogId)
                .and("commandHash").is(commandHash)
                .and("state").is(PipelineExecutionClaim.State.IN_PROGRESS)
                .and("leaseExpiresAt").lt(now));
        PipelineExecutionClaim reclaimed = mongoTemplate.findAndModify(
                expired,
                new Update()
                        .set("owner", owner)
                        .set("fencingToken", token)
                        .set("claimedAt", now)
                        .set("leaseExpiresAt", expiry(now))
                        .inc("attempts", 1),
                FindAndModifyOptions.options().returnNew(true),
                PipelineExecutionClaim.class);
        if (reclaimed != null) {
            log.warn("Reprise persistante du pipeline execLogId={} tentative={}",
                    execLogId, reclaimed.getAttempts());
            return Claim.acquired(token, commandHash, Math.max(1, reclaimed.getAttempts()));
        }

        PipelineExecutionClaim current =
                mongoTemplate.findById(execLogId, PipelineExecutionClaim.class);
        if (current == null) return Claim.busy(commandHash, 0);
        if (current.getState() == PipelineExecutionClaim.State.IN_PROGRESS) {
            if (!commandHash.equals(current.getCommandHash())) {
                throw new CommandPayloadMismatchException(execLogId);
            }
            return Claim.busy(commandHash, current.getAttempts());
        }

        // Le resultat terminal est l'autorite. Le gateway peut republier une
        // commande legerement differente apres un crash situe entre son send
        // Kafka et la finalisation de son propre claim. Reexecuter ou bloquer
        // ici produirait davantage de risque que de rejouer le snapshot.
        if (!commandHash.equals(current.getCommandHash())) {
            log.warn("Commande redelivree avec un hash different apres resultat terminal: execLogId={}",
                    execLogId);
        }
        Outcome outcome = new Outcome(
                Boolean.TRUE.equals(current.getSuccess()),
                current.getLogOutput(),
                current.getErrorMessage(),
                current.getDurationMs());
        return Claim.terminal(
                current.getState() == PipelineExecutionClaim.State.SUCCESS
                        ? ClaimState.SUCCESS : ClaimState.FAILED,
                commandHash,
                current.getAttempts(),
                outcome);
    }

    public Lease heartbeat(Claim claim, String execLogId) {
        requireAcquired(claim);
        AtomicBoolean valid = new AtomicBoolean(true);
        long interval = Math.max(1, Math.min(heartbeatSeconds, Math.max(1, leaseSeconds / 3)));
        ScheduledFuture<?> task = scheduler.scheduleAtFixedRate(() -> {
            try {
                Instant now = Instant.now();
                Query query = ownedQuery(execLogId, claim);
                query.addCriteria(Criteria.where("leaseExpiresAt").gt(now));
                long modified = mongoTemplate.updateFirst(
                        query,
                        new Update().set("leaseExpiresAt", expiry(now)),
                        PipelineExecutionClaim.class).getModifiedCount();
                if (modified != 1) valid.set(false);
            } catch (Exception failure) {
                valid.set(false);
                log.error("Bail d'execution non renouvele pour execLogId={}: {}",
                        execLogId, failure.getMessage());
            }
        }, interval, interval, TimeUnit.SECONDS);
        return new Lease(valid, task);
    }

    /** Appele immediatement apres la fin du moteur, avant le statut Kafka final. */
    public void complete(String execLogId, Claim claim, Lease lease, Outcome outcome) {
        requireAcquired(claim);
        lease.assertValid(execLogId);
        PipelineExecutionClaim.State state = outcome.success()
                ? PipelineExecutionClaim.State.SUCCESS
                : PipelineExecutionClaim.State.FAILED;
        long modified = mongoTemplate.updateFirst(
                ownedQuery(execLogId, claim),
                new Update()
                        .set("state", state)
                        .set("completedAt", Instant.now())
                        .set("success", outcome.success())
                        .set("logOutput", bounded(outcome.logOutput()))
                        .set("errorMessage", outcome.errorMessage())
                        .set("durationMs", outcome.durationMs())
                        .unset("leaseExpiresAt")
                        .unset("failureReason"),
                PipelineExecutionClaim.class).getModifiedCount();
        if (modified != 1) throw new LeaseLostException(execLogId);
    }

    public void release(String execLogId, Claim claim, Throwable cause) {
        if (claim == null || claim.state() != ClaimState.ACQUIRED) return;
        mongoTemplate.updateFirst(
                ownedQuery(execLogId, claim),
                new Update()
                        .set("leaseExpiresAt", Instant.now())
                        .set("failureReason", rootMessage(cause)),
                PipelineExecutionClaim.class);
    }

    private Query ownedQuery(String execLogId, Claim claim) {
        return Query.query(Criteria.where("_id").is(execLogId)
                .and("state").is(PipelineExecutionClaim.State.IN_PROGRESS)
                .and("owner").is(owner)
                .and("fencingToken").is(claim.fencingToken())
                .and("commandHash").is(claim.commandHash()));
    }

    private Instant expiry(Instant now) {
        return now.plusSeconds(Math.max(2, leaseSeconds));
    }

    private static void requireAcquired(Claim claim) {
        if (claim == null || claim.state() != ClaimState.ACQUIRED
                || claim.fencingToken() == null) {
            throw new IllegalArgumentException("Claim d'execution acquis requis");
        }
    }

    private static String bounded(String value) {
        if (value == null || value.length() <= MAX_PERSISTED_LOG_CHARS) return value;
        return value.substring(value.length() - MAX_PERSISTED_LOG_CHARS);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 indisponible", failure);
        }
    }

    private static String rootMessage(Throwable cause) {
        if (cause == null) return "cause inconnue";
        Throwable current = cause;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String resolveOwner() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            host = "pipeline-consumer";
        }
        return host + ":" + UUID.randomUUID();
    }

    @PreDestroy
    void stopScheduler() {
        scheduler.shutdownNow();
    }

    public enum ClaimState { ACQUIRED, BUSY, SUCCESS, FAILED }

    public record Outcome(boolean success, String logOutput, String errorMessage, long durationMs) { }

    public record Claim(
            ClaimState state,
            String fencingToken,
            String commandHash,
            int attempt,
            Outcome outcome) {
        static Claim acquired(String token, String hash, int attempt) {
            return new Claim(ClaimState.ACQUIRED, token, hash, attempt, null);
        }

        static Claim busy(String hash, int attempt) {
            return new Claim(ClaimState.BUSY, null, hash, attempt, null);
        }

        static Claim terminal(ClaimState state, String hash, int attempt, Outcome outcome) {
            return new Claim(state, null, hash, attempt, outcome);
        }
    }

    public static final class Lease implements AutoCloseable {
        private final AtomicBoolean valid;
        private final ScheduledFuture<?> task;

        private Lease(AtomicBoolean valid, ScheduledFuture<?> task) {
            this.valid = valid;
            this.task = task;
        }

        void assertValid(String execLogId) {
            if (!valid.get()) throw new LeaseLostException(execLogId);
        }

        @Override
        public void close() {
            task.cancel(false);
        }
    }

    static final class LeaseLostException extends IllegalStateException {
        LeaseLostException(String execLogId) {
            super("Bail d'execution perdu pour execLogId=" + execLogId);
        }
    }

    static final class CommandPayloadMismatchException extends IllegalArgumentException {
        CommandPayloadMismatchException(String execLogId) {
            super("execLogId reutilise avec une commande differente: " + execLogId);
        }
    }
}
