package com.iol.etlplatform.service;

import com.iol.etlplatform.dto.auth.AuthResponse;
import com.iol.etlplatform.dto.auth.AuthSession;
import com.iol.etlplatform.dto.auth.ChangePasswordRequest;
import com.iol.etlplatform.dto.auth.ForgotPasswordRequest;
import com.iol.etlplatform.dto.auth.LoginRequest;
import com.iol.etlplatform.dto.auth.RegisterRequest;
import com.iol.etlplatform.dto.auth.ResetPasswordRequest;
import com.iol.etlplatform.dto.user.UpdateProfileRequest;
import com.iol.etlplatform.entity.PasswordResetCode;
import com.iol.etlplatform.entity.RefreshToken;
import com.iol.etlplatform.entity.User;
import com.iol.etlplatform.entity.enums.UserRole;
import com.iol.etlplatform.exception.BadRequestException;
import com.iol.etlplatform.repository.PasswordResetCodeRepository;
import com.iol.etlplatform.repository.RefreshTokenRepository;
import com.iol.etlplatform.repository.UserRepository;
import com.iol.etlplatform.security.JwtService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordResetCodeRepository passwordResetCodeRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final JavaMailSender mailSender;

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.auth.refresh-token-days:7}")
    private long refreshTokenDays;

    @Value("${app.auth.reset-code-minutes:10}")
    private long resetCodeMinutes;

    @Value("${app.auth.mail-from:no-reply@iol.local}")
    private String mailFrom;

    @Transactional
    public AuthSession register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new BadRequestException("Cet email existe deja.");
        }
        User user = new User();
        user.setName(request.getName().trim());
        user.setEmail(email);
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(UserRole.USER);
        return issueSession(userRepository.save(user));
    }

    @Transactional
    public AuthSession login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.getPassword()));
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("Identifiants invalides."));
        return issueSession(user);
    }

    @Transactional
    public AuthSession refresh(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BadRequestException("Session de renouvellement absente.");
        }
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash(rawToken))
                .orElseThrow(() -> new BadRequestException("Session de renouvellement invalide."));
        if (stored.isRevoked() || stored.getExpiresAt().isBefore(Instant.now())) {
            throw new BadRequestException("Session de renouvellement expiree.");
        }
        stored.setRevoked(true);
        refreshTokenRepository.save(stored);
        User user = userRepository.findById(stored.getUserId())
                .orElseThrow(() -> new BadRequestException("Utilisateur introuvable."));
        return issueSession(user);
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return;
        }
        refreshTokenRepository.findByTokenHash(hash(rawToken)).ifPresent(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }

    @Transactional
    public AuthSession updateProfile(UpdateProfileRequest request) {
        User user = currentUser();
        String email = normalizeEmail(request.getEmail());
        userRepository.findByEmail(email)
                .filter(existing -> !existing.getId().equals(user.getId()))
                .ifPresent(existing -> { throw new BadRequestException("Cet email existe deja."); });
        user.setName(request.getName().trim());
        user.setEmail(email);
        revokeAll(user.getId());
        return issueSession(userRepository.save(user));
    }

    @Transactional
    public AuthSession changePassword(ChangePasswordRequest request) {
        User user = currentUser();
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPassword())) {
            throw new BadRequestException("Le mot de passe actuel est incorrect.");
        }
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        revokeAll(user.getId());
        return issueSession(user);
    }

    @Transactional
    public void requestPasswordReset(ForgotPasswordRequest request) {
        userRepository.findByEmail(normalizeEmail(request.getEmail())).ifPresent(user -> {
            List<PasswordResetCode> previous = passwordResetCodeRepository
                    .findByUserIdAndConsumedFalse(user.getId());
            previous.forEach(code -> code.setConsumed(true));
            passwordResetCodeRepository.saveAll(previous);

            String code = String.format(Locale.ROOT, "%06d", secureRandom.nextInt(1_000_000));
            PasswordResetCode reset = new PasswordResetCode();
            reset.setUserId(user.getId());
            reset.setCodeHash(hash(user.getId() + ":" + code));
            reset.setExpiresAt(Instant.now().plusSeconds(resetCodeMinutes * 60));
            reset.setCreatedAt(Instant.now());
            reset.setAttempts(0);
            reset.setConsumed(false);
            passwordResetCodeRepository.save(reset);
            sendResetEmail(user, code);
        });
    }

    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.getEmail()))
                .orElseThrow(() -> new BadRequestException("Code invalide ou expire."));
        PasswordResetCode reset = passwordResetCodeRepository
                .findFirstByUserIdAndConsumedFalseOrderByCreatedAtDesc(user.getId())
                .orElseThrow(() -> new BadRequestException("Code invalide ou expire."));
        if (reset.getExpiresAt().isBefore(Instant.now()) || reset.getAttempts() >= 5) {
            reset.setConsumed(true);
            passwordResetCodeRepository.save(reset);
            throw new BadRequestException("Code invalide ou expire.");
        }
        if (!MessageDigest.isEqual(
                reset.getCodeHash().getBytes(StandardCharsets.US_ASCII),
                hash(user.getId() + ":" + request.getCode()).getBytes(StandardCharsets.US_ASCII))) {
            reset.setAttempts(reset.getAttempts() + 1);
            passwordResetCodeRepository.save(reset);
            throw new BadRequestException("Code invalide ou expire.");
        }
        reset.setConsumed(true);
        passwordResetCodeRepository.save(reset);
        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);
        revokeAll(user.getId());
    }

    @Transactional
    public AuthSession createInitialAdmin(RegisterRequest request) {
        boolean adminExists = userRepository.findAll().stream().anyMatch(u -> u.getRole() == UserRole.ADMIN);
        if (adminExists) {
            throw new BadRequestException("Un administrateur existe deja.");
        }
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new BadRequestException("Cet email existe deja.");
        }
        User admin = new User();
        admin.setName(request.getName().trim());
        admin.setEmail(email);
        admin.setPassword(passwordEncoder.encode(request.getPassword()));
        admin.setRole(UserRole.ADMIN);
        return issueSession(userRepository.save(admin));
    }

    private AuthSession issueSession(User user) {
        UserDetails details = org.springframework.security.core.userdetails.User.withUsername(user.getEmail())
                .password(user.getPassword())
                .authorities("ROLE_" + user.getRole().name())
                .build();
        String accessToken = jwtService.generateToken(details);
        String rawRefreshToken = randomToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(hash(rawRefreshToken));
        refreshToken.setCreatedAt(Instant.now());
        refreshToken.setExpiresAt(Instant.now().plusSeconds(refreshTokenDays * 86_400));
        refreshToken.setRevoked(false);
        refreshTokenRepository.save(refreshToken);
        AuthResponse response = new AuthResponse(accessToken, String.valueOf(user.getId()),
                user.getName(), user.getEmail(), user.getRole());
        return new AuthSession(response, rawRefreshToken);
    }

    private User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BadRequestException("Utilisateur non authentifie.");
        }
        return userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new BadRequestException("Utilisateur introuvable."));
    }

    private void revokeAll(Long userId) {
        List<RefreshToken> tokens = refreshTokenRepository.findByUserIdAndRevokedFalse(userId);
        tokens.forEach(token -> token.setRevoked(true));
        refreshTokenRepository.saveAll(tokens);
    }

    private void sendResetEmail(User user, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(user.getEmail());
        message.setSubject("Code de recuperation IOL ETL");
        message.setText("Bonjour " + user.getName() + ",\n\nVotre code de recuperation est : " + code
                + "\nIl expire dans " + resetCodeMinutes + " minutes.\n\nIOL ETL Platform");
        mailSender.send(message);
    }

    private String randomToken() {
        byte[] bytes = new byte[64];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    private String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
