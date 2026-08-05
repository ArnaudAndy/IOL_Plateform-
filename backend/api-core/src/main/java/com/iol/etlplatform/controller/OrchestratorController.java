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

    @PostMapping("/run/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Exécuter un workflow ETL")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Exécution lancée"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Accès refusé"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Workflow non trouvé"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Erreur lors de l'exécution")
    })
    public ResponseEntity<ApiResponse<ExecutionLogDto>> runWorkflow(@PathVariable String id) {
        ExecutionLogDto dto = executionLogMapper.toDto(orchestrationService.runWorkflow(id));
        return ResponseEntity.ok(new ApiResponse<>("Execution workflow terminee.", dto));
    }
}
