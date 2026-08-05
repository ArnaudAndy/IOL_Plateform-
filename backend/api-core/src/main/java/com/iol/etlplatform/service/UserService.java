package com.iol.etlplatform.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import com.iol.etlplatform.dto.user.UpdateUserRoleRequest;
import com.iol.etlplatform.dto.user.UserDto;
import com.iol.etlplatform.entity.User;
import com.iol.etlplatform.exception.ResourceNotFoundException;
import com.iol.etlplatform.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final KeycloakAdminClient keycloakAdminClient;

    @Value("${app.auth.mode:LOCAL}")
    private String authMode;

    public List<UserDto> getAllUsers() {
        log.info("Récupération de la liste de tous les utilisateurs");
        if (keycloak()) return keycloakAdminClient.listUsers();
        return userRepository.findAll().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    public UserDto getUserById(String id) {
        log.info("Récupération de l'utilisateur avec ID: {}", id);
        if (keycloak()) return keycloakAdminClient.getUser(id);
        User user = userRepository.findById(parseId(id))
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé avec l'ID: " + id));
        return convertToDto(user);
    }

    public UserDto getUserByEmail(String email) {
        log.info("Récupération de l'utilisateur avec l'email: {}", email);
        if (keycloak()) return keycloakAdminClient.getUserByEmail(email);
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé avec l'email: " + email));
        return convertToDto(user);
    }

    public UserDto updateUserRole(String userId, UpdateUserRoleRequest request) {
        log.info("Mise à jour du rôle de l'utilisateur {}: {}", userId, request.getRole());
        if (keycloak()) return keycloakAdminClient.updateRole(userId, request.getRole());
        User user = userRepository.findById(parseId(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Utilisateur non trouvé avec l'ID: " + userId));
        user.setRole(request.getRole());
        User updatedUser = userRepository.save(user);
        log.info("Rôle de l'utilisateur {} mis à jour avec succès vers: {}", userId, updatedUser.getRole());
        return convertToDto(updatedUser);
    }

    public void deleteUser(String userId) {
        log.info("Suppression de l'utilisateur avec l'ID: {}", userId);
        if (keycloak()) {
            keycloakAdminClient.deleteUser(userId);
            return;
        }
        Long id = parseId(userId);
        if (!userRepository.existsById(id)) {
            throw new ResourceNotFoundException("Utilisateur non trouvé avec l'ID: " + userId);
        }
        userRepository.deleteById(id);
        log.info("Utilisateur {} supprimé avec succès", userId);
    }

    private Long parseId(String id) {
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException e) {
            throw new ResourceNotFoundException("Utilisateur non trouvé avec l'ID: " + id);
        }
    }

    private UserDto convertToDto(User user) {
        return new UserDto(String.valueOf(user.getId()), user.getName(), user.getEmail(), user.getRole(), true);
    }

    private boolean keycloak() {
        return "KEYCLOAK".equalsIgnoreCase(authMode);
    }
}
