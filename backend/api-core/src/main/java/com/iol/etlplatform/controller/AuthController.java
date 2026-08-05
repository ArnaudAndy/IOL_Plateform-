package com.iol.etlplatform.controller;

import com.iol.etlplatform.dto.auth.AuthResponse;
import com.iol.etlplatform.dto.auth.AuthSession;
import com.iol.etlplatform.dto.auth.ChangePasswordRequest;
import com.iol.etlplatform.dto.auth.ForgotPasswordRequest;
import com.iol.etlplatform.dto.auth.LoginRequest;
import com.iol.etlplatform.dto.auth.RegisterRequest;
import com.iol.etlplatform.dto.auth.ResetPasswordRequest;
import com.iol.etlplatform.dto.common.ApiResponse;
import com.iol.etlplatform.dto.user.UpdateProfileRequest;
import com.iol.etlplatform.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "iol_refresh_token";

    private final AuthService authService;

    @Value("${app.auth.refresh-token-days:7}")
    private long refreshTokenDays;

    @Value("${app.auth.cookie-secure:false}")
    private boolean cookieSecure;

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<AuthResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return sessionResponse("Inscription reussie.", authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        return sessionResponse("Connexion reussie.", authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        return sessionResponse("Session renouvelee.", authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<String>> logout(
            @CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        authService.logout(refreshToken);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                .body(new ApiResponse<>("Deconnexion reussie.", ""));
    }

    @PostMapping("/password/forgot")
    public ResponseEntity<ApiResponse<String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        authService.requestPasswordReset(request);
        return ResponseEntity.ok(new ApiResponse<>(
                "Si ce compte existe, un code a ete envoye par email.", ""));
    }

    @PostMapping("/password/reset")
    public ResponseEntity<ApiResponse<String>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(new ApiResponse<>("Mot de passe reinitialise.", ""));
    }

    @PutMapping("/profile")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AuthResponse>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request) {
        return sessionResponse("Profil mis a jour.", authService.updateProfile(request));
    }

    @PutMapping("/password")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AuthResponse>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        return sessionResponse("Mot de passe modifie.", authService.changePassword(request));
    }

    @PostMapping("/create-initial-admin")
    public ResponseEntity<ApiResponse<AuthResponse>> createInitialAdmin(
            @Valid @RequestBody RegisterRequest request) {
        return sessionResponse("Admin cree avec succes.", authService.createInitialAdmin(request));
    }

    private ResponseEntity<ApiResponse<AuthResponse>> sessionResponse(String message, AuthSession session) {
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken()).toString())
                .body(new ApiResponse<>(message, session.response()));
    }

    private ResponseCookie refreshCookie(String token) {
        return ResponseCookie.from(REFRESH_COOKIE, token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/api")
                .maxAge(Duration.ofDays(refreshTokenDays))
                .build();
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/api")
                .maxAge(Duration.ZERO)
                .build();
    }
}
