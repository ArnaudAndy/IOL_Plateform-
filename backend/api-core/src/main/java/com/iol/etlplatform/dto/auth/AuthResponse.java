package com.iol.etlplatform.dto.auth;

import com.iol.etlplatform.entity.enums.UserRole;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String userId;
    private String name;
    private String email;
    private UserRole role;
}
