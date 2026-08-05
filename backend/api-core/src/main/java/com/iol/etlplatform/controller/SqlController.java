package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.common.ApiResponse;
import com.iol.etlplatform.dto.sql.ExecuteSqlRequest;
import com.iol.etlplatform.dto.sql.ExecuteSqlResponse;
import com.iol.etlplatform.dto.workflow.ValidateSqlRequest;
import com.iol.etlplatform.dto.workflow.ValidateSqlResponse;
import com.iol.etlplatform.entity.SourceMetadata;
import com.iol.etlplatform.service.SqlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sql")
@RequiredArgsConstructor
@Tag(name = "SQL", description = "Validation et exécution SQL unifiées")
public class SqlController {

    private final SqlService sqlService;

    @PostMapping("/validate")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Valide un script SQL (SILVER/GOLD)")
    public ResponseEntity<ApiResponse<ValidateSqlResponse>> validateSql(@RequestBody ValidateSqlRequest request) {
        ValidateSqlResponse resp = sqlService.validate(request);
        return ResponseEntity.ok(new ApiResponse<>(resp.isValid() ? "SQL valide" : "SQL invalide", resp));
    }

    @PostMapping("/execute")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Execute un script SQL via le workbench")
    public ResponseEntity<ApiResponse<ExecuteSqlResponse>> execute(@RequestBody ExecuteSqlRequest request) {
        ExecuteSqlResponse response = sqlService.execute(request);
        return ResponseEntity.ok(new ApiResponse<>("SQL exécuté", response));
    }

    // Additional AI-driven SQL generation endpoints (validation/aggregation/cleaning)
    // remain in AISqlAssistantService / AiService; keep controllers focused on validate/execute.
}
