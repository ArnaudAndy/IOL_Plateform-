package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.interop.InboundExecutionPrepareRequest;
import com.iol.etlplatform.dto.interop.InboundExecutionPrepareResponse;
import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.entity.enums.ExecutionStatus;
import com.iol.etlplatform.entity.enums.WorkflowDirection;
import com.iol.etlplatform.exception.ResourceNotFoundException;
import com.iol.etlplatform.kafka.KafkaPipelineEventService;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import com.iol.etlplatform.repository.WorkflowConfigRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a validated interoperability payload into one durable IOL execution.
 *
 * The mediator has already normalized records to the standard pivot when this
 * service is called. This layer resolves the INBOUND workflow, claims the
 * persistent idempotency receipt, creates the execution log, and delegates all
 * data movement to Kafka/RustFS. Hop and Spark never reconnect to the sender.
 */
@Service
@RequiredArgsConstructor
public class InternalInteropExecutionService {

    private final WorkflowConfigRepository workflowConfigRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final KafkaPipelineEventService kafkaPipelineEventService;
    private final InboundIdempotencyService inboundIdempotencyService;

    @Value("${app.tenancy.mode:SINGLE_ORGANIZATION}")
    private String tenancyMode = "SINGLE_ORGANIZATION";

    @Value("${app.tenancy.default-organization-id:iol-default}")
    private String defaultOrganizationId = "iol-default";

    public InboundExecutionPrepareResponse prepareInboundExecution(
            String standardId,
            InboundExecutionPrepareRequest request) {
        List<Map<String, Object>> pivots = inboundPivots(request);
        String organizationId = activeOrganizationId();

        WorkflowConfig workflow = resolveWorkflow(standardId, request.getWorkflowId());
        validateInboundWorkflow(workflow, standardId);
        InboundIdempotencyService.Claim claim = inboundIdempotencyService.claim(
                organizationId,
                workflow.getId(),
                standardId,
                request.getSourceSystem(),
                request.getIdempotencyKey(),
                request.getPayloadHash());
        if (claim.replayed()) return claim.response();

        ExecutionLog execLog = null;
        try {
            execLog = ExecutionLog.builder()
                    .workflowId(workflow.getId())
                    .workflowName(workflow.getWorkflowName())
                    .direction(WorkflowDirection.INBOUND)
                    .correlationId(request.getCorrelationId())
                    .startTime(Instant.now())
                    .status(ExecutionStatus.RUNNING)
                    .triggeredBy("openhim-iol-mediator")
                    .createdAt(LocalDateTime.now())
                    .executionParams(executionParams(standardId, request))
                    .logOutput("Lot INBOUND valide par le mediateur; remise Kafka preparee.\n")
                    .build();
            execLog.getExecutionParams().put("organizationId", organizationId);
            execLog.getExecutionParams().put("recordCount", String.valueOf(pivots.size()));
            execLog.getExecutionParams().put("idempotencyRecordId", claim.recordId());
            execLog = executionLogRepository.save(execLog);
            inboundIdempotencyService.attachExecution(claim, execLog.getId());

            KafkaPipelineEventService.InboundPublication publication =
                    kafkaPipelineEventService.publishInboundExecutionRequested(
                    workflow,
                    execLog.getId(),
                    organizationId,
                    standardId,
                    request.getSourceSystem(),
                    request.getCorrelationId(),
                    request.getOpenhimTransactionId(),
                    pivots,
                    request.getEstimatedBytes());
            requireAtomicPublication(publication);
            InboundExecutionPrepareResponse response = response(
                    workflow, execLog, organizationId, publication);
            inboundIdempotencyService.complete(claim, response);
            return response;
        } catch (RuntimeException error) {
            markFailed(execLog, error);
            failClaim(claim, error);
            throw error;
        }
    }

    public InboundExecutionPrepareResponse prepareInboundExecutionStream(
            String standardId,
            InboundExecutionPrepareRequest request,
            InputStream inputStream) {
        if (request == null || inputStream == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Un flux NDJSON INBOUND est obligatoire.");
        }
        String organizationId = activeOrganizationId();
        WorkflowConfig workflow = resolveWorkflow(standardId, request.getWorkflowId());
        validateInboundWorkflow(workflow, standardId);
        InboundIdempotencyService.Claim claim = inboundIdempotencyService.claim(
                organizationId,
                workflow.getId(),
                standardId,
                request.getSourceSystem(),
                request.getIdempotencyKey(),
                request.getPayloadHash());
        if (claim.replayed()) return claim.response();

        ExecutionLog execLog = null;
        try {
            execLog = ExecutionLog.builder()
                    .workflowId(workflow.getId())
                    .workflowName(workflow.getWorkflowName())
                    .direction(WorkflowDirection.INBOUND)
                    .correlationId(request.getCorrelationId())
                    .startTime(Instant.now())
                    .status(ExecutionStatus.RUNNING)
                    .triggeredBy("openhim-iol-mediator")
                    .createdAt(LocalDateTime.now())
                    .executionParams(executionParams(standardId, request))
                    .logOutput("Flux NDJSON INBOUND validé par lots; remise progressive préparée.\n")
                    .build();
            execLog.getExecutionParams().put("organizationId", organizationId);
            execLog.getExecutionParams().put("recordCount", "streaming");
            execLog.getExecutionParams().put("idempotencyRecordId", claim.recordId());
            execLog = executionLogRepository.save(execLog);
            inboundIdempotencyService.attachExecution(claim, execLog.getId());

            KafkaPipelineEventService.InboundPublication publication =
                    kafkaPipelineEventService.publishInboundExecutionRequestedStream(
                    workflow,
                    execLog.getId(),
                    organizationId,
                    standardId,
                    request.getSourceSystem(),
                    request.getCorrelationId(),
                    request.getOpenhimTransactionId(),
                    inputStream,
                    request.getEstimatedRows(),
                    request.getEstimatedBytes(),
                    request.getEstimatedMaxRecordBytes());
            requireAtomicPublication(publication);
            execLog.getExecutionParams().put(
                    "recordCount", String.valueOf(publication.recordCount()));
            executionLogRepository.save(execLog);
            InboundExecutionPrepareResponse response = response(
                    workflow, execLog, organizationId, publication);
            inboundIdempotencyService.complete(claim, response);
            return response;
        } catch (RuntimeException error) {
            markFailed(execLog, error);
            failClaim(claim, error);
            throw error;
        }
    }

    private InboundExecutionPrepareResponse response(
            WorkflowConfig workflow,
            ExecutionLog execLog,
            String organizationId,
            KafkaPipelineEventService.InboundPublication publication) {
        return InboundExecutionPrepareResponse.builder()
                .workflowId(workflow.getId())
                .execLogId(execLog.getId())
                .kafkaTopic(publication.topic())
                .kafkaKey(publication.key())
                .organizationId(organizationId)
                .dataTransport(publication.dataTransport())
                .recordCount(publication.recordCount())
                .commandPublished(true)
                .idempotentReplay(false)
                .command(Map.of())
                .build();
    }

    private void requireAtomicPublication(
            KafkaPipelineEventService.InboundPublication publication) {
        if (publication == null || !publication.published()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "La réception INBOUND n'a pas pu être publiée atomiquement dans Kafka.");
        }
    }

    private void markFailed(ExecutionLog execLog, RuntimeException error) {
        if (execLog == null) return;
        execLog.setStatus(ExecutionStatus.FAILED);
        execLog.setEndTime(Instant.now());
        execLog.setErrorMessage(error.getMessage());
        executionLogRepository.save(execLog);
    }

    private void failClaim(
            InboundIdempotencyService.Claim claim, RuntimeException error) {
        try {
            inboundIdempotencyService.fail(claim, error);
        } catch (RuntimeException ledgerError) {
            error.addSuppressed(ledgerError);
        }
    }

    private List<Map<String, Object>> inboundPivots(InboundExecutionPrepareRequest request) {
        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Une charge INBOUND est obligatoire.");
        }
        boolean hasSingle = request.getPivot() != null && !request.getPivot().isEmpty();
        boolean hasBatch = request.getPivots() != null && !request.getPivots().isEmpty();
        if (hasSingle && hasBatch) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Utilisez pivot ou pivots, pas les deux.");
        }
        List<Map<String, Object>> pivots = hasBatch
                ? request.getPivots()
                : hasSingle ? List.of(request.getPivot()) : List.of();
        if (pivots.isEmpty() || pivots.stream().anyMatch(pivot -> pivot == null || pivot.isEmpty())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Au moins un pivot INBOUND non vide est obligatoire.");
        }
        return pivots.stream()
                .map(pivot -> (Map<String, Object>) new LinkedHashMap<>(pivot))
                .toList();
    }

    private String activeOrganizationId() {
        if (!"SINGLE_ORGANIZATION".equalsIgnoreCase(tenancyMode)) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Les échanges interop multi-organisation restent désactivés tant que "
                            + "l'isolation runtime complète n'est pas activée.");
        }
        if (defaultOrganizationId == null
                || !defaultOrganizationId.matches("[A-Za-z0-9][A-Za-z0-9._-]{2,63}")) {
            throw new IllegalStateException("app.tenancy.default-organization-id est invalide.");
        }
        return defaultOrganizationId;
    }

    private WorkflowConfig resolveWorkflow(String standardId, String requestedWorkflowId) {
        if (StringUtils.hasText(requestedWorkflowId)) {
            return workflowConfigRepository.findById(requestedWorkflowId)
                    .orElseThrow(() -> new ResourceNotFoundException("Workflow introuvable: " + requestedWorkflowId));
        }

        List<WorkflowConfig> candidates = workflowConfigRepository
                .findByStandardIdAndDirectionAndActiveTrue(standardId, WorkflowDirection.INBOUND);
        if (candidates.isEmpty()) {
            throw new ResourceNotFoundException(
                    "Aucun workflow INBOUND actif rattache au standard: " + standardId);
        }
        if (candidates.size() > 1) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Plusieurs workflows INBOUND actifs sont rattaches au standard; fournissez workflowId.");
        }
        return candidates.get(0);
    }

    private void validateInboundWorkflow(WorkflowConfig workflow, String standardId) {
        if (!workflow.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Le workflow cible est inactif.");
        }
        if (workflow.getDirection() != WorkflowDirection.INBOUND) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Le workflow cible doit etre INBOUND.");
        }
        if (!Objects.equals(workflow.getStandardId(), standardId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Le workflow cible n'est pas rattache a ce standard.");
        }
    }

    private Map<String, String> executionParams(String standardId, InboundExecutionPrepareRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("direction", "INBOUND");
        params.put("standardId", standardId);
        if (StringUtils.hasText(request.getSourceSystem())) {
            params.put("sourceSystem", request.getSourceSystem());
        }
        if (StringUtils.hasText(request.getCorrelationId())) {
            params.put("correlationId", request.getCorrelationId());
        }
        if (StringUtils.hasText(request.getOpenhimTransactionId())) {
            params.put("openhimTransactionId", request.getOpenhimTransactionId());
        }
        if (request.getEstimatedRows() != null) {
            params.put("estimatedRows", String.valueOf(request.getEstimatedRows()));
        }
        if (request.getEstimatedBytes() != null) {
            params.put("estimatedBytes", String.valueOf(request.getEstimatedBytes()));
        }
        if (request.getEstimatedMaxRecordBytes() != null) {
            params.put(
                    "estimatedMaxRecordBytes",
                    String.valueOf(request.getEstimatedMaxRecordBytes()));
        }
        return params;
    }
}
