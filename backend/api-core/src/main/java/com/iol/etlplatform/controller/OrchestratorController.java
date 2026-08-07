package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.common.ApiResponse;
import com.iol.etlplatform.dto.log.ExecutionLogDto;
import com.iol.etlplatform.mapper.ExecutionLogMapper;
import com.iol.etlplatform.service.OrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/orchestrator")
@RequiredArgsConstructor
@Tag(name = "Orchestration", description = "Exécution des workflows ETL")
@SecurityRequirement(name = "bearerAuth")
public class OrchestratorController {

    private final OrchestrationService orchestrationService;
    private final ExecutionLogMapper executionLogMapper;

    /**
     * Accepte un workflow et rend la main immédiatement.
     *
     * La réponse est un 202 et non un 200 : à ce stade seul le journal
     * d'exécution existe. L'extraction, le transport et la publication de la
     * commande se poursuivent en arrière-plan, puis Hop ou Spark exécutent le
     * pipeline. Le suivi se fait via {@code /api/logs} et le statut temps réel.
     */
    @PostMapping("/run/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Soumettre un workflow ETL à l'exécution")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Exécution acceptée; suivi via le journal"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Accès refusé"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Workflow non trouvé"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Une exécution est déjà en cours pour ce workflow"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "429", description = "Capacité d'exécution saturée; réessayer plus tard")
    })
    public ResponseEntity<ApiResponse<ExecutionLogDto>> runWorkflow(@PathVariable String id) {
        ExecutionLogDto dto = executionLogMapper.toDto(orchestrationService.runWorkflow(id));
        return ResponseEntity.accepted()
                .body(new ApiResponse<>("Execution acceptee; suivez son avancement dans le journal.", dto));
    }
}
