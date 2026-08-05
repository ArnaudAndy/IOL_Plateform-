package com.iol.etlplatform.controller;

import com.iol.etlplatform.entity.AuditLog;
import com.iol.etlplatform.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Contrôleur pour consulter les logs d'audit
 * Restreint aux ADMIN
 */
@RestController
@RequestMapping("/api/v1/audit")
@Slf4j
@RequiredArgsConstructor
@Tag(name = "Audit Management", description = "Consultation des logs d'audit des actions effectuées")
public class AuditController {

    private final AuditService auditService;

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Récupérer le journal d'audit",
            description = "Affiche toutes les actions, de la plus récente à la plus ancienne"
    )
    public ResponseEntity<List<AuditLog>> getAllActions() {
        return ResponseEntity.ok(auditService.getAllAuditLogs());
    }

    /**
     * Récupérer l'audit d'une ressource
     */
    @GetMapping("/resource/{resourceType}/{resourceId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Récupérer l'historique d'audit d'une ressource",
            description = "Affiche toutes les actions effectuées sur une ressource"
    )
    public ResponseEntity<List<AuditLog>> getResourceAudit(
            @Parameter(description = "Type de ressource (Standard, Workflow, etc.)", required = true)
            @PathVariable String resourceType,
            @Parameter(description = "ID de la ressource", required = true)
            @PathVariable String resourceId) {
        log.info("Récupération audit: {}/{}", resourceType, resourceId);

        List<AuditLog> logs = auditService.getResourceAuditLog(resourceType, resourceId);
        return ResponseEntity.ok(logs);
    }

    /**
     * Récupérer l'audit d'un utilisateur
     */
    @GetMapping("/user/{userId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Récupérer l'historique d'audit d'un utilisateur",
            description = "Affiche toutes les actions effectuées par un utilisateur"
    )
    public ResponseEntity<List<AuditLog>> getUserAudit(
            @Parameter(description = "ID de l'utilisateur", required = true)
            @PathVariable String userId) {
        log.info("Récupération audit utilisateur: {}", userId);

        List<AuditLog> logs = auditService.getUserAuditLog(userId);
        return ResponseEntity.ok(logs);
    }

    /**
     * Récupérer les actions échouées
     */
    @GetMapping("/failed")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Récupérer les actions échouées",
            description = "Affiche tous les audits avec statut FAILURE"
    )
    public ResponseEntity<List<AuditLog>> getFailedActions() {
        log.info("Récupération des actions échouées");

        List<AuditLog> logs = auditService.getFailedAuditLogs();
        return ResponseEntity.ok(logs);
    }
}
