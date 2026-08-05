package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.ai.AiSqlRequest;
import com.iol.etlplatform.dto.ai.AiSqlResponse;
import com.iol.etlplatform.dto.ai.ContextualAiSqlRequest;
import com.iol.etlplatform.dto.ai.ChatRequest;
import com.iol.etlplatform.dto.ai.ChatResponse;
import com.iol.etlplatform.dto.ai.SchemaOnlySqlRequest;
import com.iol.etlplatform.dto.common.ApiResponse;
import com.iol.etlplatform.entity.SourceMetadata;
import com.iol.etlplatform.service.AiService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "AI Assistant", description = "Assistant IA pour générer des requêtes SQL contextuelles")
@SecurityRequirement(name = "bearerAuth")
public class AiController {

        private final AiService aiService;

        @GetMapping("/status")
        @PreAuthorize("hasAnyRole('ADMIN','USER')")
        public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
                return ResponseEntity.ok(new ApiResponse<>("Etat de l'assistant IA.", aiService.status()));
        }

        @PostMapping("/chat-context")
        @PreAuthorize("hasAnyRole('ADMIN','USER')")
        public ResponseEntity<ApiResponse<ChatResponse>> chatContext(@RequestBody ChatRequest req) {
                ChatResponse resp = aiService.chat(req);
                return ResponseEntity.ok(new ApiResponse<>("OK", resp));
        }

    /**
     * Générer une requête SQL avec l'IA
     */
    @PostMapping("/generate-sql")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Générer une requête SQL avec l'IA")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Requête SQL générée"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Demande invalide"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Non authentifié"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "500", description = "Erreur lors de la génération")
    })
    public ResponseEntity<ApiResponse<AiSqlResponse>> generateSql(@Valid @RequestBody AiSqlRequest request) {
        log.info("Génération SQL pour workflow: {}", request.getWorkflowId());
        return ResponseEntity.ok(new ApiResponse<>("SQL genere par l'assistant IA.", 
                aiService.generateSql(request)));
    }

    @PostMapping("/generate-schema-sql")
    @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(summary = "Generer du SQL a partir des seuls noms de colonnes")
    public ResponseEntity<ApiResponse<AiSqlResponse>> generateSchemaSql(
            @Valid @RequestBody SchemaOnlySqlRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(
                "SQL genere sans transmission de donnees metier.",
                aiService.generateSchemaOnlySql(request)));
    }

    /**
     * Générer SQL contextuel avec Standard ET schéma découvert
     * C'est l'endpoint clé de la finalisation
     */
    @PostMapping("/generate-contextual-sql")
        @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(
            summary = "Générer SQL contextuel (Workflow + Standard + Schéma découvert)",
            description = """
                    Génère du SQL précis en combinant:
                    - Le schéma découvert (colonnes, types)
                    - Les termes du standard métier
                    - Le contexte du workflow
                    
                    Le LLM mappe automatiquement les colonnes aux termes du standard.
                    """
    )
    public ResponseEntity<ApiResponse<String>> generateContextualSql(
            @Valid @RequestBody ContextualAiSqlRequest request) {
        log.info("Génération SQL contextuelle: workflow={}, standard={}, columns={}", 
                request.getWorkflowId(), request.getStandardId(), 
                request.getDiscoveredColumns() != null ? request.getDiscoveredColumns().size() : 0);

        String sql = aiService.generateContextualSql(request);
        return ResponseEntity.ok(new ApiResponse<>("SQL généré avec contexte standard et schéma découvert", sql));
    }

    /**
     * Générer un SQL d'agrégation (Gold)
     */
    @PostMapping("/generate-aggregation-sql/{workflowId}/{standardId}")
        @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(
            summary = "Générer SQL d'agrégation (Gold)",
            description = "Génère un SQL pour agréger les données de multiples sources en table Gold"
    )
    public ResponseEntity<ApiResponse<String>> generateAggregationSql(
            @Parameter(description = "ID du workflow", required = true)
            @PathVariable String workflowId,
            @Parameter(description = "ID du standard", required = true)
            @PathVariable String standardId,
            @Parameter(description = "IDs des sources à agréger", required = true)
            @RequestBody List<String> sourceIds) {
        log.info("Génération SQL agrégation pour workflow: {} ({}sources)", workflowId, sourceIds.size());

        String sql = aiService.generateAggregationSql(workflowId, standardId, sourceIds);
        return ResponseEntity.ok(new ApiResponse<>("SQL agrégation généré", sql));
    }

    /**
     * Générer un SQL de nettoyage
     */
    @PostMapping("/generate-cleaning-sql")
        @PreAuthorize("hasAnyRole('ADMIN','USER')")
    @Operation(
            summary = "Générer SQL de nettoyage",
            description = "Génère un SQL pour nettoyer et normaliser les données"
    )
    public ResponseEntity<ApiResponse<String>> generateCleaningSql(
            @Valid @RequestBody List<SourceMetadata.ColumnMetadata> columns) {
        log.info("Génération SQL nettoyage pour {} colonnes", columns.size());

        String sql = aiService.generateCleaningSql(columns);
        return ResponseEntity.ok(new ApiResponse<>("SQL nettoyage généré", sql));
    }
}
