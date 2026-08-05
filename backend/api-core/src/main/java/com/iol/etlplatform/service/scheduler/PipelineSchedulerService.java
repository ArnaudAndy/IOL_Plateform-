package com.iol.etlplatform.service.scheduler;

import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.repository.WorkflowConfigRepository;
import com.iol.etlplatform.service.OrchestrationService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;



@Service
@RequiredArgsConstructor
public class PipelineSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(PipelineSchedulerService.class);

    private final WorkflowConfigRepository workflowConfigRepository;
    private final OrchestrationService orchestrationService;
    private final TaskScheduler taskScheduler;

    /** Map workflowId → tâche planifiée, pour mise à jour/annulation dynamique */
    private final Map<String, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

    /**
     * Au démarrage : planifie tous les workflows actifs avec un cron défini.
     */
    @PostConstruct
    public void loadSchedulesOnStartup() {
        try {
            List<WorkflowConfig> active = workflowConfigRepository.findAll().stream()
                    .filter(WorkflowConfig::isActive)
                    .toList();

            int scheduled = 0;
            for (WorkflowConfig wf : active) {
                String cron = extractCron(wf);
                if (cron != null) {
                    schedule(wf, cron);
                    scheduled++;
                }
            }
            log.info("[SCHEDULER] {} workflow(s) planifié(s) au démarrage.", scheduled);
        } catch (Exception e) {
            log.warn("[SCHEDULER] Impossible de charger les planifications au démarrage (infrastructure indisponible): {}", e.getMessage());
        }
    }

    /**
     * Planifie ou replanifie un workflow après création/modification.
     */
    public void reschedule(WorkflowConfig workflow) {
        cancel(workflow.getId());

        if (!workflow.isActive()) {
            log.info("[SCHEDULER] Workflow {} inactif — aucune planification.", workflow.getId());
            return;
        }

        String cron = extractCron(workflow);
        if (cron != null) {
            schedule(workflow, cron);
        } else {
            log.info("[SCHEDULER] Workflow {} sans cron — exécution manuelle uniquement.", workflow.getId());
        }
    }

    /**
     * Annule la planification (ex: suppression du workflow).
     */
    public void unschedule(String workflowId) {
        cancel(workflowId);
        log.info("[SCHEDULER] Planification annulée pour workflow {}", workflowId);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void schedule(WorkflowConfig workflow, String cron) {
        String springCron = toSpringCron(cron);
        String id = workflow.getId();

        try {
            CronTrigger trigger = new CronTrigger(springCron, TimeZone.getTimeZone("UTC"));
            ScheduledFuture<?> future = taskScheduler.schedule(
                    () -> runWorkflow(id, workflow.getWorkflowName()), trigger);

            scheduledTasks.put(id, future);
            log.info("[SCHEDULER] Workflow '{}' planifié — cron='{}'", workflow.getWorkflowName(), cron);
        } catch (Exception e) {
            log.error("[SCHEDULER] Cron invalide '{}' pour workflow {}: {}", cron, id, e.getMessage());
        }
    }

    private void cancel(String workflowId) {
        ScheduledFuture<?> existing = scheduledTasks.remove(workflowId);
        if (existing != null) existing.cancel(false);
    }

    private void runWorkflow(String workflowId, String name) {
        log.info("[SCHEDULER] Déclenchement automatique — workflow '{}'", name);
        try {
            orchestrationService.runWorkflow(workflowId);
        } catch (Exception e) {
            log.error("[SCHEDULER] Échec exécution automatique '{}': {}", name, e.getMessage());
        }
    }

    /**
     * Extrait le cron depuis le champ schedule.
     * Supporte deux formats :
     *   1) schedule.cron = "0 2 * * *"  (cron Unix explicite)
     *   2) schedule.enabled=true + schedule.frequency=DAILY + schedule.time=02:00
     */
    private String extractCron(WorkflowConfig wf) {
        Map<String, Object> schedule = wf.getSchedule();
        if (schedule == null || schedule.isEmpty()) return null;

        Object enabled = schedule.get("enabled");
        if (Boolean.FALSE.equals(enabled) || "false".equalsIgnoreCase(String.valueOf(enabled))) return null;

        // Format 1 : cron explicite
        String cronRaw = str(schedule.get("cron"));
        if (!cronRaw.isBlank()) return cronRaw;

        // Format 2 : frequency + time
        String frequency = str(schedule.get("frequency"));
        if (frequency.isBlank()) return null;

        String time   = str(schedule.get("time"));
        String[] hm   = time.contains(":") ? time.split(":") : new String[]{"0", "0"};
        String hour   = hm.length > 0 ? hm[0] : "0";
        String minute = hm.length > 1 ? hm[1] : "0";

        return switch (frequency.toUpperCase()) {
            case "HOURLY"  -> "0 * * * *";
            case "DAILY"   -> minute + " " + hour + " * * *";
            case "WEEKLY"  -> minute + " " + hour + " * * 1";
            case "MONTHLY" -> minute + " " + hour + " 1 * *";
            default -> null;
        };
    }

    /**
     * Convertit cron Unix 5 champs → Spring 6 champs (ajoute "0" pour les secondes).
     * "0 2 * * *" → "0 0 2 * * *"
     */
    private String toSpringCron(String cron) {
        String t = cron.trim();
        return t.split("\\s+").length == 5 ? "0 " + t : t;
    }

    private String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
}
