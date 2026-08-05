package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.common.ApiResponse;
import com.iol.etlplatform.dto.user.UserDto;
import com.iol.etlplatform.dto.user.UpdateUserRoleRequest;
import com.iol.etlplatform.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "User Management", description = "Gestion des utilisateurs (Admin only)")
public class UserController {

    private final UserService userService;

    /**
     * Récupère la liste de tous les utilisateurs
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Lister tous les utilisateurs")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Liste d'utilisateurs récupérée"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Accès refusé - Admin requis")
    })
    public ResponseEntity<ApiResponse<List<UserDto>>> getAllUsers() {
        log.info("Requête: Récupération de la liste de tous les utilisateurs");
        List<UserDto> users = userService.getAllUsers();
        return ResponseEntity.ok(new ApiResponse<>("Liste des utilisateurs récupérée.", users));
    }

    /**
     * Récupère un utilisateur par ID
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Récupérer un utilisateur par ID")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Utilisateur trouvé"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Utilisateur non trouvé"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Accès refusé - Admin requis")
    })
    public ResponseEntity<ApiResponse<UserDto>> getUserById(@PathVariable String id) {
        log.info("Requête: Récupération de l'utilisateur avec ID: {}", id);
        UserDto user = userService.getUserById(id);
        return ResponseEntity.ok(new ApiResponse<>("Utilisateur récupéré.", user));
    }

    /**
     * Récupère un utilisateur par email
     */
    @GetMapping("/email/{email}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Récupérer un utilisateur par email")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Utilisateur trouvé"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Utilisateur non trouvé"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Accès refusé - Admin requis")
    })
    public ResponseEntity<ApiResponse<UserDto>> getUserByEmail(@PathVariable String email) {
        log.info("Requête: Récupération de l'utilisateur avec email: {}", email);
        UserDto user = userService.getUserByEmail(email);
        return ResponseEntity.ok(new ApiResponse<>("Utilisateur récupéré.", user));
    }

    /**
     * Met à jour le rôle d'un utilisateur
     */
    @PutMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Mettre à jour le rôle d'un utilisateur")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Rôle mis à jour"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Utilisateur non trouvé"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Accès refusé - Admin requis")
    })
    public ResponseEntity<ApiResponse<UserDto>> updateUserRole(
            @PathVariable String id,
            @Valid @RequestBody UpdateUserRoleRequest request) {
        log.info("Requête: Mise à jour du rôle de l'utilisateur {} vers {}", id, request.getRole());
        UserDto updatedUser = userService.updateUserRole(id, request);
        return ResponseEntity.ok(new ApiResponse<>("Rôle de l'utilisateur mis à jour.", updatedUser));
    }

    /**
     * Supprime un utilisateur
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Supprimer un utilisateur")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Utilisateur supprimé"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Utilisateur non trouvé"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Accès refusé - Admin requis")
    })
    public ResponseEntity<ApiResponse<String>> deleteUser(@PathVariable String id) {
        log.info("Requête: Suppression de l'utilisateur avec ID: {}", id);
        userService.deleteUser(id);
        return ResponseEntity.ok(new ApiResponse<>("Utilisateur supprimé avec succès.", ""));
    }
}
