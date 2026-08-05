package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.common.ApiResponse;
import com.iol.etlplatform.dto.connection.DestinationConnectionDto;
import com.iol.etlplatform.service.DestinationConnectionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Gestion des connexions de destination réutilisables (le data warehouse cible).
 * Chaque utilisateur gere ses connexions. Un administrateur voit tout.
 */
@RestController
@RequestMapping("/api/connections")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Destination Connections", description = "Connexions de destination réutilisables")
public class DestinationConnectionController {

    private final DestinationConnectionService service;

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(summary = "Créer une connexion de destination")
    public ResponseEntity<ApiResponse<DestinationConnectionDto>> create(
            @Valid @RequestBody DestinationConnectionDto dto) {
        return ResponseEntity.ok(new ApiResponse<>("Connexion créée.", service.create(dto)));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(summary = "Lister les connexions de destination")
    public ResponseEntity<ApiResponse<List<DestinationConnectionDto>>> findAll() {
        return ResponseEntity.ok(new ApiResponse<>("Connexions récupérées.", service.findAll()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(summary = "Récupérer une connexion par ID")
    public ResponseEntity<ApiResponse<DestinationConnectionDto>> findById(@PathVariable String id) {
        return ResponseEntity.ok(new ApiResponse<>("Connexion récupérée.", service.findById(id)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(summary = "Mettre à jour une connexion")
    public ResponseEntity<ApiResponse<DestinationConnectionDto>> update(
            @PathVariable String id,
            @Valid @RequestBody DestinationConnectionDto dto) {
        return ResponseEntity.ok(new ApiResponse<>("Connexion mise à jour.", service.update(id, dto)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(summary = "Supprimer une connexion")
    public ResponseEntity<ApiResponse<String>> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.ok(new ApiResponse<>("Connexion supprimée.", ""));
    }

    @PostMapping("/{id}/test")
    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @Operation(summary = "Tester la connexion JDBC réelle")
    public ResponseEntity<ApiResponse<String>> test(@PathVariable String id) {
        return ResponseEntity.ok(new ApiResponse<>("Test connexion.", service.testConnection(id)));
    }
}
