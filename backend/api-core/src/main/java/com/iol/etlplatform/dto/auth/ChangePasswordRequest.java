package com.iol.etlplatform.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {
    @NotBlank
    private String currentPassword;

    @NotBlank
    @Size(min = 8, message = "Le nouveau mot de passe doit contenir au moins 8 caracteres.")
    private String newPassword;
}
