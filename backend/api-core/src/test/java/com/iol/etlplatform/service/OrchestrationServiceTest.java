package com.iol.etlplatform.service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.exception.ConflictException;
import com.iol.etlplatform.kafka.KafkaPipelineEventService;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import com.iol.etlplatform.repository.WorkflowConfigRepository;

/**
 * Verifie le contrat de soumission asynchrone.
 *
 * Le transport ne doit plus s'executer sur le thread appelant: c'est ce qui
 * provoquait le 504 de Nginx a 120 s sur un workflow qui reussissait pourtant,
 * puis la double execution lors de la relance.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrchestrationServiceTest {

    @Mock
    private WorkflowConfigRepository workflowConfigRepository;

    @Mock
    private ExecutionLogRepository executionLogRepository;

    @Mock
    private KafkaPipelineEventService kafkaPipelineEventService;

    private WorkflowConfig workflow;

    @BeforeEach
    void setUp() {
        workflow = new WorkflowConfig();
        workflow.setId("wf-1");
        workflow.setWorkflowName("Patients");
        workflow.setActive(true);

        when(workflowConfigRepository.findById("wf-1")).thenReturn(Optional.of(workflow));
        when(executionLogRepository.save(any(ExecutionLog.class)))
                .thenAnswer(invocation -> {
                    ExecutionLog saved = invocation.getArgument(0);
                    if (saved.getId() == null) {
                        saved.setId("exec-1");
                    }
                    return saved;
                });
        when(executionLogRepository.findById("exec-1"))
                .thenAnswer(invocation -> Optional.of(ExecutionLog.builder()
                        .id("exec-1").workflowId("wf-1").logOutput("").build()));
    }

    private OrchestrationService serviceWith(Executor executor) {
        return new OrchestrationService(
                workflowConfigRepository, executionLogRepository, kafkaPipelineEventService, executor);
    }

    /** Un executor qui ne lance rien: prouve que la soumission ne transporte pas. */
    private static final Executor NEVER_RUNS = task -> { };

    @Test
    void laSoumissionRendLaMainSansTransporter() {
        ExecutionLog result = serviceWith(NEVER_RUNS).runWorkflow("wf-1");

        assertEquals("QUEUED", result.getCurrentStage());
        assertEquals(ExecutionStatus.RUNNING, result.getStatus());
        // Le point essentiel: aucune publication n'a eu lieu sur le thread appelant.
        verify(kafkaPipelineEventService, never()).publishExecutionRequested(any(), anyString());
    }

    @Test
    void leTransportSExecuteSurLExecutorFourni() {
        when(kafkaPipelineEventService.publishExecutionRequested(any(), eq("exec-1")))
                .thenReturn("publie");

        serviceWith(Runnable::run).runWorkflow("wf-1");

        verify(kafkaPipelineEventService).publishExecutionRequested(any(), eq("exec-1"));
    }

    @Test
    void uneSecondeExecutionSimultaneeEstRefusee() {
        when(executionLogRepository.existsByWorkflowIdAndStatus("wf-1", ExecutionStatus.RUNNING))
                .thenReturn(true);

        ConflictException error = assertThrows(ConflictException.class,
                () -> serviceWith(NEVER_RUNS).runWorkflow("wf-1"));

        assertTrue(error.getMessage().contains("deja en cours"));
        // Aucun journal parasite ne doit etre cree pour une soumission refusee.
        verify(executionLogRepository, never()).save(any(ExecutionLog.class));
    }

    @Test
    void laSaturationEstSignaleeEtConsigneeCommeEchec() {
        Executor saturated = task -> {
            throw new RejectedExecutionException("plus de capacite");
        };

        assertThrows(RejectedExecutionException.class,
                () -> serviceWith(saturated).runWorkflow("wf-1"));

        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        verify(executionLogRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());

        List<ExecutionLog> saved = captor.getAllValues();
        ExecutionLog last = saved.get(saved.size() - 1);
        assertEquals(ExecutionStatus.FAILED, last.getStatus());
        assertEquals("SUBMISSION", last.getFailedStage());
    }

    @Test
    void unEchecDeTransportNeRemontePasMaisMarqueLeJournal() {
        when(kafkaPipelineEventService.publishExecutionRequested(any(), eq("exec-1")))
                .thenThrow(new IllegalStateException("kafka indisponible"));

        // L'appelant a deja recu sa reponse: l'echec ne doit pas etre propage.
        ExecutionLog result = serviceWith(Runnable::run).runWorkflow("wf-1");
        assertEquals("exec-1", result.getId());

        ArgumentCaptor<ExecutionLog> captor = ArgumentCaptor.forClass(ExecutionLog.class);
        verify(executionLogRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream()
                .anyMatch(entry -> ExecutionStatus.FAILED.equals(entry.getStatus())));
    }
}
