package com.iol.etlplatform.dto.user;

import com.iol.etlplatform.entity.enums.UserRole;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UpdateUserRoleRequest {
    @NotNull(message = "Le rôle ne peut pas être null")
    private UserRole role;
}
