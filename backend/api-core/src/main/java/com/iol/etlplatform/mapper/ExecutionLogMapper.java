package com.iol.etlplatform.mapper;

import com.iol.etlplatform.dto.log.ExecutionLogDto;
import com.iol.etlplatform.entity.ExecutionLog;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import com.iol.etlplatform.entity.enums.WorkflowDirection;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ExecutionLogMapper {
    @Mapping(target = "durationMs", expression = "java(durationMs(executionLog))")
    @Mapping(target = "direction", expression = "java(direction(executionLog))")
    @Mapping(target = "correlationId", expression = "java(correlationId(executionLog))")
    ExecutionLogDto toDto(ExecutionLog executionLog);
    List<ExecutionLogDto> toDtoList(List<ExecutionLog> executionLogs);

    default Long durationMs(ExecutionLog log) {
        return log.getStartTime() != null && log.getEndTime() != null
                ? java.time.Duration.between(log.getStartTime(), log.getEndTime()).toMillis()
                : null;
    }

    default WorkflowDirection direction(ExecutionLog log) {
        if (log.getDirection() != null) return log.getDirection();
        String value = log.getExecutionParams() != null ? log.getExecutionParams().get("direction") : null;
        if (value == null || value.isBlank()) return WorkflowDirection.INTERNAL;
        try {
            return WorkflowDirection.valueOf(value.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return WorkflowDirection.INTERNAL;
        }
    }

    default String correlationId(ExecutionLog log) {
        if (log.getCorrelationId() != null && !log.getCorrelationId().isBlank()) return log.getCorrelationId();
        return log.getExecutionParams() != null ? log.getExecutionParams().get("correlationId") : null;
    }
}
