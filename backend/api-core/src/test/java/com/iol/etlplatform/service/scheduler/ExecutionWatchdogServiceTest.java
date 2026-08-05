package com.iol.etlplatform.service.scheduler;

import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ExecutionWatchdogServiceTest {

    @Test
    void staleHeartbeatClosesRunningExecutionAtItsActualStage() {
        ExecutionLogRepository repository = mock(ExecutionLogRepository.class);
        ExecutionLog execution = new ExecutionLog();
        execution.setId("log-stale");
        execution.setStatus(ExecutionStatus.RUNNING);
        execution.setStartTime(Instant.now().minusSeconds(600));
        execution.setLastHeartbeatAt(Instant.now().minusSeconds(300));
        execution.setCurrentStage("SILVER");
        execution.setStageStatuses(new LinkedHashMap<>() {{
            put("BRONZE", "SUCCESS");
            put("SILVER", "RUNNING");
            put("GOLD", "NOT_RUN");
        }});
        when(repository.findRunningWithStaleHeartbeat(any())).thenReturn(List.of(execution));
        when(repository.findQueuedRunningBefore(any())).thenReturn(List.of());

        ExecutionWatchdogService service = new ExecutionWatchdogService(repository);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "heartbeatTimeoutSeconds", 120L);
        ReflectionTestUtils.setField(service, "queueTimeoutSeconds", 900L);

        service.reconcileStaleExecutions();

        assertEquals(ExecutionStatus.FAILED, execution.getStatus());
        assertEquals("SILVER", execution.getFailedStage());
        assertEquals("FAILED", execution.getStageStatuses().get("SILVER"));
        assertEquals("SUCCESS", execution.getStageStatuses().get("BRONZE"));
        assertNotNull(execution.getEndTime());
        assertTrue(execution.getErrorMessage().contains("aucun signal du worker"));
        verify(repository).save(execution);
    }
}
