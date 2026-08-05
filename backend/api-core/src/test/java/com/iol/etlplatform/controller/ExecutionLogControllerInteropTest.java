package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.common.ApiResponse;
import com.iol.etlplatform.dto.log.ExecutionLogDto;
import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.mapper.ExecutionLogMapper;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import com.iol.etlplatform.repository.WorkflowConfigRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExecutionLogControllerInteropTest {

    private final ExecutionLogRepository repository = mock(ExecutionLogRepository.class);
    private final ExecutionLogMapper mapper = mock(ExecutionLogMapper.class);
    private final WorkflowConfigRepository workflowRepository = mock(WorkflowConfigRepository.class);
    private final ExecutionLogController controller = new ExecutionLogController(repository, mapper, workflowRepository);

    @Test
    void findInteropExecutionsUsesDedicatedRepositoryQuery() {
        ExecutionLog log = new ExecutionLog();
        log.setId("log_1");
        log.setExecutionParams(Map.of("direction", "INBOUND", "correlationId", "corr-1"));
        ExecutionLogDto dto = new ExecutionLogDto();
        dto.setId("log_1");
        dto.setExecutionParams(log.getExecutionParams());

        when(repository.findInteropExecutionsOrderByStartTimeDesc()).thenReturn(List.of(log));
        when(mapper.toDtoList(List.of(log))).thenReturn(List.of(dto));

        ResponseEntity<ApiResponse<List<ExecutionLogDto>>> response = controller.findInteropExecutions();

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().getData().size());
        assertEquals("corr-1", response.getBody().getData().get(0).getExecutionParams().get("correlationId"));
        verify(repository).findInteropExecutionsOrderByStartTimeDesc();
    }

    @Test
    void interopSummaryCountsStatuses() {
        ExecutionLog running = new ExecutionLog();
        running.setStatus(ExecutionStatus.RUNNING);
        ExecutionLog success = new ExecutionLog();
        success.setStatus(ExecutionStatus.SUCCESS);
        ExecutionLog failed = new ExecutionLog();
        failed.setStatus(ExecutionStatus.FAILED);

        when(repository.findInteropExecutionsOrderByStartTimeDesc()).thenReturn(List.of(running, success, failed));

        ResponseEntity<ApiResponse<Map<String, Object>>> response = controller.getInteropSummary();

        assertNotNull(response.getBody());
        Map<String, Object> data = response.getBody().getData();
        assertEquals(3L, data.get("totalInbound"));
        assertEquals(1L, data.get("running"));
        assertEquals(1L, data.get("success"));
        assertEquals(1L, data.get("failed"));
    }
}
