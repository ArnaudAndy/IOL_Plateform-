package com.iol.etlplatform.dto.user;

import com.iol.etlplatform.entity.enums.UserRole;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserDto {
    private String id;
    private String name;
    private String email;
    private UserRole role;
    private boolean active;
}
