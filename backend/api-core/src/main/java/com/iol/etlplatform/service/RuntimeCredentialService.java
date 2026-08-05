package com.iol.etlplatform.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.iol.etlplatform.dto.connection.RuntimeCredentialLeaseRequest;
import com.iol.etlplatform.dto.connection.RuntimeCredentialLeaseResponse;
import com.iol.etlplatform.entity.AuditLog;
import com.iol.etlplatform.entity.DestinationConnection;
import com.iol.etlplatform.entity.ExecutionLog;
import com.iol.etlplatform.entity.WorkflowConfig;
import com.iol.etlplatform.exception.BadRequestException;
import com.iol.etlplatform.exception.ResourceNotFoundException;
import com.iol.etlplatform.repository.ExecutionLogRepository;
import com.iol.etlplatform.repository.WorkflowConfigRepository;

import lombok.RequiredArgsConstructor;

/** Délivre un credential de destination seulement pour une exécution connue. */
@Service
@RequiredArgsConstructor
public class RuntimeCredentialService {
    private final DestinationConnectionService connectionService;
    private final ExecutionLogRepository executionRepository;
    private final WorkflowConfigRepository workflowRepository;
    private final AuditService auditService;

    @Value("${app.credentials.runtime-lease-seconds:120}")
    private long leaseSeconds;

    public RuntimeCredentialLeaseResponse issue(
            String connectionId,
            RuntimeCredentialLeaseRequest request,
            String servicePrincipal) {
        ExecutionLog execution = executionRepository.findById(request.executionId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Exécution introuvable pour la délivrance du credential."));
        if (!request.workflowId().equals(execution.getWorkflowId())) {
            throw new BadRequestException("L'exécution ne correspond pas au workflow demandé.");
        }
        WorkflowConfig workflow = workflowRepository.findById(request.workflowId())
                .orElseThrow(() -> new ResourceNotFoundException("Workflow introuvable."));
        if (!connectionId.equals(workflow.getDestinationConnectionId())) {
            throw new BadRequestException("Cette connexion n'est pas la destination de l'exécution.");
        }

        DestinationConnection connection = connectionService.getEntityByIdForOwner(
                connectionId, workflow.getCreatedBy());
        String password = connectionService.resolvePassword(connection);
        auditService.logAction(
                servicePrincipal,
                "SERVICE_PIPELINE",
                AuditLog.AuditAction.READ,
                "RUNTIME_CREDENTIAL",
                connectionId,
                "Credential délivré pour l'exécution " + request.executionId());
        return new RuntimeCredentialLeaseResponse(
                connectionId,
                password,
                Instant.now().plus(Math.max(30L, leaseSeconds), ChronoUnit.SECONDS));
    }
}
