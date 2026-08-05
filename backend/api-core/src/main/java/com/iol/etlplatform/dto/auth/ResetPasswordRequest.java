package com.iol.etlplatform.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ResetPasswordRequest {
    @Email
    @NotBlank
    private String email;

    @Pattern(regexp = "\\d{6}", message = "Le code doit contenir 6 chiffres.")
    private String code;

    @NotBlank
    @Size(min = 8, message = "Le mot de passe doit contenir au moins 8 caracteres.")
    private String newPassword;
}
