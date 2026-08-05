package com.iol.etlplatform.service.scheduler;

import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExecutionWatchdogService {

    private final ExecutionLogRepository executionLogRepository;

    @Value("${app.execution-watchdog.enabled:true}")
    private boolean enabled;

    @Value("${app.execution-watchdog.heartbeat-timeout-seconds:120}")
    private long heartbeatTimeoutSeconds;

    @Value("${app.execution-watchdog.queue-timeout-seconds:900}")
    private long queueTimeoutSeconds;

    @Scheduled(fixedDelayString = "${app.execution-watchdog.scan-interval-ms:30000}")
    public void reconcileStaleExecutions() {
        if (!enabled) return;

        Instant now = Instant.now();
        Set<ExecutionLog> stale = new LinkedHashSet<>();
        stale.addAll(executionLogRepository.findRunningWithStaleHeartbeat(
                now.minusSeconds(Math.max(30L, heartbeatTimeoutSeconds))));
        stale.addAll(executionLogRepository.findQueuedRunningBefore(
                now.minusSeconds(Math.max(60L, queueTimeoutSeconds))));

        for (ExecutionLog execution : stale) {
            failStaleExecution(execution, now);
        }
    }

    private void failStaleExecution(ExecutionLog execution, Instant now) {
        if (execution.getStatus() != ExecutionStatus.RUNNING) return;

        String stage = execution.getCurrentStage();
        if (stage == null || stage.isBlank() || "QUEUED".equalsIgnoreCase(stage)) {
            stage = "WORKER_UNAVAILABLE";
        }
        long silenceSeconds = execution.getLastHeartbeatAt() == null
                ? Duration.between(execution.getStartTime(), now).getSeconds()
                : Duration.between(execution.getLastHeartbeatAt(), now).getSeconds();

        execution.setStatus(ExecutionStatus.FAILED);
        execution.setEndTime(now);
        execution.setFailedStage(stage);
        execution.setCurrentStage(stage);
        execution.setErrorMessage("Execution interrompue: aucun signal du worker depuis "
                + Math.max(0L, silenceSeconds) + " secondes.");
        execution.setLogOutput(appendBounded(execution.getLogOutput(), execution.getErrorMessage()));
        execution.setDetailedLogs(appendBounded(execution.getDetailedLogs(), execution.getErrorMessage()));

        LinkedHashMap<String, String> stages = execution.getStageStatuses() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(execution.getStageStatuses());
        stages.replaceAll((key, value) -> "RUNNING".equals(value) ? "FAILED" : value);
        stages.put(stage, "FAILED");
        execution.setStageStatuses(stages);
        executionLogRepository.save(execution);
        log.warn("Execution {} cloturee par le watchdog a l'etape {}", execution.getId(), stage);
    }

    private String appendBounded(String existing, String line) {
        String value = (existing == null ? "" : existing) + line + "\n";
        int maximum = 512_000;
        return value.length() <= maximum ? value : value.substring(value.length() - maximum);
    }
}
