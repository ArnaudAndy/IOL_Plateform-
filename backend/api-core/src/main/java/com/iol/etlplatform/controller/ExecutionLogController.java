package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.common.ApiResponse;
import com.iol.etlplatform.dto.log.ExecutionLogDto;
import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.mapper.ExecutionLogMapper;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import com.iol.etlplatform.repository.WorkflowConfigRepository;
import com.iol.etlplatform.exception.BadRequestException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/logs")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Execution Logs", description = "Historique d'exécution des workflows avec détails par phase")
@SecurityRequirement(name = "bearerAuth")
public class ExecutionLogController {

    private final ExecutionLogRepository executionLogRepository;
    private final ExecutionLogMapper executionLogMapper;
    private final WorkflowConfigRepository workflowConfigRepository;

    /**
     * Récupérer toutes les exécutions pour le monitoring global
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Récupérer toutes les exécutions")
    public ResponseEntity<ApiResponse<List<ExecutionLogDto>>> findAll() {
        List<ExecutionLogDto> logs = executionLogMapper.toDtoList(
                visibleLogs(executionLogRepository.findAllByOrderByStartTimeDesc()));
        return ResponseEntity.ok(new ApiResponse<>("Historique global des executions.", logs));
    }

    @GetMapping("/interop")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Récupérer les exécutions interop INBOUND")
    public ResponseEntity<ApiResponse<List<ExecutionLogDto>>> findInteropExecutions() {
        List<ExecutionLogDto> logs = executionLogMapper.toDtoList(visibleLogs(
                executionLogRepository.findInteropExecutionsOrderByStartTimeDesc()));
        return ResponseEntity.ok(new ApiResponse<>("Historique interop INBOUND.", logs));
    }

    @GetMapping("/interop/correlation/{correlationId}")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Récupérer les exécutions interop par correlationId")
    public ResponseEntity<ApiResponse<List<ExecutionLogDto>>> findInteropByCorrelationId(
            @Parameter(description = "Identifiant de corrélation", required = true)
            @PathVariable String correlationId) {
        List<ExecutionLogDto> logs = executionLogMapper.toDtoList(visibleLogs(
                executionLogRepository.findInteropExecutionsByCorrelationId(correlationId)));
        return ResponseEntity.ok(new ApiResponse<>("Historique interop pour correlationId.", logs));
    }

    @GetMapping("/interop/summary")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Résumé des exécutions interop INBOUND")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getInteropSummary() {
        List<ExecutionLog> logs = visibleLogs(executionLogRepository.findInteropExecutionsOrderByStartTimeDesc());
        long total = logs.size();
        long running = logs.stream().filter(l -> l.getStatus() == com.iol.etlplatform.entity.enums.ExecutionStatus.RUNNING).count();
        long success = logs.stream().filter(l -> l.getStatus() == com.iol.etlplatform.entity.enums.ExecutionStatus.SUCCESS).count();
        long failed = logs.stream().filter(l -> l.getStatus() == com.iol.etlplatform.entity.enums.ExecutionStatus.FAILED).count();
        long last24h = logs.stream().filter(l -> l.getStartTime() != null
                && l.getStartTime().isAfter(java.time.Instant.now().minus(java.time.Duration.ofHours(24)))).count();

        return ResponseEntity.ok(new ApiResponse<>(
                "Résumé interop INBOUND.",
                Map.of(
                        "totalInbound", total,
                        "running", running,
                        "success", success,
                        "failed", failed,
                        "dlqCount", failed,
                        "last24h", last24h
                )
        ));
    }

    /**
     * Récupérer l'historique d'exécution d'un workflow
     */
        @GetMapping("/{workflowId}")
        @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Récupérer l'historique d'exécution")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Historique récupéré"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Non authentifié"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Workflow non trouvé")
    })
    public ResponseEntity<ApiResponse<List<ExecutionLogDto>>> findByWorkflowId(
            @Parameter(description = "ID du workflow", required = true)
            @PathVariable String workflowId) {
        log.info("Récupération historique workflow: {}", workflowId);
        assertWorkflowAccess(workflowId);

        List<ExecutionLogDto> logs = executionLogMapper.toDtoList(
                executionLogRepository.findByWorkflowIdOrderByStartTimeDesc(workflowId)
        );
        return ResponseEntity.ok(new ApiResponse<>("Historique des executions.", logs));
    }

    /**
     * Récupérer les détails complets d'une exécution
     */
        @GetMapping("/details/{executionId}")
        @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(
            summary = "Récupérer les détails complets d'une exécution",
            description = "Affiche tous les détails: durées par phase, métriques par source, logs détaillés"
    )
    public ResponseEntity<ApiResponse<ExecutionLog>> getExecutionDetails(
            @Parameter(description = "ID de l'exécution", required = true)
            @PathVariable String executionId) {
        log.info("Récupération détails exécution: {}", executionId);

        ExecutionLog log = getAccessibleExecution(executionId);

        return ResponseEntity.ok(new ApiResponse<>("Détails de l'exécution", log));
    }

    /**
     * Récupérer les métriques de performance
     */
    @GetMapping("/performance/{workflowId}")
        @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(
            summary = "Récupérer les métriques de performance",
            description = "Affiche les statistiques: durées moyennes par phase, taux de réussite"
    )
    public ResponseEntity<ApiResponse<Object>> getPerformanceMetrics(
            @Parameter(description = "ID du workflow", required = true)
            @PathVariable String workflowId) {
        log.info("Calcul métriques performance pour workflow: {}", workflowId);
        assertWorkflowAccess(workflowId);

        List<ExecutionLog> logs = executionLogRepository.findByWorkflowIdOrderByStartTimeDesc(workflowId);

        // Calcul des statistiques
        long totalExecutions = logs.size();
        long successCount = logs.stream().filter(l -> l.getStatus() == com.iol.etlplatform.entity.enums.ExecutionStatus.SUCCESS).count();
        double successRate = totalExecutions > 0 ? (double) successCount / totalExecutions * 100 : 0;

        double avgIngestionDuration = logs.stream()
                .filter(l -> l.getIngestionDurationMs() != null)
                .mapToLong(ExecutionLog::getIngestionDurationMs)
                .average()
                .orElse(0.0);

        double avgTransformationDuration = logs.stream()
                .filter(l -> l.getTransformationDurationMs() != null)
                .mapToLong(ExecutionLog::getTransformationDurationMs)
                .average()
                .orElse(0.0);

        long totalRowsProcessed = logs.stream()
                .filter(l -> l.getRowsExtracted() != null)
                .mapToLong(ExecutionLog::getRowsExtracted)
                .sum();

        return ResponseEntity.ok(new ApiResponse<>(
                "Métriques de performance",
                Map.of(
                        "totalExecutions", totalExecutions,
                        "successCount", successCount,
                        "successRate", String.format("%.1f%%", successRate),
                        "avgIngestionDurationMs", String.format("%.0f", avgIngestionDuration),
                        "avgTransformationDurationMs", String.format("%.0f", avgTransformationDuration),
                        "totalRowsProcessed", totalRowsProcessed
                )
        ));
    }

    /**
     * Récupérer les sources par exécution
     */
    @GetMapping("/sources/{executionId}")
        @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(
            summary = "Récupérer les métriques par source",
            description = "Affiche les détails de chaque source: rows lues/nettoyées/rejetées, durée"
    )
    public ResponseEntity<ApiResponse<List<ExecutionLog.SourceMetric>>> getSourceMetrics(
            @Parameter(description = "ID de l'exécution", required = true)
            @PathVariable String executionId) {
        log.info("Récupération métriques sources pour exécution: {}", executionId);

        ExecutionLog log = getAccessibleExecution(executionId);

        return ResponseEntity.ok(new ApiResponse<>(
                "Métriques par source",
                log.getSourceMetrics() != null ? log.getSourceMetrics() : List.of()
        ));
    }

    private List<ExecutionLog> visibleLogs(List<ExecutionLog> logs) {
        if (isCurrentUserAdmin()) {
            return logs;
        }
        String email = currentUserEmail();
        java.util.Set<String> workflowIds = workflowConfigRepository.findByCreatedBy(email).stream()
                .map(com.iol.etlplatform.entity.WorkflowConfig::getId)
                .collect(java.util.stream.Collectors.toSet());
        return logs.stream()
                .filter(item -> item.getWorkflowId() != null && workflowIds.contains(item.getWorkflowId()))
                .toList();
    }

    private ExecutionLog getAccessibleExecution(String executionId) {
        ExecutionLog execution = executionLogRepository.findById(executionId)
                .orElseThrow(() -> new BadRequestException("Execution introuvable: " + executionId));
        assertWorkflowAccess(execution.getWorkflowId());
        return execution;
    }

    private void assertWorkflowAccess(String workflowId) {
        if (isCurrentUserAdmin()) {
            return;
        }
        boolean owned = workflowConfigRepository.findById(workflowId)
                .map(workflow -> currentUserEmail().equals(workflow.getCreatedBy()))
                .orElse(false);
        if (!owned) {
            throw new BadRequestException("Acces refuse a cette execution.");
        }
    }

    private boolean isCurrentUserAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
    }

    private String currentUserEmail() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : "anonymous";
    }
}
