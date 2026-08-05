package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.common.ApiResponse;
import com.iol.etlplatform.dto.workflow.WorkflowTemplateDto;
import com.iol.etlplatform.service.WorkflowTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/workflow-templates")
@RequiredArgsConstructor
@Tag(name = "Modeles de workflows", description = "Structures de workflows reutilisables et sans secrets")
@SecurityRequirement(name = "bearerAuth")
public class WorkflowTemplateController {

    private final WorkflowTemplateService workflowTemplateService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Lister les modeles de workflows")
    public ResponseEntity<ApiResponse<List<WorkflowTemplateDto>>> findAll() {
        return ResponseEntity.ok(new ApiResponse<>(
                "Modeles de workflows.", workflowTemplateService.findAll()));
    }
}
