package com.iol.etlplatform.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.iol.etlplatform.entity.SourceMetadata;
import com.iol.etlplatform.service.MetadataGeneratorService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Contrôleur pour la génération des métadonnées JSON
 * Utilisé avant l'orchestration avec Apache Hop
 */
@RestController
@RequestMapping("/api/v1/metadata")
@Slf4j
@RequiredArgsConstructor
@Tag(name = "Metadata Generation", description = "Génération des métadonnées pour sources (JDBC, S3, API)")
public class MetadataGeneratorController {

    private final MetadataGeneratorService metadataGeneratorService;

    /**
     * Générer les métadonnées pour une source
     */
        @PostMapping("/generate")
        @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Générer métadonnées pour une source",
            description = "Génère le JSON métadonnées pour Apache Hop à partir de la configuration source"
    )
    public ResponseEntity<String> generateMetadata(
            @Valid @RequestBody SourceMetadata metadata) {
        log.info("Génération métadonnées pour source: {}", metadata.getSourceName());

        String json = metadataGeneratorService.generateMetadata(metadata);
        return ResponseEntity.ok(json);
    }

    /**
     * Générer les métadonnées pour toutes les sources d'un workflow
     */
        @PostMapping("/generate-workflow/{workflowId}")
        @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Générer métadonnées pour un workflow",
            description = "Génère les JSON métadonnées pour toutes les sources d'un workflow"
    )
    public ResponseEntity<Map<String, String>> generateWorkflowMetadata(
            @Parameter(description = "ID du workflow", required = true)
            @PathVariable String workflowId,
            @Valid @RequestBody List<SourceMetadata> sources) {
        log.info("Génération métadonnées workflow: {} ({} sources)", workflowId, sources.size());

        Map<String, String> metadata = metadataGeneratorService.generateMetadataForWorkflow(sources);
        return ResponseEntity.ok(metadata);
    }

    /**
     * Générer la configuration Hop complète d'orchestration
     */
        @PostMapping("/generate-hop-orchestration/{workflowId}")
        @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Générer configuration Hop d'orchestration",
            description = "Génère le JSON complet de configuration pour orchestrer le workflow avec Hop"
    )
    public ResponseEntity<String> generateHopOrchestration(
            @Parameter(description = "ID du workflow", required = true)
            @PathVariable String workflowId,
            @Valid @RequestBody List<SourceMetadata> sources) {
        log.info("Génération orchestration Hop pour workflow: {}", workflowId);

        String hopConfig = metadataGeneratorService.generateHopOrchestrationConfig(sources, workflowId);
        return ResponseEntity.ok(hopConfig);
    }
}
