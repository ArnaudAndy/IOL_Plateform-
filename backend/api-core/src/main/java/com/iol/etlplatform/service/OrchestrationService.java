package com.iol.etlplatform.service;

import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.exception.ConflictException;
import com.iol.etlplatform.exception.ResourceNotFoundException;
import com.iol.etlplatform.exception.BadRequestException;
import com.iol.etlplatform.kafka.TransportOrderPublisher;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import com.iol.etlplatform.repository.WorkflowConfigRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Rôle de api-core dans l'exécution d'un pipeline :
 *
 *   1. Enregistre un log RUNNING dans MongoDB (traçabilité immédiate)
 *   2. Publie un ordre minimal vers le source-gateway, hors du thread HTTP
 *   3. C'est tout — le gateway transporte, puis le consumer execute
 *
 * Le statut SUCCESS/FAILED est mis à jour par le pipeline-consumer
 * via le topic iol.pipeline.status → KafkaStatusListenerService.
 *
 * ─────────────────────────────────────────────────────────────────────────
 *  POURQUOI LA SOUMISSION EST ASYNCHRONE
 * ─────────────────────────────────────────────────────────────────────────
 *  L'extraction peut durer plusieurs minutes. api-core ne l'execute plus et ne
 *  possede plus les credentials source au moment du lancement : il publie un
 *  ordre contenant seulement les identifiants metier necessaires au gateway.
 *
 *   - Nginx coupait `/api/` à 120 s, donc l'utilisateur recevait un 504 alors
 *     que le transport se poursuivait et que le workflow s'exécutait vraiment ;
 *   - une relance après ce faux échec produisait une seconde extraction, avec
 *     un nouveau journal d'exécution que la déduplication par empreinte ne
 *     rattrapait pas ;
 *   - les threads Tomcat étaient consommés par les transports, jusqu'à figer
 *     le portail d'un seul coup une fois les 200 threads pris.
 *
 *  La soumission rend donc la main immédiatement avec un journal en QUEUED.
 *  Le suivi passe par ce journal et le topic de statut, qui existaient déjà.
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
    private final TransportOrderPublisher transportOrderPublisher;

    @Qualifier("pipelineExecutionExecutor")
    private final Executor pipelineExecutionExecutor;

    /**
     * Soumet un pipeline à l'exécution et rend la main immédiatement.
     *
     * Appelé par :
     *   - POST /api/orchestrator/run/{id}  (déclenchement manuel)
     *   - PipelineSchedulerService          (déclenchement cron automatique)
     *
     * @return le journal d'exécution en QUEUED. Il n'est PAS terminal : le
     *         transport et la publication se poursuivent en arrière-plan.
     * @throws ConflictException si une exécution est déjà en cours
     * @throws java.util.concurrent.RejectedExecutionException si la capacité
     *         d'exécution est saturée
     */
    public ExecutionLog runWorkflow(String workflowId) {
        WorkflowConfig workflow = workflowConfigRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow introuvable: " + workflowId));
        assertCanRun(workflow);
        assertNoActiveExecution(workflow);

        String triggeredBy = currentTrigger(workflow);
        ExecutionLog execLog = ExecutionLog.builder()
                .workflowId(workflowId)
                .workflowName(workflow.getWorkflowName())
                .direction(workflow.getDirection())
                .startTime(Instant.now())
                .status(ExecutionStatus.RUNNING)
                .currentStage("QUEUED")
                .stageStatuses(initialStageStatuses())
                .triggeredBy(triggeredBy)
                .logOutput("Pipeline '" + workflow.getWorkflowName()
                        + "' accepte; ordre de transport en attente de prise en charge.\n")
                .build();
        execLog = executionLogRepository.save(execLog);

        String execLogId = execLog.getId();
        try {
            pipelineExecutionExecutor.execute(
                    () -> publishTransportOrder(workflowId, execLogId, triggeredBy));
        } catch (RejectedExecutionException saturated) {
            markSubmissionFailed(execLog, "Capacite d'execution saturee: " + saturated.getMessage());
            throw saturated;
        }

        log.info("Pipeline '{}' accepté. ExecutionLog id={}", workflow.getWorkflowName(), execLogId);
        return execLog;
    }

    /**
     * Publication de l'ordre de transport. Toute exception est consignee dans le
     * journal; la lecture de la source appartient exclusivement au gateway.
     */
    private void publishTransportOrder(String workflowId, String execLogId, String requestedBy) {
        try {
            WorkflowConfig workflow = workflowConfigRepository.findById(workflowId)
                    .orElseThrow(() -> new ResourceNotFoundException("Workflow introuvable: " + workflowId));
            String kafkaResult = transportOrderPublisher.publishTransportRequested(
                    workflow, execLogId, requestedBy);

            executionLogRepository.findById(execLogId).ifPresent(current -> {
                current.setLogOutput(current.getLogOutput() + kafkaResult + "\n");
                executionLogRepository.save(current);
            });
            log.info("Ordre de transport du pipeline '{}' soumis a Kafka. ExecutionLog id={}",
                     workflow.getWorkflowName(), execLogId);
        } catch (Exception error) {
            log.error("Echec de publication de l'ordre pour l'execution {}: {}",
                    execLogId, rootMessage(error), error);
            executionLogRepository.findById(execLogId).ifPresent(current ->
                    markSubmissionFailed(current,
                            "L'ordre de transport n'a pas pu etre publie: " + rootMessage(error)));
        }
    }

    private void markSubmissionFailed(ExecutionLog execLog, String message) {
        execLog.setStatus(ExecutionStatus.FAILED);
        execLog.setEndTime(Instant.now());
        execLog.setCurrentStage("SUBMISSION");
        execLog.setFailedStage("SUBMISSION");
        execLog.setErrorMessage(message);
        execLog.setLogOutput(execLog.getLogOutput() + message + "\n");
        execLog.setStageStatuses(Map.of("SUBMISSION", "FAILED"));
        executionLogRepository.save(execLog);
    }

    /**
     * Refuse une seconde exécution simultanée du même workflow.
     *
     * Deux exécutions concurrentes écriraient dans les mêmes tables Bronze,
     * Silver et Gold. Le consumer prend déjà un verrou distribué par workflow ;
     * refuser dès la soumission donne un message clair au lieu d'une attente
     * silencieuse, et neutralise la relance après un faux échec.
     *
     * Les exécutions bloquées sont libérées par ExecutionWatchdogService, qui
     * les bascule en FAILED au-delà de ses délais de heartbeat et de file.
     */
    private void assertNoActiveExecution(WorkflowConfig workflow) {
        if (executionLogRepository.existsByWorkflowIdAndStatus(workflow.getId(), ExecutionStatus.RUNNING)) {
            throw new ConflictException(
                    "Une execution est deja en cours pour le workflow '" + workflow.getWorkflowName()
                            + "'. Attendez sa fin avant d'en lancer une nouvelle.");
        }
    }

    private Map<String, String> initialStageStatuses() {
        Map<String, String> statuses = new LinkedHashMap<>();
        statuses.put("TRANSPORT", "NOT_RUN");
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
