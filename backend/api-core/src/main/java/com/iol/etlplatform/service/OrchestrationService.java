package com.iol.etlplatform.service;

import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.exception.ResourceNotFoundException;
import com.iol.etlplatform.exception.BadRequestException;
import com.iol.etlplatform.kafka.KafkaPipelineEventService;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import com.iol.etlplatform.repository.WorkflowConfigRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rôle de api-core dans l'exécution d'un pipeline :
 *
 *   1. Enregistre un log RUNNING dans MongoDB (traçabilité immédiate)
 *   2. Publie l'événement PIPELINE_EXECUTION_REQUESTED dans Kafka
 *   3. C'est tout — Apache Hop (via pipeline-consumer) fait le reste
 *
 * Le statut SUCCESS/FAILED est mis à jour par le pipeline-consumer
 * via le topic iol.pipeline.status → KafkaStatusListenerService.
 *
 * Dépendance directe sur WorkflowConfigRepository (pas WorkflowService)
 * pour éviter une dépendance circulaire avec WorkflowService.
 */
@Service
@RequiredArgsConstructor
public class OrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationService.class);

    private final WorkflowConfigRepository workflowConfigRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final KafkaPipelineEventService kafkaPipelineEventService;

    /**
     * Déclenche l'exécution d'un pipeline.
     * Appelé par :
     *   - POST /api/orchestrator/run/{id}  (déclenchement manuel)
     *   - PipelineSchedulerService          (déclenchement cron automatique)
     */
    public ExecutionLog runWorkflow(String workflowId) {
        WorkflowConfig workflow = workflowConfigRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow introuvable: " + workflowId));
        assertCanRun(workflow);

        ExecutionLog execLog = ExecutionLog.builder()
                .workflowId(workflowId)
                .workflowName(workflow.getWorkflowName())
                .direction(workflow.getDirection())
                .startTime(Instant.now())
                .status(ExecutionStatus.RUNNING)
                .currentStage("QUEUED")
                .stageStatuses(initialStageStatuses())
                .triggeredBy(currentTrigger(workflow))
                .logOutput("Pipeline '" + workflow.getWorkflowName() + "' soumis à Hop via Kafka.\n")
                .build();
        execLog = executionLogRepository.save(execLog);

        String kafkaResult;
        try {
            kafkaResult = kafkaPipelineEventService.publishExecutionRequested(workflow, execLog.getId());
        } catch (Exception error) {
            execLog.setStatus(ExecutionStatus.FAILED);
            execLog.setEndTime(Instant.now());
            execLog.setCurrentStage("SUBMISSION");
            execLog.setFailedStage("SUBMISSION");
            execLog.setErrorMessage("La commande d'execution n'a pas pu etre publiee: " + rootMessage(error));
            execLog.setLogOutput(execLog.getLogOutput() + execLog.getErrorMessage() + "\n");
            execLog.setStageStatuses(Map.of("SUBMISSION", "FAILED"));
            executionLogRepository.save(execLog);
            if (error instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(execLog.getErrorMessage(), error);
        }

        execLog.setLogOutput(execLog.getLogOutput() + kafkaResult + "\n");
        executionLogRepository.save(execLog);

        log.info("Pipeline '{}' soumis. ExecutionLog id={}", workflow.getWorkflowName(), execLog.getId());
        return execLog;
    }

    private Map<String, String> initialStageStatuses() {
        Map<String, String> statuses = new LinkedHashMap<>();
        statuses.put("PREPARATION", "NOT_RUN");
        statuses.put("BRONZE", "NOT_RUN");
        statuses.put("SILVER", "NOT_RUN");
        statuses.put("GOLD", "NOT_RUN");
        statuses.put("DESTINATION", "NOT_RUN");
        return statuses;
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private String currentTrigger(WorkflowConfig workflow) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            return authentication.getName();
        }
        return workflow.getCreatedBy();
    }

    private void assertCanRun(WorkflowConfig workflow) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            return;
        }
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()));
        if (!admin && !authentication.getName().equals(workflow.getCreatedBy())) {
            throw new BadRequestException("Acces refuse a ce workflow.");
        }
    }
}
