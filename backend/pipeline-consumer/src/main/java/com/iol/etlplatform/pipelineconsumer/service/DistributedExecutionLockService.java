package com.iol.etlplatform.pipelineconsumer.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class DistributedExecutionLockService {

    private static final Logger log = LoggerFactory.getLogger(DistributedExecutionLockService.class);

    @Value("${app.execution-lock.mode:POSTGRES}")
    private String mode;

    @Value("${app.execution-lock.jdbc-url:jdbc:postgresql://localhost:5432/lakehouse}")
    private String jdbcUrl;

    @Value("${app.execution-lock.username:etl_user}")
    private String username;

    @Value("${app.execution-lock.password:etl_password}")
    private String password;

    @Value("${app.execution-lock.acquire-timeout-seconds:900}")
    private long acquireTimeoutSeconds;

    @Value("${app.execution-lock.poll-interval-millis:500}")
    private long pollIntervalMillis;

    private final ConcurrentHashMap<String, LocalLockRef> localLocks = new ConcurrentHashMap<>();

    public LockHandle acquire(String executionKey) throws Exception {
        if ("LOCAL".equalsIgnoreCase(mode)) {
            return acquireLocal(executionKey);
        }
        if (!"POSTGRES".equalsIgnoreCase(mode)) {
            throw new IllegalStateException("Mode de verrou distribué non supporté: " + mode);
        }
        return acquirePostgres(executionKey);
    }

    long advisoryLockId(String executionKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(executionKey.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.wrap(digest, 0, Long.BYTES).getLong();
        } catch (Exception e) {
            throw new IllegalStateException("Impossible de calculer l'identifiant du verrou.", e);
        }
    }

    private LockHandle acquirePostgres(String executionKey) throws Exception {
        Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
        long lockId = advisoryLockId(executionKey);
        Instant deadline = Instant.now().plusSeconds(Math.max(1, acquireTimeoutSeconds));
        try {
            while (Instant.now().isBefore(deadline)) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Attente du verrou interrompue.");
                }
                try (PreparedStatement statement =
                             connection.prepareStatement("select pg_try_advisory_lock(?)")) {
                    statement.setLong(1, lockId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next() && result.getBoolean(1)) {
                            log.info("Verrou distribué acquis: key={} lockId={}", executionKey, lockId);
                            return new PostgresLockHandle(connection, lockId, executionKey);
                        }
                    }
                }
                Thread.sleep(Math.max(50, pollIntervalMillis));
            }
            throw new IllegalStateException(
                    "Timeout après " + Duration.ofSeconds(acquireTimeoutSeconds)
                            + " pendant l'attente du verrou " + executionKey);
        } catch (Exception e) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
            throw e;
        }
    }

    private LockHandle acquireLocal(String executionKey) throws InterruptedException {
        LocalLockRef ref = localLocks.compute(executionKey, (ignored, current) -> {
            LocalLockRef result = current == null ? new LocalLockRef() : current;
            result.references.incrementAndGet();
            return result;
        });
        ref.lock.lockInterruptibly();
        return () -> {
            ref.lock.unlock();
            if (ref.references.decrementAndGet() == 0) {
                localLocks.remove(executionKey, ref);
            }
        };
    }

    public interface LockHandle extends AutoCloseable {
        @Override
        void close();
    }

    private static final class LocalLockRef {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final AtomicInteger references = new AtomicInteger();
    }

    private static final class PostgresLockHandle implements LockHandle {
        private final Connection connection;
        private final long lockId;
        private final String executionKey;
        private boolean closed;

        private PostgresLockHandle(Connection connection, long lockId, String executionKey) {
            this.connection = connection;
            this.lockId = lockId;
            this.executionKey = executionKey;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            try (PreparedStatement statement =
                         connection.prepareStatement("select pg_advisory_unlock(?)")) {
                statement.setLong(1, lockId);
                statement.execute();
                log.info("Verrou distribué libéré: key={} lockId={}", executionKey, lockId);
            } catch (Exception e) {
                log.warn("Impossible de libérer explicitement le verrou {}: {}", executionKey, e.getMessage());
            } finally {
                try {
                    connection.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
