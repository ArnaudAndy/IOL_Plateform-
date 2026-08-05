package com.iol.etlplatform.dto.log;

import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.entity.enums.WorkflowDirection;
import com.iol.etlplatform.entity.ExecutionLog.SourceMetric;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.List;

@Data
public class ExecutionLogDto {
    private String id;
    private String workflowId;
    private String workflowName;
    private WorkflowDirection direction;
    private String correlationId;
    private Instant startTime;
    private Instant endTime;
    private String currentStage;
    private Instant lastHeartbeatAt;
    private Long durationMs;
    private ExecutionStatus status;
    private String logOutput;
    private Long ingestionDurationMs;
    private Long rowsExtracted;
    private Long cleaningDurationMs;
    private Long rowsCleaned;
    private Long transformationDurationMs;
    private Long rowsTransformed;
    private String metadataFilePath;
    private String errorMessage;
    private String failedStage;
    private Integer warningCount;
    private String triggeredBy;
    private Map<String, String> executionParams;
    private String detailedLogs;
    private List<SourceMetric> sourceMetrics;
    private Map<String, String> stageStatuses;
}
