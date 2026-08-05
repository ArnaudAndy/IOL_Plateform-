package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.common.ApiResponse;
import com.iol.etlplatform.dto.workflow.SchemaDiscoveryResponse;
import com.iol.etlplatform.dto.workflow.WorkflowConfigDto;
import com.iol.etlplatform.dto.workflow.WorkflowExportResponse;
import com.iol.etlplatform.service.WorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
@Tag(name = "Workflows", description = "Gestion des workflows ETL")
@SecurityRequirement(name = "bearerAuth")
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Créer un workflow")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Workflow créé"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Accès refusé")
    })
    public ResponseEntity<ApiResponse<WorkflowConfigDto>> create(@Valid @RequestBody WorkflowConfigDto dto) {
        return ResponseEntity.ok(new ApiResponse<>("Workflow cree.", workflowService.create(dto)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Récupérer tous les workflows")
    public ResponseEntity<ApiResponse<List<WorkflowConfigDto>>> findAll() {
        return ResponseEntity.ok(new ApiResponse<>("Liste des workflows.", workflowService.findAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Récupérer un workflow")
    public ResponseEntity<ApiResponse<WorkflowConfigDto>> findById(@PathVariable String id) {
        return ResponseEntity.ok(new ApiResponse<>("Workflow recupere.", workflowService.findById(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Mettre à jour un workflow")
    public ResponseEntity<ApiResponse<WorkflowConfigDto>> update(@PathVariable String id, @Valid @RequestBody WorkflowConfigDto dto) {
        return ResponseEntity.ok(new ApiResponse<>("Workflow mis a jour.", workflowService.update(id, dto)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Supprimer un workflow")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable String id) {
        workflowService.delete(id);
        return ResponseEntity.ok(new ApiResponse<>("Workflow supprime.", null));
    }

    @GetMapping("/{id}/discover")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Découvrir les schémas")
    public ResponseEntity<ApiResponse<List<SchemaDiscoveryResponse>>> discover(@PathVariable String id) {
        return ResponseEntity.ok(new ApiResponse<>("Introspection terminee.", workflowService.discoverSources(id)));
    }

    @GetMapping("/{id}/export")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Exporter le workflow")
    public ResponseEntity<ApiResponse<WorkflowExportResponse>> export(@PathVariable String id) {
        return ResponseEntity.ok(new ApiResponse<>("Workflow exporte en JSON.", workflowService.exportWorkflowAsJson(id)));
    }

    @PostMapping("/discover")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Découvrir les colonnes d'une source")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> discover(@RequestBody Map<String, Object> sourceConfig) {
        return ResponseEntity.ok(new ApiResponse<>("Colonnes découvertes.", workflowService.discoverSourceColumns(sourceConfig)));
    }

    @PostMapping("/draft")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Sauvegarder un brouillon de mapping")
    public ResponseEntity<ApiResponse<Void>> draft(@RequestBody Map<String, Object> payload) {
        workflowService.saveMappingDraft(payload);
        return ResponseEntity.ok(new ApiResponse<>("Brouillon sauvegardé.", null));
    }

    @PostMapping("/execute")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Exécuter un workflow")
    public ResponseEntity<ApiResponse<String>> execute(@RequestBody Map<String, String> payload) {
        String result = workflowService.executeWorkflow(payload.get("workflowId"));
        return ResponseEntity.ok(new ApiResponse<>("Workflow exécuté.", result));
    }
}
