package com.iol.etlplatform.dto.auth;

public record AuthSession(AuthResponse response, String refreshToken) {
}
