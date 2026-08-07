package com.iol.etlplatform.repository;

import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;
import java.time.Instant;

public interface ExecutionLogRepository extends MongoRepository<ExecutionLog, String> {
    List<ExecutionLog> findAllByOrderByStartTimeDesc();

    List<ExecutionLog> findByWorkflowIdOrderByStartTimeDesc(String workflowId);

    /**
     * Find the most recent successful execution for incremental loading watermark tracking.
     * Returns the latest ExecutionLog with status SUCCESS, ordered by endTime descending.
     */
    Optional<ExecutionLog> findFirstByWorkflowIdAndStatusOrderByEndTimeDesc(String workflowId, ExecutionStatus status);

    /**
     * Vrai si une execution du workflow est deja active. Sert a refuser une
     * seconde soumission simultanee, qui ecrirait dans les memes tables Bronze,
     * Silver et Gold. Les executions bloquees sont liberees par
     * ExecutionWatchdogService, qui les bascule en FAILED.
     */
    boolean existsByWorkflowIdAndStatus(String workflowId, ExecutionStatus status);

    @Query("{ 'status': 'RUNNING', 'last_heartbeat_at': { $lt: ?0 } }")
    List<ExecutionLog> findRunningWithStaleHeartbeat(Instant cutoff);

    @Query("{ 'status': 'RUNNING', $or: [ { 'last_heartbeat_at': null }, { 'last_heartbeat_at': { $exists: false } } ], 'startTime': { $lt: ?0 } }")
    List<ExecutionLog> findQueuedRunningBefore(Instant cutoff);

    @Query(value = "{ 'execution_params.direction': 'INBOUND' }", sort = "{ 'startTime': -1 }")
    List<ExecutionLog> findInteropExecutionsOrderByStartTimeDesc();

    @Query(value = "{ 'execution_params.direction': 'INBOUND', 'execution_params.correlationId': ?0 }", sort = "{ 'startTime': -1 }")
    List<ExecutionLog> findInteropExecutionsByCorrelationId(String correlationId);
}
