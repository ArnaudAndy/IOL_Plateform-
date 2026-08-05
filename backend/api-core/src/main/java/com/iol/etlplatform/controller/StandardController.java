package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.standard.StandardDto;
import com.iol.etlplatform.dto.standard.StandardTermDto;
import com.iol.etlplatform.entity.Standard;
import com.iol.etlplatform.service.StandardService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * Contrôleur REST pour la gestion des Standards
 * 
 * Endpoints pour:
 * - Créer, consulter, modifier les standards
 * - Gérer les termes d'un standard
 * - Valider les mappages sémantiques
 */
@RestController
@RequestMapping("/api/v1/standards")
@Slf4j
@RequiredArgsConstructor
@Tag(name = "Standards Management", description = "Gestion complète des standards métier (HL7, FHIR, ISO20022, etc.)")
public class StandardController {

        private final StandardService standardService;

    // ==================== STANDARDS ====================

    /**
     * Créer un nouveau standard
     * Seuls les ADMIN peuvent créer des standards
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Créer un nouveau standard",
            description = "Crée un nouveau standard avec les informations fournies. Restreint aux ADMIN."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Standard créé avec succès",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = StandardDto.class))),
            @ApiResponse(responseCode = "400", description = "Données invalides"),
            @ApiResponse(responseCode = "403", description = "Accès refusé (rôle insuffisant)")
    })
    public ResponseEntity<StandardDto> createStandard(
            @Valid @RequestBody StandardDto standardDto,
            Authentication authentication) {
        log.info("Création d'un standard par l'utilisateur: {}", authentication.getName());

        StandardDto created = standardService.createStandard(standardDto, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Récupérer un standard par son ID
     */
        @GetMapping("/{id}")
        @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(
            summary = "Récupérer un standard par ID",
            description = "Récupère les détails complets d'un standard avec tous ses termes"
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Standard trouvé",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = StandardDto.class))),
            @ApiResponse(responseCode = "404", description = "Standard non trouvé")
    })
    public ResponseEntity<StandardDto> getStandardById(
            @Parameter(description = "ID du standard", example = "std_001", required = true)
            @PathVariable String id) {
        log.info("Récupération du standard: {}", id);

        StandardDto standard = standardService.getStandardById(id);
        return ResponseEntity.ok(standard);
    }

    /**
     * Récupérer tous les standards
     */
        @GetMapping
        @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(
            summary = "Lister tous les standards",
            description = "Récupère la liste de tous les standards définis dans le système"
    )
    @ApiResponse(responseCode = "200", description = "Liste des standards")
    public ResponseEntity<List<StandardDto>> getAllStandards() {
        log.info("Récupération de tous les standards");

        List<StandardDto> standards = standardService.getAllStandards();
        return ResponseEntity.ok(standards);
    }

    /**
     * Récupérer les standards par domaine
     */
        @GetMapping("/domain/{domain}")
        @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(
            summary = "Lister les standards par domaine",
            description = "Récupère les standards actifs d'un domaine spécifique (HEALTH, FINANCE, etc.)"
    )
    @ApiResponse(responseCode = "200", description = "Standards du domaine")
    public ResponseEntity<List<StandardDto>> getStandardsByDomain(
            @Parameter(description = "Domaine des standards", example = "HEALTH", required = true)
            @PathVariable Standard.StandardDomain domain) {
        log.info("Récupération des standards du domaine: {}", domain);

        List<StandardDto> standards = standardService.getStandardsByDomain(domain);
        return ResponseEntity.ok(standards);
    }

    /**
     * Mettre à jour un standard
     * Seuls les ADMIN peuvent modifier les standards
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Mettre à jour un standard",
            description = "Modifie les propriétés d'un standard existant. Restreint aux ADMIN."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Standard mis à jour"),
            @ApiResponse(responseCode = "404", description = "Standard non trouvé"),
            @ApiResponse(responseCode = "403", description = "Accès refusé")
    })
    public ResponseEntity<StandardDto> updateStandard(
            @Parameter(description = "ID du standard", required = true)
            @PathVariable String id,
            @Valid @RequestBody StandardDto standardDto) {
        log.info("Mise à jour du standard: {}", id);

        StandardDto updated = standardService.updateStandard(id, standardDto);
        return ResponseEntity.ok(updated);
    }

    /**
     * Activer un standard (passer du statut DRAFT à ACTIVE)
     */
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Activer un standard",
            description = "Change le statut d'un standard à ACTIVE. Restreint aux ADMIN."
    )
    @ApiResponse(responseCode = "200", description = "Standard activé")
    public ResponseEntity<StandardDto> activateStandard(
            @Parameter(description = "ID du standard", required = true)
            @PathVariable String id) {
        log.info("Activation du standard: {}", id);

        StandardDto activated = standardService.activateStandard(id);
        return ResponseEntity.ok(activated);
    }

    /**
     * Dépublier un standard (passer du statut ACTIVE à DEPRECATED)
     */
    @PostMapping("/{id}/deprecate")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Dépublier un standard",
            description = "Change le statut d'un standard à DEPRECATED. Restreint aux ADMIN."
    )
    @ApiResponse(responseCode = "200", description = "Standard dépublié")
    public ResponseEntity<StandardDto> deprecateStandard(
            @Parameter(description = "ID du standard", required = true)
            @PathVariable String id) {
        log.info("Dépublication du standard: {}", id);

        StandardDto deprecated = standardService.deprecateStandard(id);
        return ResponseEntity.ok(deprecated);
    }

    // ==================== STANDARD TERMS ====================

    /**
     * Ajouter un terme à un standard
     */
    @PostMapping("/{standardId}/terms")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Ajouter un terme à un standard",
            description = "Crée un nouveau terme (champ) dans un standard. Restreint aux ADMIN."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Terme créé"),
            @ApiResponse(responseCode = "404", description = "Standard non trouvé"),
            @ApiResponse(responseCode = "409", description = "Terme déjà existant")
    })
    public ResponseEntity<StandardTermDto> addTermToStandard(
            @Parameter(description = "ID du standard", required = true)
            @PathVariable String standardId,
            @Valid @RequestBody StandardTermDto termDto) {
        log.info("Ajout du terme {} au standard: {}", termDto.getTermName(), standardId);

        StandardTermDto created = standardService.addTermToStandard(standardId, termDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * Récupérer les termes d'un standard
     */
        @GetMapping("/{standardId}/terms")
        @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(
            summary = "Lister les termes d'un standard",
            description = "Récupère tous les termes (champs) définis pour un standard donné"
    )
    @ApiResponse(responseCode = "200", description = "Liste des termes du standard")
    public ResponseEntity<List<StandardTermDto>> getTermsByStandard(
            @Parameter(description = "ID du standard", required = true)
            @PathVariable String standardId) {
        log.info("Récupération des termes du standard: {}", standardId);

        List<StandardTermDto> terms = standardService.getTermsByStandard(standardId);
        return ResponseEntity.ok(terms);
    }

    /**
     * Mettre à jour un terme
     */
    @PutMapping("/terms/{termId}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Mettre à jour un terme",
            description = "Modifie les propriétés d'un terme existant. Restreint aux ADMIN."
    )
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Terme mis à jour"),
            @ApiResponse(responseCode = "404", description = "Terme non trouvé")
    })
    public ResponseEntity<StandardTermDto> updateTerm(
            @Parameter(description = "ID du terme", required = true)
            @PathVariable String termId,
            @Valid @RequestBody StandardTermDto termDto) {
        log.info("Mise à jour du terme: {}", termId);

        StandardTermDto updated = standardService.updateTerm(termId, termDto);
        return ResponseEntity.ok(updated);
    }

    /**
     * Valider un champ contre les règles d'un terme
     */
    @PostMapping("/{standardId}/validate")
        @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(
            summary = "Valider un champ contre un standard",
            description = "Vérifie qu'une valeur respecte les règles d'un terme du standard (type, format, longueur, etc.)"
    )
    @ApiResponse(responseCode = "200", description = "Résultat de la validation")
    public ResponseEntity<ValidationResult> validateField(
            @Parameter(description = "ID du standard", required = true)
            @PathVariable String standardId,
            @Parameter(description = "Nom du champ/terme", required = true)
            @RequestParam String fieldName,
            @Parameter(description = "Valeur du champ", required = true)
            @RequestParam String fieldValue,
            @Parameter(description = "Type de données", required = true)
            @RequestParam String dataType) {
        log.info("Validation du champ {} contre le standard {}", fieldName, standardId);

        boolean isValid = standardService.validateFieldAgainstStandard(standardId, fieldName, fieldValue, dataType);
        ValidationResult result = new ValidationResult(isValid, 
                isValid ? "Validation réussie" : "La valeur ne respecte pas les règles du standard");

        return ResponseEntity.ok(result);
    }

    /*
     * DTO pour le résultat de validation
     */
        @lombok.Data
        @lombok.AllArgsConstructor
        public static class ValidationResult {
                private boolean valid;
                private String message;
        }
}
